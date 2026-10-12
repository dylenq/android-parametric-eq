//! Plain-Rust state machine behind the FFI object. Every edit is validated before it mutates
//! anything and is recorded in [`History`]; nothing here panics on caller input.

use crate::achieved::BinGains;
use crate::error::EqError;
use crate::fit;
use crate::graphic;
use crate::grid::{self, Grid};
use crate::headroom::{self, LimiterParams};
use crate::history::{EditSnapshot, History};
use crate::model::{Band, EqMode, EqState, FilterKind, GraphicState, MAX_BANDS, MAX_GAIN_DB};

pub const DEFAULT_FRAME_MS: f32 = 85.3;
pub const MIN_FRAME_MS: f32 = 5.0;
pub const MAX_FRAME_MS: f32 = 300.0;
pub const MIN_CURVE_POINTS: u32 = 2;
pub const MAX_CURVE_POINTS: u32 = 2048;

pub struct Inner {
    pub mode: EqMode,
    pub parametric: Vec<Band>,
    pub graphic: GraphicState,
    pub preamp_db: f32,
    pub limiter_enabled: bool,
    pub bypass: bool,
    pub frame_duration_ms: f32,
    pub(crate) history: History,
}

impl Default for Inner {
    fn default() -> Self {
        Inner {
            mode: EqMode::Graphic,
            parametric: EqState::default().bands,
            graphic: GraphicState::default(),
            preamp_db: 0.0,
            limiter_enabled: true,
            bypass: false,
            frame_duration_ms: DEFAULT_FRAME_MS,
            history: History::default(),
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
        }
    }

    fn apply(&mut self, s: EditSnapshot) {
        self.parametric = s.bands;
        self.graphic = GraphicState { band_count: s.band_count, gains_db: s.gains_db };
        self.preamp_db = s.preamp_db;
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
        let cur = self.edit_snapshot();
        match self.history.undo(&cur) {
            Some(s) => {
                self.apply(s);
                true
            }
            None => false,
        }
    }

    pub fn redo(&mut self) -> bool {
        let cur = self.edit_snapshot();
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
    fn target<'a>(&'a self, sample_rate: f64) -> Box<dyn Fn(f64) -> f64 + 'a> {
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

    fn grid_for(sample_rate: u32, frame_ms: f32) -> Result<Grid, EqError> {
        Grid::hybrid(sample_rate, frame_ms).map_err(|_| EqError::InvalidValue)
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
        let grid = Self::grid_for(sample_rate, frame_ms)?;
        let sr = sample_rate as f64;
        let target = self.target(sr);
        let gains = fit::fit(&grid, &target);
        let bins = BinGains::new(&grid, &gains);

        let n = points as usize;
        let mut freqs = Vec::with_capacity(n);
        let (mut ideal, mut achieved) = (Vec::with_capacity(n), Vec::with_capacity(n));
        for i in 0..n {
            let t = i as f64 / (n - 1) as f64;
            let f = if i == n - 1 { 20_000.0 } else { 20.0 * 1000f64.powf(t) };
            freqs.push(f as f32);
            ideal.push(finite_or_zero(target(f)) as f32);
            achieved.push(finite_or_zero(bins.at(f)) as f32);
        }
        Ok(Curve { freqs_hz: freqs, ideal_db: ideal, achieved_db: achieved })
    }

    pub fn engine_params(&self, sample_rate: u32, frame_ms: f32) -> Result<Engine, EqError> {
        let grid = Self::grid_for(sample_rate, frame_ms)?;
        let target = self.target(sample_rate as f64);
        let gains = fit::fit(&grid, &target);
        let peak = headroom::peak_db(&grid, &gains);
        let preamp = self.preamp_db.clamp(-(MAX_GAIN_DB), MAX_GAIN_DB);
        Ok(Engine {
            sample_rate,
            frame_duration_ms: frame_ms,
            block_size: grid.block,
            cutoffs_hz: grid.cutoffs_hz.clone(),
            gains_db: gains.iter().map(|&g| g as f32).collect(),
            input_gain_db: preamp,
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

    pub fn graphic_centres(&self) -> Vec<f32> {
        graphic::centres(self.graphic.band_count).map(|c| c.to_vec()).unwrap_or_default()
    }
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
