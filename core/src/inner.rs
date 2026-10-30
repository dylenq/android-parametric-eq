//! Plain-Rust state machine behind the FFI object. Every edit is validated before it mutates
//! anything and is recorded in [`History`]; nothing here panics on caller input.

use crate::achieved::BinGains;
use crate::error::EqError;
use crate::dsp::biquad::Coeffs;
use crate::fit;
use crate::graphic;
use crate::grid::{self, Grid};
use crate::headroom::{self, LimiterParams};
use crate::history::{EditSnapshot, History, PresetCtx};
use crate::presets::{self, MAX_USER_PRESETS, Preset, PresetCurve};
use crate::model::{Band, EqMode, EqState, FilterKind, GraphicState, MAX_BANDS, MAX_GAIN_DB};

pub const DEFAULT_FRAME_MS: f32 = 85.3;
pub const MIN_FRAME_MS: f32 = 5.0;
pub const MAX_FRAME_MS: f32 = 300.0;
pub const MIN_CURVE_POINTS: u32 = 2;
pub const MAX_CURVE_POINTS: u32 = 2048;
pub const TONE_BASS_HZ: f64 = 100.0;
pub const TONE_TREBLE_HZ: f64 = 10_000.0;
pub const TONE_Q: f64 = 0.707;
pub const TONE_MAX_DB: f32 = 12.0;
/// Largest balance attenuation of the quieter side, relative to the louder side.
pub const MAX_BALANCE_ATTENUATION_DB: f32 = -60.0;
/// Lowest absolute per-channel input gain the core hands to the engine (DynamicsProcessing-safe;
/// preamp -24 plus the full -60 dB balance attenuation).
pub const MIN_CHANNEL_GAIN_DB: f32 = -84.0;
/// Balance magnitudes at or below this snap to centre.
const BALANCE_SNAP: f32 = 0.01;

pub struct Inner {
    pub mode: EqMode,
    pub parametric: Vec<Band>,
    pub graphic: GraphicState,
    pub preamp_db: f32,
    pub bass_db: f32,
    pub treble_db: f32,
    /// -1.0 (full left) .. 1.0 (full right); > 0 attenuates the left channel.
    pub balance: f32,
    pub limiter_enabled: bool,
    pub bypass: bool,
    pub frame_duration_ms: f32,
    /// User presets in creation order (built-ins are code, not stored).
    pub presets: Vec<Preset>,
    /// Key of the preset last applied or saved (`builtin:<name>` / `user:<name>`).
    pub active_preset: Option<String>,
    pub(crate) history: History,
    /// Device state, never saved or put in undo history: the most bands the engine accepts.
    pub(crate) engine_band_cap: Option<usize>,
}

impl Default for Inner {
    fn default() -> Self {
        Inner {
            mode: EqMode::Graphic,
            parametric: EqState::default().bands,
            graphic: GraphicState::default(),
            preamp_db: 0.0,
            bass_db: 0.0,
            treble_db: 0.0,
            balance: 0.0,
            limiter_enabled: true,
            bypass: false,
            frame_duration_ms: DEFAULT_FRAME_MS,
            presets: Vec::new(),
            active_preset: None,
            history: History::default(),
            engine_band_cap: None,
        }
    }
}

/// Curve data on the core's fixed log grid.
pub struct Curve {
    pub freqs_hz: Vec<f32>,
    pub ideal_db: Vec<f32>,
    pub achieved_db: Vec<f32>,
}

/// Everything the Phase 3 service needs to configure the engine.
pub struct Engine {
    pub sample_rate: u32,
    pub frame_duration_ms: f32,
    pub block_size: u32,
    pub cutoffs_hz: Vec<f32>,
    pub gains_db: Vec<f32>,
    pub input_gain_db: f32,
    pub input_gain_left_db: f32,
    pub input_gain_right_db: f32,
    pub limiter: LimiterParams,
    pub limiter_enabled: bool,
    pub bypass: bool,
    pub peak_db: f32,
    pub suggested_preamp_db: f32,
    pub headroom_after_preamp_db: f32,
    pub layout_id: u32,
}

