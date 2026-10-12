// Origin: macOS EQ app src/state.rs (lines 1-135 plus Curve), commit 03be7aa, GPL-3.0.
// Ported for Android: settings, devices and UI selection plumbing are not carried over.

use serde::{Deserialize, Serialize};

pub const MAX_BANDS: usize = 16;
pub const MIN_FREQ: f32 = 20.0;
pub const MAX_FREQ: f32 = 20_000.0;
pub const MIN_Q: f32 = 0.1;
pub const MAX_Q: f32 = 18.0;
pub const MAX_GAIN_DB: f32 = 24.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum FilterKind {
    Peak,
    LowShelf,
    HighShelf,
    LowPass,
    HighPass,
}

impl FilterKind {
    pub const ALL: [FilterKind; 5] = [
        FilterKind::Peak,
        FilterKind::LowShelf,
        FilterKind::HighShelf,
        FilterKind::LowPass,
        FilterKind::HighPass,
    ];

    pub fn label(self) -> &'static str {
        match self {
            FilterKind::Peak => "Peak",
            FilterKind::LowShelf => "Low Shelf",
            FilterKind::HighShelf => "High Shelf",
            FilterKind::LowPass => "Low Pass",
            FilterKind::HighPass => "High Pass",
        }
    }

    /// Pass filters have no gain parameter.
    pub fn has_gain(self) -> bool {
        !matches!(self, FilterKind::LowPass | FilterKind::HighPass)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct Band {
    pub kind: FilterKind,
    pub freq: f32,
    pub gain_db: f32,
    pub q: f32,
    pub enabled: bool,
}

impl Band {
    pub fn new(kind: FilterKind, freq: f32) -> Self {
        Band {
            kind,
            freq,
            gain_db: 0.0,
            q: std::f32::consts::FRAC_1_SQRT_2,
            enabled: true,
        }
    }

    pub fn clamp(&mut self) {
        self.freq = self.freq.clamp(MIN_FREQ, MAX_FREQ);
        self.q = self.q.clamp(MIN_Q, MAX_Q);
        self.gain_db = self.gain_db.clamp(-MAX_GAIN_DB, MAX_GAIN_DB);
    }
}

fn enabled() -> bool {
    true
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct EqState {
    pub bands: Vec<Band>,
    pub preamp_db: f32,
    pub bypass: bool,
    /// Whether a limiter holds the output under full scale instead of letting it clip.
    #[serde(default = "enabled")]
    pub clip_protection: bool,
}

impl Default for EqState {
    fn default() -> Self {
        let mut bands = vec![Band::new(FilterKind::LowShelf, 80.0)];
        for freq in [200.0, 500.0, 1_000.0, 2_500.0, 6_000.0] {
            let mut band = Band::new(FilterKind::Peak, freq);
            band.q = 1.0;
            bands.push(band);
        }
        bands.push(Band::new(FilterKind::HighShelf, 10_000.0));
        EqState {
            bands,
            preamp_db: 0.0,
            bypass: false,
            clip_protection: true,
        }
    }
}

impl EqState {
    /// Whether another parametric band fits.
    pub fn can_add_band(&self) -> bool {
        self.bands.len() < MAX_BANDS
    }
}

/// The part of an [`EqState`] that presets remember: everything but bypass.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Curve {
    pub bands: Vec<Band>,
    pub preamp_db: f32,
}

// ---- Android additions: graphic mode (not in the macOS app) ----

pub const GRAPHIC_COUNTS: [u32; 4] = [5, 10, 15, 31];
pub const GRAPHIC_MAX_DB: f32 = 24.0;

/// Which editor drives the curve.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum EqMode {
    Parametric,
    #[default]
    Graphic,
}

/// Why a graphic edit was refused.
#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)]
pub enum GraphicError {
    #[error("band index out of range")]
    BadIndex,
    #[error("gain is not a finite number")]
    NotFinite,
    #[error("unsupported band count")]
    BadCount,
}

/// Slider gains for the graphic EQ: one gain per ISO centre of `band_count`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct GraphicState {
    pub band_count: u32,
    pub gains_db: Vec<f32>,
}

impl Default for GraphicState {
    fn default() -> Self {
        GraphicState {
            band_count: 10,
            gains_db: vec![0.0; 10],
        }
    }
}

impl GraphicState {
    /// Sets one slider, clamped to +/-24 dB. Rejects bad indices and non-finite input.
    pub fn set_gain(&mut self, index: usize, db: f32) -> Result<(), GraphicError> {
        if !db.is_finite() {
            return Err(GraphicError::NotFinite);
        }
        let slot = self.gains_db.get_mut(index).ok_or(GraphicError::BadIndex)?;
        *slot = db.clamp(-GRAPHIC_MAX_DB, GRAPHIC_MAX_DB);
        Ok(())
    }

    /// Switches to another band count, resampling the current curve.
    pub fn set_band_count(&mut self, count: u32) -> Result<(), GraphicError> {
        if !GRAPHIC_COUNTS.contains(&count) {
            return Err(GraphicError::BadCount);
        }
        self.gains_db = crate::graphic::resample(self.band_count, &self.gains_db, count);
        self.band_count = count;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn graphic_default_is_ten_flat_bands() {
        let g = GraphicState::default();
        assert_eq!((g.band_count, g.gains_db.len()), (10, 10));
        assert_eq!(EqMode::default(), EqMode::Graphic);
    }

    #[test]
    fn set_gain_clamps_and_rejects() {
        let mut g = GraphicState::default();
        g.set_gain(0, 99.0).unwrap();
        g.set_gain(1, -99.0).unwrap();
        assert_eq!((g.gains_db[0], g.gains_db[1]), (24.0, -24.0));
        assert_eq!(g.set_gain(10, 1.0), Err(GraphicError::BadIndex));
        assert_eq!(g.set_gain(0, f32::NAN), Err(GraphicError::NotFinite));
        assert_eq!(g.set_gain(0, f32::INFINITY), Err(GraphicError::NotFinite));
        assert_eq!(g.gains_db[0], 24.0);
    }

    #[test]
    fn set_band_count_resamples_and_rejects_bad_counts() {
        let mut g = GraphicState::default();
        g.set_gain(3, 6.0).unwrap();
        g.set_band_count(31).unwrap();
        assert_eq!((g.band_count, g.gains_db.len()), (31, 31));
        assert_eq!(g.set_band_count(7), Err(GraphicError::BadCount));
        assert_eq!((g.band_count, g.gains_db.len()), (31, 31));
        g.set_band_count(5).unwrap();
        assert_eq!(g.gains_db.len(), 5);
    }

    #[test]
    fn parametric_limits_match_macos() {
        let mut b = Band::new(FilterKind::Peak, 5.0);
        b.q = 100.0;
        b.gain_db = -90.0;
        b.clamp();
        assert_eq!((b.freq, b.q, b.gain_db), (20.0, 18.0, -24.0));
        assert_eq!(MAX_BANDS, 16);
        assert!(EqState::default().can_add_band());
    }
}
