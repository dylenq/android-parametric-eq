//! Versioned JSON persistence. The caller supplies the full path; the core builds none.

use std::io::{Read, Write};

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::error::EqError;
use crate::inner::{DEFAULT_FRAME_MS, Inner, MAX_FRAME_MS, MIN_FRAME_MS, TONE_MAX_DB};
use crate::presets::{self, Preset};
use crate::rules::{self, AppRule, DeviceRule, MAX_APP_RULES, MAX_DEVICE_RULES};
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
    #[serde(default)]
    pub app_rules: Vec<AppRule>,
    #[serde(default)]
    pub device_rules: Vec<DeviceRule>,
    #[serde(default)]
    pub last_device: Option<String>,
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
            app_rules: s.app_rules.clone(),
            device_rules: s.device_rules.clone(),
            last_device: s.last_device.clone(),
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
            app_rules: self.app_rules,
            device_rules: self.device_rules,
            last_device: self.last_device,
            ..Inner::default()
        }
    }
}

/// Clamps and validates a parsed state. Anything that cannot be repaired is `Corrupt`.
pub fn sanitize(p: PersistedState) -> Result<PersistedState, EqError> {
    sanitize_counted(p).map(|(p, _)| p)
}

/// [`sanitize`], also returning how many user presets it removed.
fn sanitize_counted(mut p: PersistedState) -> Result<(PersistedState, u32), EqError> {
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
    let before = p.presets.len();
    p.presets = presets::sanitize_user_presets(std::mem::take(&mut p.presets));
    let removed = u32::try_from(before - p.presets.len()).unwrap_or(u32::MAX);
    let resolves = p
        .active_preset
        .as_deref()
        .is_some_and(|k| presets::find_in(&p.presets, k).is_some());
    if !resolves {
        p.active_preset = None;
    }
    let (kept, fixed) = rules::sanitize_rules(std::mem::take(&mut p.app_rules), &p.presets);
    p.app_rules = kept;
    let (kept, fixed_devices) =
        rules::sanitize_devices(std::mem::take(&mut p.device_rules), &p.presets);
    p.device_rules = kept;
    let mut repaired = removed.saturating_add(fixed).saturating_add(fixed_devices);
    if p.last_device.as_deref().is_some_and(|k| !rules::valid_device_key(k)) {
        p.last_device = None;
        repaired = repaired.saturating_add(1);
    }
    Ok((p, repaired))
}

fn count(repairs: &mut u32) {
    *repairs = repairs.saturating_add(1);
}

/// Reads one entry on its own. Absent is an older file: the default, not counted. Present but
/// of the wrong type: the default, counted.
fn read_or<T: DeserializeOwned>(raw: Option<Value>, default: T, repairs: &mut u32) -> T {
    match raw {
        None => default,
        Some(v) => serde_json::from_value(v).unwrap_or_else(|_| {
            count(repairs);
            default
        }),
    }
}

/// [`read_or`] for a number that must also be finite (a value too large for an f32 is not).
fn read_f32(raw: Option<Value>, default: f32, repairs: &mut u32) -> f32 {
    match raw {
        None => default,
        Some(v) => match serde_json::from_value::<f32>(v) {
            Ok(x) if x.is_finite() => x,
            _ => {
                count(repairs);
                default
            }
        },
    }
}

/// Bands one at a time: a damaged band is dropped and counted, bands past the limit are cut and
/// counted. A missing `parametric` or `bands` entry is the default list; one of the wrong shape
/// is the default list and counted.
fn read_bands(raw: Option<Value>, repairs: &mut u32) -> Vec<Band> {
    let items = match raw {
        None => return default_bands(),
        Some(Value::Object(mut o)) => match o.remove("bands") {
            None => return default_bands(),
            Some(Value::Array(items)) => items,
            Some(_) => {
                count(repairs);
                return default_bands();
            }
        },
        Some(_) => {
            count(repairs);
            return default_bands();
        }
    };
    let mut bands: Vec<Band> = items
        .into_iter()
        .filter_map(|v| match serde_json::from_value::<Band>(v) {
            Ok(b) if b.freq.is_finite() && b.gain_db.is_finite() && b.q.is_finite() => Some(b),
            _ => {
                count(repairs);
                None
            }
        })
        .collect();
    let cut = u32::try_from(bands.len().saturating_sub(MAX_BANDS)).unwrap_or(u32::MAX);
    *repairs = repairs.saturating_add(cut);
    bands.truncate(MAX_BANDS);
    bands
}

/// The graphic block is one unit: it must read, have a supported count, one gain per band and
/// only finite numbers, otherwise it is the default graphic state.
fn read_graphic(raw: Option<Value>, repairs: &mut u32) -> GraphicState {
    let Some(v) = raw else { return GraphicState::default() };
    match serde_json::from_value::<GraphicState>(v) {
        Ok(g)
            if GRAPHIC_COUNTS.contains(&g.band_count)
                && g.gains_db.len() == g.band_count as usize
                && g.gains_db.iter().all(|x| x.is_finite()) =>
        {
            g
        }
        _ => {
            count(repairs);
            GraphicState::default()
        }
    }
}

