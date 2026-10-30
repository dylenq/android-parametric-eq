//! Presets: named snapshots of the whole sound (mode, both editors, preamp, tone, balance).
//! Built-ins are code, never stored; user presets live in the state file. Pure data and rules;
//! the operations that touch history live in [`crate::inner::Inner`].

use serde::{Deserialize, Serialize};

use crate::error::EqError;
use crate::inner::{Inner, TONE_MAX_DB, snap_balance};
use crate::model::{
    Band, EqMode, EqState, GRAPHIC_COUNTS, GRAPHIC_MAX_DB, MAX_BANDS, MAX_GAIN_DB,
};

pub const MAX_USER_PRESETS: usize = 100;
pub const MAX_NAME_CHARS: usize = 40;

const ABS_TOL: f32 = 1e-4;
const REL_TOL: f32 = 1e-4;

/// Everything a preset remembers. Bypass, limiter flag, frame duration and engine cap are
/// listening settings and are not part of it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct PresetCurve {
    pub mode: EqMode,
    pub bands: Vec<Band>,
    pub graphic_band_count: u32,
    pub graphic_gains_db: Vec<f32>,
    pub preamp_db: f32,
    pub bass_db: f32,
    pub treble_db: f32,
    pub balance: f32,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Preset {
    pub name: String,
    pub curve: PresetCurve,
}

impl PresetCurve {
    pub fn from_inner(s: &Inner) -> Self {
        PresetCurve {
            mode: s.mode,
            bands: s.parametric.clone(),
            graphic_band_count: s.graphic.band_count,
            graphic_gains_db: s.graphic.gains_db.clone(),
            preamp_db: s.preamp_db,
            bass_db: s.bass_db,
            treble_db: s.treble_db,
            balance: s.balance,
        }
    }

    /// Whether the curve is structurally usable: supported count, matching gains length and
    /// only finite numbers. Does not look at ranges (see [`PresetCurve::sanitize`]).
    pub fn is_valid(&self) -> bool {
        GRAPHIC_COUNTS.contains(&self.graphic_band_count)
            && self.graphic_gains_db.len() == self.graphic_band_count as usize
            && self.graphic_gains_db.iter().all(|g| g.is_finite())
            && self.bands.iter().all(|b| b.freq.is_finite() && b.gain_db.is_finite() && b.q.is_finite())
            && self.preamp_db.is_finite()
            && self.bass_db.is_finite()
            && self.treble_db.is_finite()
            && self.balance.is_finite()
    }

    /// Clamps every value the way the live state clamps it. Returns false (leaving the curve
    /// unspecified) when it is structurally unusable.
    pub fn sanitize(&mut self) -> bool {
        if !self.is_valid() {
            return false;
        }
        self.bands.truncate(MAX_BANDS);
        for b in &mut self.bands {
            b.clamp();
        }
        for g in &mut self.graphic_gains_db {
            *g = g.clamp(-GRAPHIC_MAX_DB, GRAPHIC_MAX_DB);
        }
        self.preamp_db = self.preamp_db.clamp(-MAX_GAIN_DB, MAX_GAIN_DB);
        self.bass_db = self.bass_db.clamp(-TONE_MAX_DB, TONE_MAX_DB);
        self.treble_db = self.treble_db.clamp(-TONE_MAX_DB, TONE_MAX_DB);
        self.balance = snap_balance(self.balance);
        true
    }
}

fn near(a: f32, b: f32) -> bool {
    (a - b).abs() <= ABS_TOL
}

fn near_freq(a: f32, b: f32) -> bool {
    (a - b).abs() <= REL_TOL * a.abs().max(b.abs()).max(1.0)
}

/// Equality with the tolerances of the CONTEXT amendment; both editors' data are compared
/// whatever the mode.
pub fn curves_equal(a: &PresetCurve, b: &PresetCurve) -> bool {
    a.mode == b.mode
        && a.graphic_band_count == b.graphic_band_count
        && a.bands.len() == b.bands.len()
        && a.graphic_gains_db.len() == b.graphic_gains_db.len()
        && a.bands.iter().zip(&b.bands).all(|(x, y)| {
            x.kind == y.kind
                && x.enabled == y.enabled
                && near_freq(x.freq, y.freq)
                && near(x.gain_db, y.gain_db)
                && near(x.q, y.q)
        })
        && a.graphic_gains_db.iter().zip(&b.graphic_gains_db).all(|(x, y)| near(*x, *y))
        && near(a.preamp_db, b.preamp_db)
        && near(a.bass_db, b.bass_db)
        && near(a.treble_db, b.treble_db)
        && near(a.balance, b.balance)
}

