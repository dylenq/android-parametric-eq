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
