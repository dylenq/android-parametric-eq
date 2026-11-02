//! The only UniFFI surface: one `EqCore` object over a mutex-guarded [`Inner`].
//! Every method is a thin wrapper; none panics. A poisoned lock is recovered.

use std::sync::{Arc, Mutex, MutexGuard};

use crate::error::EqError;
use crate::headroom::LimiterParams;
use crate::inner::{DEFAULT_FRAME_MS, Engine, Inner};
use crate::model::{Band, EqMode};
use crate::persist;
use crate::presets;

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct Snapshot {
    pub mode: EqMode,
    pub bands: Vec<Band>,
    pub graphic_band_count: u32,
    pub graphic_gains_db: Vec<f32>,
    pub graphic_centres_hz: Vec<f32>,
    pub preamp_db: f32,
    pub bass_db: f32,
    pub treble_db: f32,
    pub balance: f32,
    pub limiter_enabled: bool,
    pub bypass: bool,
    pub frame_duration_ms: f32,
    pub can_undo: bool,
    pub can_redo: bool,
    /// Built-ins first, then user presets in creation order.
    pub presets: Vec<PresetInfo>,
    pub active_preset_key: Option<String>,
    pub active_preset_name: Option<String>,
    /// The live sound differs from the active preset (computed, never stored).
    pub preset_modified: bool,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PresetInfo {
    pub key: String,
    pub name: String,
    pub builtin: bool,
}

/// One per-app rule. `preset_key` of `None` means the live EQ.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AppRuleInfo {
    pub pkg: String,
    pub enabled: bool,
    pub preset_key: Option<String>,
    pub last_seen: u64,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct CurveData {
    pub freqs_hz: Vec<f32>,
    pub ideal_db: Vec<f32>,
    pub achieved_db: Vec<f32>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct EngineParams {
    pub sample_rate: u32,
    pub frame_duration_ms: f32,
    pub block_size: u32,
    pub cutoffs_hz: Vec<f32>,
    pub gains_db: Vec<f32>,
    pub input_gain_db: f32,
    pub input_gain_left_db: f32,
    pub input_gain_right_db: f32,
    /// Verified on the emulator, see `headroom::LIMITER_VERIFIED`.
    pub limiter: LimiterParams,
    pub limiter_enabled: bool,
    pub bypass: bool,
    pub peak_db: f32,
    pub suggested_preamp_db: f32,
    pub headroom_after_preamp_db: f32,
    pub layout_id: u32,
}

#[derive(uniffi::Object)]
pub struct EqCore {
    inner: Mutex<Inner>,
}

impl EqCore {
    fn wrap(inner: Inner) -> Arc<Self> {
        Arc::new(EqCore { inner: Mutex::new(inner) })
    }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }
}

#[uniffi::export]
impl EqCore {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Self::wrap(Inner::default())
    }

    #[uniffi::constructor]
    pub fn load(path: String) -> Result<Arc<Self>, EqError> {
        Ok(Self::wrap(persist::load(&path)?))
    }

    #[uniffi::constructor]
    pub fn load_or_default(path: String) -> Arc<Self> {
        Self::wrap(persist::load_or_default(&path))
    }

    pub fn version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_string()
    }

    pub fn save(&self, path: String) -> Result<(), EqError> {
        persist::save(&path, &self.lock())
    }

    /// How many saved entries the load dropped or reset to a default (0 for a clean file and
    /// for a new core). Not saved and not part of the snapshot.
    pub fn load_repairs(&self) -> u32 {
        self.lock().load_repairs
    }

    pub fn snapshot(&self) -> Snapshot {
        let s = self.lock();
        let mut list: Vec<PresetInfo> = presets::builtin_presets()
            .into_iter()
            .map(|p| PresetInfo { key: presets::builtin_key(&p.name), name: p.name, builtin: true })
            .collect();
        list.extend(
            s.presets
                .iter()
                .map(|p| PresetInfo { key: presets::user_key(&p.name), name: p.name.clone(), builtin: false }),
        );
        let active = s.active_preset.as_deref().and_then(|k| s.find_preset(k));
        Snapshot {
            mode: s.mode,
            bands: s.parametric.clone(),
            graphic_band_count: s.graphic.band_count,
            graphic_gains_db: s.graphic.gains_db.clone(),
            graphic_centres_hz: s.graphic_centres(),
            preamp_db: s.preamp_db,
            bass_db: s.bass_db,
            treble_db: s.treble_db,
            balance: s.balance,
            limiter_enabled: s.limiter_enabled,
            bypass: s.bypass,
            frame_duration_ms: s.frame_duration_ms,
            can_undo: s.can_undo(),
            can_redo: s.can_redo(),
            presets: list,
            active_preset_key: active.as_ref().and_then(|_| s.active_preset.clone()),
            active_preset_name: active.map(|p| p.name),
            preset_modified: s.is_modified(),
        }
    }

    pub fn set_mode(&self, mode: EqMode) {
        self.lock().mode = mode;
    }

    pub fn set_band(&self, index: u32, band: Band) -> Result<(), EqError> {
        self.lock().set_band(index as usize, band)
    }

    pub fn add_band(&self) -> Result<u32, EqError> {
        self.lock().add_band().map(|i| i as u32)
    }

    pub fn remove_band(&self, index: u32) -> Result<(), EqError> {
        self.lock().remove_band(index as usize)
    }

    pub fn set_graphic_band_count(&self, count: u32) -> Result<(), EqError> {
        self.lock().set_graphic_band_count(count)
    }

    pub fn set_graphic_gain(&self, index: u32, gain_db: f32) -> Result<(), EqError> {
        self.lock().set_graphic_gain(index as usize, gain_db)
    }

    pub fn set_preamp(&self, db: f32) -> Result<(), EqError> {
        self.lock().set_preamp(db)
    }

    pub fn set_bass(&self, db: f32) -> Result<(), EqError> {
        self.lock().set_bass(db)
    }

    pub fn set_treble(&self, db: f32) -> Result<(), EqError> {
        self.lock().set_treble(db)
    }

    pub fn set_balance(&self, balance: f32) -> Result<(), EqError> {
        self.lock().set_balance(balance)
    }

    pub fn set_limiter_enabled(&self, on: bool) {
        self.lock().limiter_enabled = on;
    }

    pub fn set_bypass(&self, on: bool) {
        self.lock().bypass = on;
    }

    pub fn set_frame_duration_ms(&self, ms: f32) -> Result<(), EqError> {
        self.lock().set_frame_duration_ms(ms)
    }

    /// Limits the engine grid to at most `max_bands` bands (raised to 8). Device state: not
    /// saved, not undoable, does not mark the state dirty. `None` clears it.
    pub fn set_engine_band_cap(&self, max_bands: Option<u32>) {
        self.lock().engine_band_cap = max_bands.map(|v| v as usize);
    }

    /// Applies a preset as one undo step. Returns whether a step was recorded.
    pub fn apply_preset(&self, key: String) -> Result<bool, EqError> {
        self.lock().apply_preset(&key)
    }

    pub fn save_preset(&self, name: String) -> Result<String, EqError> {
        self.lock().save_preset(&name)
    }

    pub fn rename_preset(&self, key: String, new_name: String) -> Result<String, EqError> {
        self.lock().rename_preset(&key, &new_name)
    }

    pub fn delete_preset(&self, key: String) -> Result<(), EqError> {
        self.lock().delete_preset(&key)
    }

    /// Records that an app has a session. True when a rule was added. Not an undo step.
    pub fn note_app(&self, pkg: String) -> bool {
        self.lock().note_app(&pkg)
    }

    pub fn set_app_enabled(&self, pkg: String, on: bool) {
        self.lock().set_app_enabled(&pkg, on);
    }

    /// `None` returns the app to the live EQ; a key that does not resolve is `NotFound`.
    pub fn set_app_preset(&self, pkg: String, key: Option<String>) -> Result<(), EqError> {
        self.lock().set_app_preset(&pkg, key.as_deref())
    }

    pub fn app_rules(&self) -> Vec<AppRuleInfo> {
        self.lock()
            .app_rules()
            .iter()
            .map(|r| AppRuleInfo {
                pkg: r.pkg.clone(),
                enabled: r.enabled,
                preset_key: r.preset_key.clone(),
                last_seen: r.last_seen,
            })
            .collect()
    }

    pub fn begin_edit(&self) {
        self.lock().begin_edit();
    }

    pub fn end_edit(&self) {
        self.lock().end_edit();
    }

    pub fn undo(&self) -> bool {
        self.lock().undo()
    }

    pub fn redo(&self) -> bool {
        self.lock().redo()
    }

    pub fn response_curve(
        &self,
        points: u32,
        sample_rate: u32,
        frame_duration_ms: f32,
    ) -> Result<CurveData, EqError> {
        let c = self.lock().response_curve(points, sample_rate, frame_duration_ms)?;
        Ok(CurveData { freqs_hz: c.freqs_hz, ideal_db: c.ideal_db, achieved_db: c.achieved_db })
    }

    pub fn band_response_db(&self, index: u32, points: u32, sample_rate: u32) -> Result<Vec<f32>, EqError> {
        self.lock().band_response(index as usize, points, sample_rate)
    }

    pub fn reset_bands(&self) {
        self.lock().reset_bands()
    }

    pub fn engine_params(
        &self,
        sample_rate: u32,
        frame_duration_ms: f32,
    ) -> Result<EngineParams, EqError> {
        self.lock().engine_params(sample_rate, frame_duration_ms).map(to_params)
    }

    /// The params `apply_preset(key)` would give, without applying it: live state, the active
    /// preset and undo history stay as they are. Uses the live limiter flag, bypass and band cap.
    pub fn engine_params_for_preset(
        &self,
        key: String,
        sample_rate: u32,
        frame_duration_ms: f32,
    ) -> Result<EngineParams, EqError> {
        self.lock()
            .engine_params_for_preset(&key, sample_rate, frame_duration_ms)
            .map(to_params)
    }
}

fn to_params(e: Engine) -> EngineParams {
    EngineParams {
        sample_rate: e.sample_rate,
        frame_duration_ms: e.frame_duration_ms,
        block_size: e.block_size,
        cutoffs_hz: e.cutoffs_hz,
        gains_db: e.gains_db,
        input_gain_db: e.input_gain_db,
        input_gain_left_db: e.input_gain_left_db,
        input_gain_right_db: e.input_gain_right_db,
        limiter: e.limiter,
        limiter_enabled: e.limiter_enabled,
        bypass: e.bypass,
        peak_db: e.peak_db,
        suggested_preamp_db: e.suggested_preamp_db,
        headroom_after_preamp_db: e.headroom_after_preamp_db,
        layout_id: e.layout_id,
    }
}

/// Default frame duration, for callers that have no preference stored.
pub const DEFAULT_FRAME_DURATION_MS: f32 = DEFAULT_FRAME_MS;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_is_semver_like() {
        let v = EqCore::new().version();
        assert!(v.chars().next().is_some_and(|c| c.is_ascii_digit()));
    }
}