fn finite_band(b: &Band) -> bool {
    b.freq.is_finite() && b.gain_db.is_finite() && b.q.is_finite()
}

impl Inner {
    pub fn edit_snapshot(&self) -> EditSnapshot {
        EditSnapshot {
            bands: self.parametric.clone(),
            band_count: self.graphic.band_count,
            gains_db: self.graphic.gains_db.clone(),
            preamp_db: self.preamp_db,
            bass_db: self.bass_db,
            treble_db: self.treble_db,
            balance: self.balance,
            preset_ctx: None,
        }
    }

    /// A snapshot that also carries the mode and the active-preset marker (apply steps only).
    fn ctx_snapshot(&self) -> EditSnapshot {
        EditSnapshot {
            preset_ctx: Some(PresetCtx { mode: self.mode, active: self.active_preset.clone() }),
            ..self.edit_snapshot()
        }
    }

    fn apply(&mut self, s: EditSnapshot) {
        self.parametric = s.bands;
        self.graphic = GraphicState { band_count: s.band_count, gains_db: s.gains_db };
        self.preamp_db = s.preamp_db;
        self.bass_db = s.bass_db;
        self.treble_db = s.treble_db;
        self.balance = s.balance;
        if let Some(ctx) = s.preset_ctx {
            self.mode = ctx.mode;
            self.active_preset = ctx.active.filter(|k| self.find_preset(k).is_some());
        }
    }

    /// Runs a validated mutation and records it. `f` must not mutate when it returns Err.
    fn edit<T>(&mut self, f: impl FnOnce(&mut Self) -> Result<T, EqError>) -> Result<T, EqError> {
        let before = self.edit_snapshot();
        let out = f(self)?;
        let after = self.edit_snapshot();
        self.history.record(before, &after);
        Ok(out)
    }

    pub fn set_band(&mut self, index: usize, mut band: Band) -> Result<(), EqError> {
        if !finite_band(&band) {
            return Err(EqError::InvalidValue);
        }
        if index >= self.parametric.len() {
            return Err(EqError::OutOfRange);
        }
        band.clamp();
        self.edit(|s| {
            if let Some(slot) = s.parametric.get_mut(index) {
                *slot = band;
            }
            Ok(())
        })
    }

    /// Appends a flat 1 kHz peak, Q 1, and returns its index.
    pub fn add_band(&mut self) -> Result<usize, EqError> {
        if self.parametric.len() >= MAX_BANDS {
            return Err(EqError::TooManyBands);
        }
        self.edit(|s| {
            let mut b = Band::new(FilterKind::Peak, 1_000.0);
            b.q = 1.0;
            s.parametric.push(b);
            Ok(s.parametric.len() - 1)
        })
    }

    pub fn remove_band(&mut self, index: usize) -> Result<(), EqError> {
        if index >= self.parametric.len() {
            return Err(EqError::OutOfRange);
        }
        self.edit(|s| {
            s.parametric.remove(index);
            Ok(())
        })
    }

    pub fn set_graphic_gain(&mut self, index: usize, db: f32) -> Result<(), EqError> {
        if !db.is_finite() {
            return Err(EqError::InvalidValue);
        }
        if index >= self.graphic.gains_db.len() {
            return Err(EqError::OutOfRange);
        }
        self.edit(|s| Ok(s.graphic.set_gain(index, db)?))
    }

    pub fn set_graphic_band_count(&mut self, count: u32) -> Result<(), EqError> {
        if !crate::model::GRAPHIC_COUNTS.contains(&count) {
            return Err(EqError::InvalidValue);
        }
        self.edit(|s| Ok(s.graphic.set_band_count(count)?))
    }

    pub fn set_preamp(&mut self, db: f32) -> Result<(), EqError> {
        if !db.is_finite() {
            return Err(EqError::InvalidValue);
        }
        self.edit(|s| {
            s.preamp_db = db.clamp(-MAX_GAIN_DB, MAX_GAIN_DB);
            Ok(())
        })
    }