pub fn builtin_key(name: &str) -> String {
    format!("builtin:{name}")
}

pub fn user_key(name: &str) -> String {
    format!("user:{name}")
}

/// The one comparison form for name uniqueness.
pub fn normalize(name: &str) -> String {
    name.trim().to_lowercase()
}

/// Trims and checks a name: 1..=40 chars, no control characters.
pub fn validate_name(name: &str) -> Result<String, EqError> {
    let t = name.trim();
    if t.is_empty() || t.chars().count() > MAX_NAME_CHARS || t.chars().any(char::is_control) {
        return Err(EqError::InvalidName);
    }
    Ok(t.to_string())
}

fn builtin(name: &str, gains: [f32; 10], preamp_db: f32) -> Preset {
    Preset {
        name: name.to_string(),
        curve: PresetCurve {
            mode: EqMode::Graphic,
            bands: EqState::default().bands,
            graphic_band_count: 10,
            graphic_gains_db: gains.to_vec(),
            preamp_db,
            bass_db: 0.0,
            treble_db: 0.0,
            balance: 0.0,
        },
    }
}

/// Flat, Bass, Treble, Vocal, in that order.
pub fn builtin_presets() -> Vec<Preset> {
    vec![
        builtin("Flat", [0.0; 10], 0.0),
        builtin("Bass", [6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0], -6.0),
        builtin("Treble", [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 3.0, 5.0, 6.0], -6.0),
        builtin("Vocal", [0.0, -2.0, -1.0, 0.0, 2.0, 3.0, 3.0, 2.0, 0.0, 0.0], -3.0),
    ]
}

/// Resolves a key against the built-ins and `user` presets. Unknown prefixes resolve to None.
pub fn find_in(user: &[Preset], key: &str) -> Option<Preset> {
    if let Some(n) = key.strip_prefix("builtin:") {
        builtin_presets().into_iter().find(|p| p.name == n)
    } else if let Some(n) = key.strip_prefix("user:") {
        user.iter().find(|p| p.name == n).cloned()
    } else {
        None
    }
}

/// Drops damaged, duplicate and surplus user presets one by one (never an error) and clamps
/// the survivors. Used when loading a file.
pub fn sanitize_user_presets(list: Vec<Preset>) -> Vec<Preset> {
    let mut seen: Vec<String> = builtin_presets().iter().map(|p| normalize(&p.name)).collect();
    let mut out: Vec<Preset> = Vec::new();
    for mut p in list {
        let Ok(name) = validate_name(&p.name) else { continue };
        let norm = normalize(&name);
        if seen.contains(&norm) || !p.curve.sanitize() {
            continue;
        }
        seen.push(norm);
        p.name = name;
        out.push(p);
        if out.len() >= MAX_USER_PRESETS {
            break;
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn builtins_are_valid_ordered_and_unique() {
        let b = builtin_presets();
        let names: Vec<&str> = b.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["Flat", "Bass", "Treble", "Vocal"]);
        let mut uniq = HashSet::new();
        for p in &b {
            assert!(uniq.insert(normalize(&p.name)));
            assert!(validate_name(&p.name).is_ok());
            assert!(GRAPHIC_COUNTS.contains(&p.curve.graphic_band_count));
            assert_eq!(p.curve.graphic_gains_db.len(), p.curve.graphic_band_count as usize);
            let mut c = p.curve.clone();
            assert!(c.sanitize());
            assert_eq!(c, p.curve, "built-in values are in range");
        }
    }

    #[test]
    fn find_in_resolves_prefixes() {
        assert!(find_in(&[], "builtin:Bass").is_some());
        assert!(find_in(&[], "builtin:Nope").is_none());
        assert!(find_in(&[], "user:Bass").is_none());
        assert!(find_in(&[], "Bass").is_none());
    }

    #[test]
    fn names_are_trimmed_and_checked_in_chars() {
        assert_eq!(validate_name("  Night ").unwrap(), "Night");
        assert!(validate_name(&"a".repeat(40)).is_ok());
        assert!(validate_name(&"a".repeat(41)).is_err());
        assert!(validate_name(&"é".repeat(40)).is_ok());
        assert!(validate_name("").is_err());
        assert!(validate_name("   ").is_err());
        assert!(validate_name("a\nb").is_err());
    }
}
