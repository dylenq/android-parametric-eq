//! Versioned JSON persistence. The caller supplies the full path; the core builds none.

use std::io::Read;

use serde::{Deserialize, Serialize};

use crate::error::EqError;
use crate::inner::{DEFAULT_FRAME_MS, Inner, MAX_FRAME_MS, MIN_FRAME_MS, TONE_MAX_DB};
use crate::presets::{self, Preset};
use crate::model::{
    Band, EqMode, EqState, GRAPHIC_COUNTS, GRAPHIC_MAX_DB, GraphicState, MAX_BANDS, MAX_GAIN_DB,
};

pub const SCHEMA_VERSION: u32 = 1;
pub const MAX_FILE_BYTES: u64 = 1024 * 1024;

fn default_bands() -> Vec<Band> {
    EqState::default().bands
}
fn yes() -> bool {
    true
}
fn default_frame() -> f32 {
    DEFAULT_FRAME_MS
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PersistedParametric {
    #[serde(default = "default_bands")]
    pub bands: Vec<Band>,
}

impl Default for PersistedParametric {
    fn default() -> Self {
        PersistedParametric { bands: default_bands() }
    }
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PersistedState {
    pub version: u32,
    #[serde(default)]
    pub mode: EqMode,
    #[serde(default)]
    pub parametric: PersistedParametric,
    #[serde(default)]
    pub graphic: GraphicState,
    #[serde(default)]
    pub preamp_db: f32,
    #[serde(default)]
    pub bass_db: f32,
    #[serde(default)]
    pub treble_db: f32,
    #[serde(default)]
    pub balance: f32,
    #[serde(default = "yes")]
    pub limiter_enabled: bool,
    #[serde(default)]
    pub bypass: bool,
    #[serde(default = "default_frame")]
    pub frame_duration_ms: f32,
    #[serde(default)]
    pub presets: Vec<Preset>,
    #[serde(default)]
    pub active_preset: Option<String>,
}

impl PersistedState {
    pub fn from_inner(s: &Inner) -> Self {
        PersistedState {
            version: SCHEMA_VERSION,
            mode: s.mode,
            parametric: PersistedParametric { bands: s.parametric.clone() },
            graphic: s.graphic.clone(),
            preamp_db: s.preamp_db,
            bass_db: s.bass_db,
            treble_db: s.treble_db,
            balance: s.balance,
            limiter_enabled: s.limiter_enabled,
            bypass: s.bypass,
            frame_duration_ms: s.frame_duration_ms,
            presets: s.presets.clone(),
            active_preset: s.active_preset.clone(),
        }
    }

    pub fn into_inner(self) -> Inner {
        Inner {
            mode: self.mode,
            parametric: self.parametric.bands,
            graphic: self.graphic,
            preamp_db: self.preamp_db,
            bass_db: self.bass_db,
            treble_db: self.treble_db,
            balance: self.balance,
            limiter_enabled: self.limiter_enabled,
            bypass: self.bypass,
            frame_duration_ms: self.frame_duration_ms,
            presets: self.presets,
            active_preset: self.active_preset,
            ..Inner::default()
        }
    }
}

/// Clamps and validates a parsed state. Anything that cannot be repaired is `Corrupt`.
pub fn sanitize(mut p: PersistedState) -> Result<PersistedState, EqError> {
    p.parametric.bands.truncate(MAX_BANDS);
    for b in &mut p.parametric.bands {
        if !(b.freq.is_finite() && b.gain_db.is_finite() && b.q.is_finite()) {
            return Err(EqError::Corrupt);
        }
        b.clamp();
    }
    if !GRAPHIC_COUNTS.contains(&p.graphic.band_count)
        || p.graphic.gains_db.len() != p.graphic.band_count as usize
    {
        return Err(EqError::Corrupt);
    }
    for g in &mut p.graphic.gains_db {
        if !g.is_finite() {
            return Err(EqError::Corrupt);
        }
        *g = g.clamp(-GRAPHIC_MAX_DB, GRAPHIC_MAX_DB);
    }
    if !p.preamp_db.is_finite() {
        return Err(EqError::Corrupt);
    }
    p.preamp_db = p.preamp_db.clamp(-MAX_GAIN_DB, MAX_GAIN_DB);
    if !(p.bass_db.is_finite() && p.treble_db.is_finite() && p.balance.is_finite()) {
        return Err(EqError::Corrupt);
    }
    p.bass_db = p.bass_db.clamp(-TONE_MAX_DB, TONE_MAX_DB);
    p.treble_db = p.treble_db.clamp(-TONE_MAX_DB, TONE_MAX_DB);
    p.balance = p.balance.clamp(-1.0, 1.0);
    if p.balance.abs() <= 0.01 {
        p.balance = 0.0;
    }
    if !p.frame_duration_ms.is_finite()
        || !(MIN_FRAME_MS..=MAX_FRAME_MS).contains(&p.frame_duration_ms)
    {
        p.frame_duration_ms = DEFAULT_FRAME_MS;
    }
    // Preset damage never fails the load: bad presets are dropped one by one.
    p.presets = presets::sanitize_user_presets(std::mem::take(&mut p.presets));
    let resolves = p
        .active_preset
        .as_deref()
        .is_some_and(|k| presets::find_in(&p.presets, k).is_some());
    if !resolves {
        p.active_preset = None;
    }
    Ok(p)
}

/// Parses bytes (size is checked by the caller before this).
pub fn parse(bytes: &[u8]) -> Result<Inner, EqError> {
    let value: serde_json::Value = serde_json::from_slice(bytes).map_err(|_| EqError::Corrupt)?;
    let version = value
        .get("version")
        .and_then(|v| v.as_u64())
        .ok_or(EqError::Corrupt)?;
    if version > SCHEMA_VERSION as u64 {
        return Err(EqError::UnsupportedVersion(u32::try_from(version).unwrap_or(u32::MAX)));
    }
    if version == 0 {
        return Err(EqError::Corrupt);
    }
    // Presets are read apart from the rest so a damaged one costs only itself, never the file.
    let mut value = value;
    let (raw_presets, raw_active) = match value.as_object_mut() {
        Some(o) => (o.remove("presets"), o.remove("active_preset")),
        None => (None, None),
    };
    let mut state: PersistedState = serde_json::from_value(value).map_err(|_| EqError::Corrupt)?;
    state.presets = match raw_presets {
        Some(serde_json::Value::Array(items)) => items
            .into_iter()
            .filter_map(|v| serde_json::from_value::<Preset>(v).ok())
            .collect(),
        _ => Vec::new(),
    };
    state.active_preset = raw_active.and_then(|v| v.as_str().map(String::from));
    Ok(sanitize(state)?.into_inner())
}

pub fn load(path: &str) -> Result<Inner, EqError> {
    let file = std::fs::File::open(path).map_err(|e| EqError::Io(e.to_string()))?;
    let len = file.metadata().map_err(|e| EqError::Io(e.to_string()))?.len();
    if len > MAX_FILE_BYTES {
        return Err(EqError::Corrupt);
    }
    let mut bytes = Vec::new();
    file.take(MAX_FILE_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| EqError::Io(e.to_string()))?;
    if bytes.len() as u64 > MAX_FILE_BYTES {
        return Err(EqError::Corrupt);
    }
    parse(&bytes)
}

pub fn load_or_default(path: &str) -> Inner {
    load(path).unwrap_or_default()
}

/// Writes `<path>.tmp` then renames it over `path`; the previous file survives any failure.
pub fn save(path: &str, state: &Inner) -> Result<(), EqError> {
    let json = serde_json::to_vec_pretty(&PersistedState::from_inner(state))
        .map_err(|e| EqError::Io(e.to_string()))?;
    let tmp = format!("{path}.tmp");
    if let Err(e) = std::fs::write(&tmp, &json) {
        let _ = std::fs::remove_file(&tmp);
        return Err(EqError::Io(e.to_string()));
    }
    if let Err(e) = std::fs::rename(&tmp, path) {
        let _ = std::fs::remove_file(&tmp);
        return Err(EqError::Io(e.to_string()));
    }
    Ok(())
}