    pub fn set_bass(&mut self, db: f32) -> Result<(), EqError> {
        if !db.is_finite() {
            return Err(EqError::InvalidValue);
        }
        self.edit(|s| {
            s.bass_db = db.clamp(-TONE_MAX_DB, TONE_MAX_DB);
            Ok(())
        })
    }

    pub fn set_treble(&mut self, db: f32) -> Result<(), EqError> {
        if !db.is_finite() {
            return Err(EqError::InvalidValue);
        }
        self.edit(|s| {
            s.treble_db = db.clamp(-TONE_MAX_DB, TONE_MAX_DB);
            Ok(())
        })
    }

    pub fn set_balance(&mut self, v: f32) -> Result<(), EqError> {
        if !v.is_finite() {
            return Err(EqError::InvalidValue);
        }
        self.edit(|s| {
            s.balance = snap_balance(v);
            Ok(())
        })
    }

    pub fn set_frame_duration_ms(&mut self, ms: f32) -> Result<(), EqError> {
        if !ms.is_finite() || !(MIN_FRAME_MS..=MAX_FRAME_MS).contains(&ms) {
            return Err(EqError::InvalidValue);
        }
        self.frame_duration_ms = ms;
        Ok(())
    }

    pub fn begin_edit(&mut self) {
        self.history.begin_edit();
    }

    pub fn end_edit(&mut self) {
        self.history.end_edit();
    }

    pub fn undo(&mut self) -> bool {
        let cur = self.ctx_snapshot();
        match self.history.undo(&cur) {
            Some(s) => {
                self.apply(s);
                true
            }
            None => false,
        }
    }

    pub fn redo(&mut self) -> bool {
        let cur = self.ctx_snapshot();
        match self.history.redo(&cur) {
            Some(s) => {
                self.apply(s);
                true
            }
            None => false,
        }
    }

    pub fn can_undo(&self) -> bool {
        self.history.can_undo()
    }

    pub fn can_redo(&self) -> bool {
        self.history.can_redo()
    }

    /// Macros-style state of the enabled bands only, preamp 0 and no bypass: disabled bands
    /// contribute 0 dB and the ideal curve excludes preamp.
    pub fn eq_state_for_curve(&self) -> EqState {
        EqState {
            bands: self.parametric.iter().filter(|b| b.enabled).copied().collect(),
            preamp_db: 0.0,
            bypass: false,
            clip_protection: self.limiter_enabled,
        }
    }