/// Presets one at a time: one that does not read is dropped and counted. A `presets` entry that
/// is not a list is no presets, counted.
fn read_presets(raw: Option<Value>, repairs: &mut u32) -> Vec<Preset> {
    match raw {
        None => Vec::new(),
        Some(Value::Array(items)) => items
            .into_iter()
            .filter_map(|v| match serde_json::from_value::<Preset>(v) {
                Ok(p) => Some(p),
                Err(_) => {
                    count(repairs);
                    None
                }
            })
            .collect(),
        Some(_) => {
            count(repairs);
            Vec::new()
        }
    }
}

/// Rules one at a time: one that does not read is dropped and counted, rules past the limit
/// are cut and counted (by `sanitize`). An entry that is not a list is no rules, counted.
fn read_rules<T: DeserializeOwned>(raw: Option<Value>, max: usize, repairs: &mut u32) -> Vec<T> {
    match raw {
        None => Vec::new(),
        Some(Value::Array(items)) => {
            // Past twice the limit nothing is parsed: the rest is cut here and counted.
            let skipped = items.len().saturating_sub(max * 2);
            for _ in 0..skipped {
                count(repairs);
            }
            items
                .into_iter()
                .take(max * 2)
                .filter_map(|v| match serde_json::from_value::<T>(v) {
                    Ok(r) => Some(r),
                    Err(_) => {
                        count(repairs);
                        None
                    }
                })
                .collect()
        }
        Some(_) => {
            count(repairs);
            Vec::new()
        }
    }
}

/// Parses bytes (size is checked by the caller before this).
///
/// Only the envelope is strict: bytes that are not a JSON object, or a `version` that is missing,
/// zero or not a whole number, are `Corrupt`, and a newer version is `UnsupportedVersion`. Every
/// other entry is read on its own, so a damaged one costs only itself; `load_repairs` on the
/// result says how many were dropped or reset to their default.
pub fn parse(bytes: &[u8]) -> Result<Inner, EqError> {
    let value: Value = serde_json::from_slice(bytes).map_err(|_| EqError::Corrupt)?;
    let Value::Object(mut o) = value else { return Err(EqError::Corrupt) };
    let version = o
        .get("version")
        .and_then(|v| v.as_u64())
        .ok_or(EqError::Corrupt)?;
    if version > SCHEMA_VERSION as u64 {
        return Err(EqError::UnsupportedVersion(u32::try_from(version).unwrap_or(u32::MAX)));
    }
    if version == 0 {
        return Err(EqError::Corrupt);
    }
    let d = Inner::default();
    let mut repairs: u32 = 0;
    let r = &mut repairs;
    let state = PersistedState {
        version: u32::try_from(version).unwrap_or(SCHEMA_VERSION),
        mode: read_or(o.remove("mode"), d.mode, r),
        parametric: PersistedParametric { bands: read_bands(o.remove("parametric"), r) },
        graphic: read_graphic(o.remove("graphic"), r),
        preamp_db: read_f32(o.remove("preamp_db"), d.preamp_db, r),
        bass_db: read_f32(o.remove("bass_db"), d.bass_db, r),
        treble_db: read_f32(o.remove("treble_db"), d.treble_db, r),
        balance: read_f32(o.remove("balance"), d.balance, r),
        limiter_enabled: read_or(o.remove("limiter_enabled"), d.limiter_enabled, r),
        bypass: read_or(o.remove("bypass"), d.bypass, r),
        frame_duration_ms: read_f32(o.remove("frame_duration_ms"), d.frame_duration_ms, r),
        presets: read_presets(o.remove("presets"), r),
        // A wrong type or a null (what "no active preset" is saved as) just means none.
        active_preset: match o.remove("active_preset") {
            Some(Value::String(key)) => Some(key),
            _ => None,
        },
        app_rules: read_rules(o.remove("app_rules"), MAX_APP_RULES, r),
        device_rules: read_rules(o.remove("device_rules"), MAX_DEVICE_RULES, r),
        // A null is none; any other wrong type is none, counted.
        last_device: match o.remove("last_device") {
            None | Some(Value::Null) => None,
            Some(Value::String(key)) => Some(key),
            Some(_) => {
                count(r);
                None
            }
        },
    };
    // Every value above is typed and finite; this clamps the ranges and drops unusable presets.
    // It cannot fail on such input, and if it ever did the file is Corrupt rather than a panic.
    let (state, removed) = sanitize_counted(state)?;
    let mut inner = state.into_inner();
    inner.load_repairs = repairs.saturating_add(removed);
    Ok(inner)
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

/// Flushes to the disk before the rename: otherwise a power cut can leave the rename on disk but not
/// the data, replacing a good file with an empty one.
fn write_durably(path: &str, bytes: &[u8]) -> std::io::Result<()> {
    let mut f = std::fs::File::create(path)?;
    f.write_all(bytes)?;
    f.sync_all()
}

/// Writes `<path>.tmp` then renames it over `path`; the previous file survives any failure.
pub fn save(path: &str, state: &Inner) -> Result<(), EqError> {
    let json = serde_json::to_vec_pretty(&PersistedState::from_inner(state))
        .map_err(|e| EqError::Io(e.to_string()))?;
    let tmp = format!("{path}.tmp");
    if let Err(e) = write_durably(&tmp, &json) {
        let _ = std::fs::remove_file(&tmp);
        return Err(EqError::Io(e.to_string()));
    }
    if let Err(e) = std::fs::rename(&tmp, path) {
        let _ = std::fs::remove_file(&tmp);
        return Err(EqError::Io(e.to_string()));
    }
    Ok(())
}
