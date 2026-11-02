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

/// Which AutoEq format a file was.
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ImportKind {
    Parametric,
    Graphic,
}

/// What an import made. The preset is stored, not applied.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ImportReport {
    pub key: String,
    pub name: String,
    pub kind: ImportKind,
    /// Parametric filters kept, or graphic bands made.
    pub filters: u32,
    /// Filters or curve points the file had that could not be used.
    pub skipped: u32,
    /// Graphic bands whose value was cut back to the slider range.
    pub clamped: u32,
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

/// One per-device rule. `preset_key` of `None` keeps the current sound.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct DeviceRuleInfo {
    pub key: String,
    pub name: String,
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

    /// Turns the text of a ParametricEQ.txt or GraphicEQ.txt into a user preset, named after
    /// the file. Does not apply it. Nothing changes on error.
    pub fn import_eq_text(
        &self,
        file_name: String,
        text: String,
        graphic_band_count: u32,
    ) -> Result<ImportReport, EqError> {
        todo!()
    }

    /// The live parametric bands and preamp as ParametricEQ.txt text, in any mode.
    pub fn export_parametric_text(&self) -> String {
        todo!()
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

    /// Records that an output device is in use. True when a rule was added. Not an undo step.
    pub fn note_device(&self, key: String, name: String) -> bool {
        self.lock().note_device(&key, &name)
    }

    /// `None` keeps the current sound; a key that does not resolve is `NotFound`.
    pub fn set_device_preset(&self, key: String, preset_key: Option<String>) -> Result<(), EqError> {
        self.lock().set_device_preset(&key, preset_key.as_deref())
    }

    pub fn device_rules(&self) -> Vec<DeviceRuleInfo> {
        self.lock()
            .device_rules()
            .iter()
            .map(|r| DeviceRuleInfo {
                key: r.key.clone(),
                name: r.name.clone(),
                preset_key: r.preset_key.clone(),
                last_seen: r.last_seen,
            })
            .collect()
    }

    /// Records the output device now in use. Returns the preset key to apply, only when the device
    /// differs from the last one recorded and has a preset. Applying it is up to the caller.
    pub fn record_device(&self, key: String, name: String) -> Option<String> {
        self.lock().record_device(&key, &name)
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
    fn device_rules_work_through_the_core() {
        let c = EqCore::new();
        assert!(c.note_device("speaker".into(), "Phone speaker".into()));
        assert!(!c.note_device("speaker".into(), "Phone speaker".into()));
        assert_eq!(
            c.set_device_preset("usb:DAC".into(), Some("user:Nope".into())),
            Err(EqError::NotFound)
        );
        c.set_device_preset("usb:DAC".into(), Some("builtin:Bass".into())).unwrap();
        assert_eq!(c.record_device("speaker".into(), "Phone speaker".into()), None);
        assert_eq!(
            c.record_device("usb:DAC".into(), "USB: DAC".into()).as_deref(),
            Some("builtin:Bass")
        );
        assert_eq!(c.record_device("usb:DAC".into(), "USB: DAC".into()), None);
        let rules = c.device_rules();
        assert_eq!(rules.len(), 2);
        let usb = rules.iter().find(|r| r.key == "usb:DAC").unwrap();
        assert_eq!(usb.name, "USB: DAC");
        assert_eq!(usb.preset_key.as_deref(), Some("builtin:Bass"));
    }

    #[test]
    fn version_is_semver_like() {
        let v = EqCore::new().version();
        assert!(v.chars().next().is_some_and(|c| c.is_ascii_digit()));
    }

    const AUTOEQ: &str = "Preamp: -6.2 dB\n\
        Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70\n\
        Filter 2: ON PK Fc 180 Hz Gain -2.3 dB Q 1.20\n";

    #[test]
    fn import_stores_a_preset_without_applying_it() {
        let c = EqCore::new();
        c.set_preamp(-1.0).unwrap();
        let before = c.snapshot();
        let r = c.import_eq_text("HD 650 ParametricEQ.txt".into(), AUTOEQ.into(), 10).unwrap();
        assert_eq!((r.name.as_str(), r.key.as_str()), ("HD 650", "user:HD 650"));
        assert_eq!((r.kind, r.filters, r.skipped, r.clamped), (ImportKind::Parametric, 2, 0, 0));
        let after = c.snapshot();
        assert_eq!(after.presets.len(), before.presets.len() + 1);
        assert_eq!(after.preamp_db, before.preamp_db);
        assert_eq!(after.bands, before.bands);
        assert_eq!(after.active_preset_key, before.active_preset_key);
        assert_eq!(after.can_undo, before.can_undo);
        // Applying it gives the file's sound: parametric, file preamp, zero tone, centred.
        c.apply_preset(r.key).unwrap();
        let s = c.snapshot();
        assert_eq!(s.mode, EqMode::Parametric);
        assert_eq!((s.bands.len(), s.preamp_db, s.bass_db, s.treble_db, s.balance), (2, -6.2, 0.0, 0.0, 0.0));
        assert_eq!(s.graphic_band_count, 10);
        assert_eq!(s.graphic_gains_db, vec![0.0; 10]);
    }

    #[test]
    fn a_graphic_import_makes_a_graphic_preset() {
        let c = EqCore::new();
        let r = c
            .import_eq_text("Buds GraphicEQ.txt".into(), "Preamp: -2 dB\nGraphicEQ: 20 3; 20000 3\n".into(), 15)
            .unwrap();
        assert_eq!((r.kind, r.filters, r.name.as_str()), (ImportKind::Graphic, 15, "Buds"));
        c.apply_preset(r.key).unwrap();
        let s = c.snapshot();
        assert_eq!((s.mode, s.graphic_band_count, s.preamp_db), (EqMode::Graphic, 15, -2.0));
        assert!(s.graphic_gains_db.iter().all(|g| (g - 3.0).abs() < 1e-4));
        assert_eq!(s.bands, crate::model::EqState::default().bands);
    }

    #[test]
    fn clashing_imports_get_numbers() {
        let c = EqCore::new();
        let a = c.import_eq_text("Flat.txt".into(), AUTOEQ.into(), 10).unwrap();
        let b = c.import_eq_text("Flat.txt".into(), AUTOEQ.into(), 10).unwrap();
        let d = c.import_eq_text("Flat.txt".into(), AUTOEQ.into(), 10).unwrap();
        assert_eq!((a.name.as_str(), b.name.as_str(), d.name.as_str()), ("Flat 2", "Flat 3", "Flat 4"));
    }

    #[test]
    fn failed_imports_change_nothing() {
        let c = EqCore::new();
        c.import_eq_text("One.txt".into(), AUTOEQ.into(), 10).unwrap();
        c.apply_preset("user:One".into()).unwrap();
        let before = c.snapshot();
        let big = format!("{AUTOEQ}{}", "# padding\n".repeat(120_000));
        for (text, count, want) in [
            ("Dear diary: hi", 10, EqError::NotAnEqFile),
            ("Preamp: -3 dB\n", 10, EqError::NotAnEqFile),
            (big.as_str(), 10, EqError::FileTooLarge),
            (AUTOEQ, 7, EqError::InvalidValue),
        ] {
            assert_eq!(c.import_eq_text("x.txt".into(), text.into(), count), Err(want));
            assert_eq!(c.snapshot(), before);
        }
    }

    #[test]
    fn a_full_preset_list_refuses_the_import_untouched() {
        let c = EqCore::new();
        for i in 0..100 {
            c.save_preset(format!("P{i}")).unwrap();
        }
        let before = c.snapshot();
        assert_eq!(
            c.import_eq_text("New.txt".into(), AUTOEQ.into(), 10),
            Err(EqError::TooManyPresets)
        );
        assert_eq!(c.snapshot(), before);
    }

    #[test]
    fn export_then_import_returns_the_live_bands() {
        let c = EqCore::new();
        c.set_preamp(-4.25).unwrap();
        let text = c.export_parametric_text();
        assert!(text.starts_with("Preamp: -4.25 dB\nFilter 1: ON LSC Fc 80 Hz"));
        let r = c.import_eq_text("Mine.txt".into(), text, 10).unwrap();
        c.apply_preset(r.key).unwrap();
        let s = c.snapshot();
        assert_eq!(s.bands, crate::model::EqState::default().bands);
        assert_eq!(s.preamp_db, -4.25);
        // Exporting works in graphic mode too and does not mark the state.
        c.set_mode(EqMode::Graphic);
        let before = c.snapshot();
        assert!(c.export_parametric_text().contains("Filter 1:"));
        assert_eq!(c.snapshot(), before);
    }
}