    /// The ideal curve (dB) for the active mode as a function of frequency.
    fn base_target<'a>(&'a self, sample_rate: f64) -> Box<dyn Fn(f64) -> f64 + 'a> {
        match self.mode {
            EqMode::Parametric => {
                Box::new(fit::parametric_target(&self.eq_state_for_curve(), sample_rate))
            }
            EqMode::Graphic => {
                match fit::graphic_target(self.graphic.band_count, &self.graphic.gains_db) {
                    Some(t) => Box::new(t),
                    None => Box::new(|_| 0.0),
                }
            }
        }
    }

    /// The ideal curve including the tone shelves. Each shelf is skipped when its gain is
    /// exactly 0, so zero tone returns the base closure untouched.
    fn target<'a>(&'a self, sample_rate: f64) -> Box<dyn Fn(f64) -> f64 + 'a> {
        let base = self.base_target(sample_rate);
        if self.bass_db == 0.0 && self.treble_db == 0.0 {
            return base;
        }
        let low = (self.bass_db != 0.0).then(|| {
            Coeffs::design(FilterKind::LowShelf, TONE_BASS_HZ, self.bass_db as f64, TONE_Q, sample_rate)
        });
        let high = (self.treble_db != 0.0).then(|| {
            Coeffs::design(FilterKind::HighShelf, TONE_TREBLE_HZ, self.treble_db as f64, TONE_Q, sample_rate)
        });
        Box::new(move |f| {
            let mut v = base(f);
            if let Some(c) = &low {
                v += c.magnitude_db(f, sample_rate);
            }
            if let Some(c) = &high {
                v += c.magnitude_db(f, sample_rate);
            }
            v
        })
    }

    fn grid_for(&self, sample_rate: u32, frame_ms: f32) -> Result<Grid, EqError> {
        Grid::hybrid_capped(sample_rate, frame_ms, self.engine_band_cap).map_err(|_| EqError::InvalidValue)
    }

    pub fn response_curve(
        &self,
        points: u32,
        sample_rate: u32,
        frame_ms: f32,
    ) -> Result<Curve, EqError> {
        if !(MIN_CURVE_POINTS..=MAX_CURVE_POINTS).contains(&points) {
            return Err(EqError::InvalidValue);
        }
        let grid = self.grid_for(sample_rate, frame_ms)?;
        let sr = sample_rate as f64;
        let target = self.target(sr);
        let gains = fit::fit(&grid, &target);
        let bins = BinGains::new(&grid, &gains);

        let n = points as usize;
        let mut freqs = Vec::with_capacity(n);
        let (mut ideal, mut achieved) = (Vec::with_capacity(n), Vec::with_capacity(n));
        for i in 0..n {
            let f = log_grid_hz(i, n);
            freqs.push(f as f32);
            ideal.push(finite_or_zero(target(f)) as f32);
            achieved.push(finite_or_zero(bins.at(f)) as f32);
        }
        Ok(Curve { freqs_hz: freqs, ideal_db: ideal, achieved_db: achieved })
    }

    /// One band's own magnitude (dB) on the same log grid as `response_curve`. Read-only; the
    /// band is evaluated as enabled so a disabled selected band can still be drawn.
    pub fn band_response(&self, index: usize, points: u32, sample_rate: u32) -> Result<Vec<f32>, EqError> {
        let band = *self.parametric.get(index).ok_or(EqError::OutOfRange)?;
        if !(MIN_CURVE_POINTS..=MAX_CURVE_POINTS).contains(&points) {
            return Err(EqError::InvalidValue);
        }
        if !(8_000..=384_000).contains(&sample_rate) {
            return Err(EqError::InvalidValue);
        }
        let target = fit::band_target(band, sample_rate as f64);
        let n = points as usize;
        Ok((0..n).map(|i| finite_or_zero(target(log_grid_hz(i, n))) as f32).collect())
    }

    /// Restores the default bands and a 0 dB preamp as one undo step (no step when unchanged).
    pub fn reset_bands(&mut self) {
        if self.parametric == EqState::default().bands && self.preamp_db == 0.0 {
            return;
        }
        let _ = self.edit(|s| {
            s.parametric = EqState::default().bands;
            s.preamp_db = 0.0;
            Ok(())
        });
    }

    pub fn engine_params(&self, sample_rate: u32, frame_ms: f32) -> Result<Engine, EqError> {
        let grid = self.grid_for(sample_rate, frame_ms)?;
        let target = self.target(sample_rate as f64);
        let gains = fit::fit(&grid, &target);
        let peak = headroom::peak_db(&grid, &gains);
        let preamp = self.preamp_db.clamp(-(MAX_GAIN_DB), MAX_GAIN_DB);
        let att = balance_attenuation_db(self.balance);
        let (att_l, att_r) = if self.balance > 0.0 {
            (att, 0.0)
        } else if self.balance < 0.0 {
            (0.0, att)
        } else {
            (0.0, 0.0)
        };
        let left = (preamp + att_l).max(MIN_CHANNEL_GAIN_DB);
        let right = (preamp + att_r).max(MIN_CHANNEL_GAIN_DB);
        Ok(Engine {
            sample_rate,
            frame_duration_ms: frame_ms,
            block_size: grid.block,
            cutoffs_hz: grid.cutoffs_hz.clone(),
            gains_db: gains.iter().map(|&g| g as f32).collect(),
            input_gain_db: preamp,
            input_gain_left_db: left,
            input_gain_right_db: right,
            limiter: LimiterParams::default(),
            limiter_enabled: self.limiter_enabled,
            bypass: self.bypass,
            peak_db: peak as f32,
            suggested_preamp_db: headroom::suggested_preamp_db(peak) as f32,
            headroom_after_preamp_db: headroom::headroom_after_preamp_db(peak, preamp as f64)
                as f32,
            layout_id: grid::layout_id(&grid),
        })
    }

    // ---- presets ----

    /// Resolves a key against the built-ins and the user presets.
    pub fn find_preset(&self, key: &str) -> Option<Preset> {
        presets::find_in(&self.presets, key)
    }

    fn apply_curve(&mut self, c: &PresetCurve) {
        self.parametric = c.bands.clone();
        self.graphic = GraphicState {
            band_count: c.graphic_band_count,
            gains_db: c.graphic_gains_db.clone(),
        };
        self.preamp_db = c.preamp_db;
        self.bass_db = c.bass_db;
        self.treble_db = c.treble_db;
        self.balance = c.balance;
        self.mode = c.mode;
    }

    /// Replaces mode, both editors, preamp, tone and balance in one undo step. Returns whether a
    /// step was recorded (false when nothing changed). Does not close an open gesture.
    pub fn apply_preset(&mut self, key: &str) -> Result<bool, EqError> {
        let mut preset = self.find_preset(key).ok_or(EqError::NotFound)?;
        if !preset.curve.sanitize() {
            return Err(EqError::InvalidValue);
        }
        let stored_key = if key.starts_with("builtin:") {
            presets::builtin_key(&preset.name)
        } else {
            presets::user_key(&preset.name)
        };
        let before = self.ctx_snapshot();
        self.apply_curve(&preset.curve);
        self.active_preset = Some(stored_key);
        let after = self.ctx_snapshot();
        self.history.record(before.clone(), &after);
        Ok(before != after)
    }

    fn builtin_name_taken(name: &str) -> bool {
        let n = presets::normalize(name);
        presets::builtin_presets().iter().any(|p| presets::normalize(&p.name) == n)
    }

    /// Stores the current sound as a user preset (overwriting a same-named one) and makes it
    /// active. Not an undo step. Returns the key.
    pub fn save_preset(&mut self, name: &str) -> Result<String, EqError> {
        let name = presets::validate_name(name)?;
        if Self::builtin_name_taken(&name) {
            return Err(EqError::NameTaken);
        }
        let curve = PresetCurve::from_inner(self);
        let norm = presets::normalize(&name);
        let stored = match self.presets.iter().position(|p| presets::normalize(&p.name) == norm) {
            Some(i) => {
                self.presets[i].curve = curve;
                self.presets[i].name.clone()
            }
            None => {
                if self.presets.len() >= MAX_USER_PRESETS {
                    return Err(EqError::TooManyPresets);
                }
                self.presets.push(Preset { name: name.clone(), curve });
                name
            }
        };
        let key = presets::user_key(&stored);
        self.active_preset = Some(key.clone());
        Ok(key)
    }

    fn user_index(&self, key: &str) -> Result<usize, EqError> {
        if key.starts_with("builtin:") {
            return Err(EqError::ReadOnly);
        }
        let name = key.strip_prefix("user:").ok_or(EqError::NotFound)?;
        self.presets.iter().position(|p| p.name == name).ok_or(EqError::NotFound)
    }

    /// Renames a user preset; the active marker follows it. Returns the new key.
    pub fn rename_preset(&mut self, key: &str, new_name: &str) -> Result<String, EqError> {
        let i = self.user_index(key)?;
        let name = presets::validate_name(new_name)?;
        let norm = presets::normalize(&name);
        let clash = Self::builtin_name_taken(&name)
            || self.presets.iter().enumerate().any(|(j, p)| j != i && presets::normalize(&p.name) == norm);
        if clash {
            return Err(EqError::NameTaken);
        }
        self.presets[i].name = name.clone();
        let new_key = presets::user_key(&name);
        if self.active_preset.as_deref() == Some(key) {
            self.active_preset = Some(new_key.clone());
        }
        Ok(new_key)
    }

    /// Deletes a user preset. If it was active the marker clears; the sound is unchanged.
    pub fn delete_preset(&mut self, key: &str) -> Result<(), EqError> {
        let i = self.user_index(key)?;
        self.presets.remove(i);
        if self.active_preset.as_deref() == Some(key) {
            self.active_preset = None;
        }
        Ok(())
    }

    /// True when the active preset resolves and the live state differs from it.
    pub fn is_modified(&self) -> bool {
        match self.active_preset.as_deref().and_then(|k| self.find_preset(k)) {
            Some(p) => !presets::curves_equal(&PresetCurve::from_inner(self), &p.curve),
            None => false,
        }
    }

    pub fn graphic_centres(&self) -> Vec<f32> {
        graphic::centres(self.graphic.band_count).map(|c| c.to_vec()).unwrap_or_default()
    }
}

/// Snaps near-centre balance to exactly 0 and clamps to -1..1.
pub(crate) fn snap_balance(v: f32) -> f32 {
    let c = v.clamp(-1.0, 1.0);
    if c.abs() <= BALANCE_SNAP { 0.0 } else { c }
}

/// Attenuation (dB, <= 0) of the quieter channel: 20*log10(1 - |b|), floored at -60 dB (relative to the louder side).
fn balance_attenuation_db(b: f32) -> f32 {
    if b == 0.0 {
        return 0.0;
    }
    let rem = 1.0 - (b.abs() as f64);
    if rem <= 0.0 {
        return MAX_BALANCE_ATTENUATION_DB;
    }
    ((20.0 * rem.log10()) as f32).max(MAX_BALANCE_ATTENUATION_DB)
}

/// Frequency of point `i` of `n` on the shared 20 Hz..20 kHz log grid.
fn log_grid_hz(i: usize, n: usize) -> f64 {
    if i + 1 == n { 20_000.0 } else { 20.0 * 1000f64.powf(i as f64 / (n - 1) as f64) }
}

fn finite_or_zero(v: f64) -> f64 {
    if v.is_finite() { v } else { 0.0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peak_band(freq: f32, gain: f32) -> Band {
        let mut b = Band::new(FilterKind::Peak, freq);
        b.gain_db = gain;
        b.q = 1.0;
        b
    }

    #[test]
    fn seventeenth_band_is_an_error() {
        let mut s = Inner::default();
        while s.parametric.len() < MAX_BANDS {
            s.add_band().unwrap();
        }
        assert_eq!(s.add_band(), Err(EqError::TooManyBands));
        assert_eq!(s.parametric.len(), MAX_BANDS);
    }

    #[test]
    fn bad_index_and_non_finite_are_rejected_without_change() {
        let mut s = Inner::default();
        let before = s.edit_snapshot();
        assert_eq!(s.set_band(99, peak_band(1000.0, 1.0)), Err(EqError::OutOfRange));
        assert_eq!(s.set_band(0, peak_band(f32::NAN, 1.0)), Err(EqError::InvalidValue));
        assert_eq!(s.set_band(0, peak_band(1000.0, f32::INFINITY)), Err(EqError::InvalidValue));
        assert_eq!(s.remove_band(99), Err(EqError::OutOfRange));
        assert_eq!(s.set_graphic_gain(99, 1.0), Err(EqError::OutOfRange));
        assert_eq!(s.set_graphic_gain(0, f32::NAN), Err(EqError::InvalidValue));
        assert_eq!(s.set_graphic_band_count(7), Err(EqError::InvalidValue));
        assert_eq!(s.set_preamp(f32::NAN), Err(EqError::InvalidValue));
        assert_eq!(s.set_frame_duration_ms(1.0), Err(EqError::InvalidValue));
        assert_eq!(s.set_frame_duration_ms(f32::NAN), Err(EqError::InvalidValue));
        assert_eq!(s.edit_snapshot(), before);
        assert!(!s.can_undo());
    }

    #[test]
    fn band_response_peak_reaches_its_gain_at_centre() {
        let s = Inner { parametric: vec![peak_band(1000.0, 6.0)], ..Inner::default() };
        let r = s.band_response(0, 256, 48_000).unwrap();
        assert_eq!(r.len(), 256);
        assert!(r.iter().all(|v| v.is_finite()));
        let (imax, vmax) = r.iter().enumerate().fold((0, f32::MIN), |a, (i, &v)| if v > a.1 { (i, v) } else { a });
        assert!((vmax - 6.0).abs() < 0.01, "{vmax}"); // grid need not land exactly on 1 kHz
        let centre = fit::band_target(s.parametric[0], 48_000.0)(1000.0);
        assert!((centre - 6.0).abs() < 1e-3, "{centre}");
        let f = log_grid_hz(imax, 256);
        assert!((f / 1000.0).ln().abs() < 0.03, "{f}");
        let c = crate::dsp::biquad::Coeffs::for_band(&s.parametric[0], 48_000.0);
        assert!((c.magnitude_db(f, 48_000.0) as f32 - r[imax]).abs() < 1e-4);
    }

    #[test]
    fn band_response_lowpass_ignores_gain() {
        let mut b = Band::new(FilterKind::LowPass, 1000.0);
        b.q = 0.707;
        b.gain_db = 0.0;
        let mut b2 = b;
        b2.gain_db = 12.0;
        let a = Inner { parametric: vec![b], ..Inner::default() };
        let c = Inner { parametric: vec![b2], ..Inner::default() };
        let ra = a.band_response(0, 128, 48_000).unwrap();
        assert!(*ra.last().unwrap() < -3.0);
        assert_eq!(ra, c.band_response(0, 128, 48_000).unwrap());
    }

    #[test]
    fn band_response_ignores_enabled_flag() {
        let on = peak_band(2000.0, -5.0);
        let mut off = on;
        off.enabled = false;
        let a = Inner { parametric: vec![on], ..Inner::default() };
        let b = Inner { parametric: vec![off], ..Inner::default() };
        assert_eq!(a.band_response(0, 64, 48_000).unwrap(), b.band_response(0, 64, 48_000).unwrap());
    }

    #[test]
    fn band_response_rejects_bad_arguments() {
        let s = Inner::default();
        let n = s.parametric.len();
        assert_eq!(s.band_response(n, 64, 48_000), Err(EqError::OutOfRange));
        assert_eq!(s.band_response(0, 1, 48_000), Err(EqError::InvalidValue));
        assert_eq!(s.band_response(0, 2049, 48_000), Err(EqError::InvalidValue));
        assert_eq!(s.band_response(0, 64, 0), Err(EqError::InvalidValue));
        assert_eq!(s.band_response(0, 64, 1_000_000), Err(EqError::InvalidValue));
    }

    #[test]
    fn band_response_grid_matches_response_curve() {
        let s = Inner::default();
        let curve = s.response_curve(100, 48_000, DEFAULT_FRAME_MS).unwrap();
        assert_eq!(curve.freqs_hz[0], 20.0);
        assert_eq!(*curve.freqs_hz.last().unwrap(), 20_000.0);
        for (i, &f) in curve.freqs_hz.iter().enumerate() {
            assert_eq!(f, log_grid_hz(i, 100) as f32);
        }
    }

    #[test]
    fn band_response_is_read_only() {
        let mut s = Inner::default();
        s.set_band(0, peak_band(500.0, 3.0)).unwrap();
        let before = s.edit_snapshot();
        let (u, r) = (s.can_undo(), s.can_redo());
        s.band_response(0, 64, 48_000).unwrap();
        assert_eq!(s.edit_snapshot(), before);
        assert_eq!((s.can_undo(), s.can_redo()), (u, r));
    }

    #[test]
    fn reset_bands_restores_defaults_as_one_step() {
        let mut s = Inner::default();
        s.set_band(0, peak_band(500.0, 3.0)).unwrap();
        s.add_band().unwrap();
        s.set_preamp(-6.0).unwrap();
        let edited = s.edit_snapshot();
        s.reset_bands();
        assert_eq!(s.parametric, EqState::default().bands);
        assert_eq!(s.preamp_db, 0.0);
        assert!(s.undo());
        assert_eq!(s.edit_snapshot(), edited);
    }

    #[test]
    fn reset_bands_leaves_other_state_alone() {
        let mut s = Inner::default();
        s.mode = EqMode::Parametric;
        s.bypass = true;
        s.limiter_enabled = false;
        s.set_frame_duration_ms(50.0).unwrap();
        s.set_graphic_gain(0, 4.0).unwrap();
        s.set_preamp(-3.0).unwrap();
        let g = s.graphic.clone();
        s.reset_bands();
        assert_eq!(s.mode, EqMode::Parametric);
        assert!(s.bypass);
        assert!(!s.limiter_enabled);
        assert_eq!(s.frame_duration_ms, 50.0);
        assert_eq!(s.graphic, g);
    }

    #[test]
    fn reset_bands_on_defaults_records_nothing() {
        let mut s = Inner::default();
        s.reset_bands();
        assert!(!s.can_undo());
        s.set_band(0, peak_band(500.0, 3.0)).unwrap();
        assert!(s.undo());
        assert!(s.can_redo());
        s.reset_bands();
        assert!(s.can_redo());
        assert!(!s.can_undo());
    }

    #[test]
    fn set_band_clamps() {
        let mut s = Inner::default();
        s.set_band(0, peak_band(1e9, 500.0)).unwrap();
        let b = s.parametric[0];
        assert_eq!((b.freq, b.gain_db), (20_000.0, 24.0));
    }

    #[test]
    fn a_drag_is_one_undo_step() {
        let mut s = Inner::default();
        s.begin_edit();
        for i in 1..=20 {
            s.set_graphic_gain(2, i as f32 * 0.5).unwrap();
        }
        s.end_edit();
        assert_eq!(s.graphic.gains_db[2], 10.0);
        assert!(s.undo());
        assert_eq!(s.graphic.gains_db[2], 0.0);
        assert!(!s.undo());
        assert!(s.redo());
        assert_eq!(s.graphic.gains_db[2], 10.0);
    }

    #[test]
    fn band_count_change_is_one_step() {
        let mut s = Inner::default();
        s.set_graphic_gain(3, 6.0).unwrap();
        let gains = s.graphic.gains_db.clone();
        s.set_graphic_band_count(31).unwrap();
        assert_eq!(s.graphic.gains_db.len(), 31);
        assert!(s.undo());
        assert_eq!((s.graphic.band_count, s.graphic.gains_db.clone()), (10, gains));
    }

    #[test]
    fn mode_bypass_limiter_and_frame_are_not_undoable() {
        let mut s = Inner::default();
        s.mode = EqMode::Parametric;
        s.bypass = true;
        s.limiter_enabled = false;
        s.set_frame_duration_ms(170.6).unwrap();
        assert!(!s.can_undo());
        s.set_preamp(3.0).unwrap();
        assert!(s.undo());
        assert_eq!(s.preamp_db, 0.0);
        assert_eq!(s.mode, EqMode::Parametric);
        assert!(s.bypass && !s.limiter_enabled);
        assert_eq!(s.frame_duration_ms, 170.6);
    }

    #[test]
    fn lone_peak_ideal_is_six_db_and_flat_is_zero() {
        let mut s = Inner::default();
        s.mode = EqMode::Parametric;
        for b in s.parametric.iter_mut() {
            b.enabled = false;
        }
        let flat = s.response_curve(256, 48000, 85.3).unwrap();
        assert!(flat.ideal_db.iter().chain(&flat.achieved_db).all(|&v| v == 0.0));
        s.set_band(0, peak_band(1000.0, 6.0)).unwrap();
        s.set_band(0, { let mut b = peak_band(1000.0, 6.0); b.enabled = true; b }).unwrap();
        let c = s.response_curve(2048, 48000, 85.3).unwrap();
        let max = c.ideal_db.iter().cloned().fold(f32::MIN, f32::max);
        assert!((max - 6.0).abs() < 1e-3, "{max}");
    }
}
