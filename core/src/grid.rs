//! The engine band layout: block size rule, bin stops and the hybrid grid.
//!
//! Pure maths, no FFI attributes. Mirrors the native DynamicsProcessing rules read from AOSP
//! (`EffectDynamicsProcessing.cpp`, `DPFrequency.cpp`) and the Phase 1 `band_model.py`.

use std::fmt;

/// Smallest and largest block the engine uses.
pub const MIN_BLOCK: u32 = 8;
pub const MAX_BLOCK: u32 = 16384;
/// Below this frequency the hybrid grid has one band per FFT bin.
pub const LINEAR_REGION_HZ: f64 = 600.0;
/// Number of log-spaced points above the linear region (before dedupe).
pub const LOG_BANDS: usize = 256;
/// A cap below this is raised to it; the engine never gets fewer bands.
pub const MIN_ENGINE_BANDS: usize = 8;
/// Fewest log-spaced bands kept while the linear region is thinned instead.
pub const MIN_LOG_BANDS: usize = 32;
/// Band count the app requests when probing what the engine really allocates. At least as large
/// as every grid `Grid::hybrid` can build for 44.1/48/96 kHz and 5..=300 ms frames.
pub const PROBE_BANDS: usize = 512;

/// Rejected inputs; the API layer maps these to its own error type.
#[derive(Clone, Debug, PartialEq)]
pub enum GridError {
    /// Sample rate outside 8000..=192000 Hz.
    SampleRate(u32),
    /// Frame duration not finite or outside 5.0..=300.0 ms.
    FrameMs(f32),
}

impl fmt::Display for GridError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            GridError::SampleRate(sr) => write!(f, "sample rate {sr} Hz is outside 8000..=192000"),
            GridError::FrameMs(ms) => write!(f, "frame duration {ms} ms is outside 5.0..=300.0"),
        }
    }
}

impl std::error::Error for GridError {}

/// FFT block size the engine picks for a preferred frame duration: the native rule is
/// truncate `frame_ms * sr / 1000` to an integer, below 8 becomes 8, a non-power-of-two rounds
/// up, and the maximum is 16384. (band_model.py uses a ceil of the float; the two differ only
/// for values just above an exact power of two.)
pub fn block_size(frame_ms: f32, sr: u32) -> u32 {
    let samples = (frame_ms * sr as f32 / 1000.0) as i64;
    if samples < MIN_BLOCK as i64 {
        return MIN_BLOCK;
    }
    if samples >= MAX_BLOCK as i64 {
        return MAX_BLOCK;
    }
    (samples as u32).next_power_of_two().min(MAX_BLOCK)
}

/// The last bin a band with this cutoff covers: `int(0.5 + cutoff * block / sr)`.
pub fn bin_stop(cutoff_hz: f64, block: u32, sr: u32) -> i64 {
    (0.5 + cutoff_hz * block as f64 / sr as f64) as i64
}

/// A band layout for one sample rate and frame duration.
#[derive(Clone, Debug, PartialEq)]
pub struct Grid {
    pub block: u32,
    pub sr: u32,
    /// Bin width in Hz (`sr / block`).
    pub binw: f64,
    /// Bin-centred cutoffs (`stop * binw`), strictly increasing, last = Nyquist.
    pub cutoffs_hz: Vec<f32>,
    /// Inclusive bin range `(start, stop)` of each band, following the engine loop.
    pub ranges: Vec<(usize, usize)>,
}

impl Grid {
    /// Hybrid grid: one band per FFT bin up to ~600 Hz, then 256 log-spaced bands to Nyquist,
    /// deduplicated on `bin_stop`. Cutoffs are re-emitted at the bin centre so f32 rounding on
    /// the device cannot move a band edge.
    pub fn hybrid(sr: u32, frame_ms: f32) -> Result<Grid, GridError> {
        Self::build(sr, frame_ms, 1, LOG_BANDS)
    }

    /// Hybrid grid fitted to an engine band cap. `None`, or a cap (raised to `MIN_ENGINE_BANDS`)
    /// that the full grid already fits, returns exactly `Grid::hybrid`. Otherwise the log band
    /// count is reduced first, then the linear region is thinned (stride 2, 3, ...), then the log
    /// bands are thinned below `MIN_LOG_BANDS`.
    pub fn hybrid_capped(sr: u32, frame_ms: f32, max_bands: Option<usize>) -> Result<Grid, GridError> {
        let full = Self::hybrid(sr, frame_ms)?;
        let cap = match max_bands {
            None => return Ok(full),
            Some(c) => c.max(MIN_ENGINE_BANDS),
        };
        if full.band_count() <= cap {
            return Ok(full);
        }
        let make = |stride: usize, log: usize| Self::build(sr, frame_ms, stride, log);
        // 1. Fewer log bands, linear region intact.
        if make(1, MIN_LOG_BANDS)?.band_count() <= cap {
            let (mut lo, mut hi) = (MIN_LOG_BANDS, LOG_BANDS);
            while hi - lo > 1 {
                let mid = (lo + hi) / 2;
                if make(1, mid)?.band_count() <= cap {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            // Dedupe makes the count only near-monotone: step down until it fits.
            let mut l = lo;
            loop {
                let g = make(1, l)?;
                if g.band_count() <= cap || l <= MIN_LOG_BANDS {
                    return Ok(g);
                }
                l -= 1;
            }
        }
        // 2. Thin the linear region.
        let kmax = ((LINEAR_REGION_HZ / (sr as f64 / block_size(frame_ms, sr) as f64)).floor() as usize).max(1);
        for stride in 2..=kmax + 1 {
            let g = make(stride, MIN_LOG_BANDS)?;
            if g.band_count() <= cap {
                return Ok(g);
            }
        }
        // 3. No linear region left: thin the log bands.
        let mut l = MIN_LOG_BANDS;
        loop {
            let g = make(kmax + 1, l)?;
            if g.band_count() <= cap || l <= 1 {
                return Ok(g);
            }
            l -= 1;
        }
    }

    fn build(sr: u32, frame_ms: f32, stride: usize, log_bands: usize) -> Result<Grid, GridError> {
        let stride = stride.max(1);
        let log_bands = log_bands.max(1);
        if !(8000..=192_000).contains(&sr) {
            return Err(GridError::SampleRate(sr));
        }
        if !frame_ms.is_finite() || !(5.0..=300.0).contains(&frame_ms) {
            return Err(GridError::FrameMs(frame_ms));
        }
        let block = block_size(frame_ms, sr);
        let binw = sr as f64 / block as f64;
        let nyquist = sr as f64 / 2.0;
        let kmax = ((LINEAR_REGION_HZ / binw).floor() as usize).max(1);

        let mut cuts: Vec<f64> = (1..=kmax).step_by(stride).map(|k| k as f64 * binw).collect();
        let lo = ((kmax / stride) * stride + 1) as f64 * binw;
        for i in 0..log_bands {
            let t = if log_bands == 1 { 1.0 } else { i as f64 / (log_bands - 1) as f64 };
            cuts.push(lo * (nyquist / lo).powf(t));
        }
        if let Some(last) = cuts.last_mut() {
            *last = nyquist;
        }
        cuts.sort_by(|a, b| a.total_cmp(b));

        let mut stops: Vec<i64> = Vec::with_capacity(cuts.len());
        for c in cuts {
            let st = bin_stop(c, block, sr);
            if !stops.contains(&st) {
                stops.push(st);
            }
        }

        let cutoffs_hz: Vec<f32> = stops.iter().map(|&s| (s as f64 * binw) as f32).collect();
        let mut ranges = Vec::with_capacity(stops.len());
        let mut next: i64 = 0;
        for &s in &stops {
            ranges.push((next as usize, s.max(0) as usize));
            next = s + 1;
        }
        Ok(Grid { block, sr, binw, cutoffs_hz, ranges })
    }

    pub fn band_count(&self) -> usize {
        self.cutoffs_hz.len()
    }

    /// Highest bin index (`block / 2`).
    pub fn half(&self) -> usize {
        (self.block / 2) as usize
    }
}

/// Deterministic identity of a layout (FNV-1a over block, sample rate and cutoff bit patterns).
/// Changes only when the effect must be re-created.
pub fn layout_id(grid: &Grid) -> u32 {
    const OFFSET: u32 = 0x811c_9dc5;
    const PRIME: u32 = 0x0100_0193;
    let mut h = OFFSET;
    let mut feed = |v: u32| {
        for byte in v.to_le_bytes() {
            h ^= byte as u32;
            h = h.wrapping_mul(PRIME);
        }
    };
    feed(grid.block);
    feed(grid.sr);
    for c in &grid.cutoffs_hz {
        feed(c.to_bits());
    }
    h
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn block_size_table() {
        assert_eq!(block_size(85.3, 48000), 4096);
        assert_eq!(block_size(170.6, 48000), 8192);
        assert_eq!(block_size(21.3, 48000), 1024);
        assert_eq!(block_size(10.0, 48000), 512);
        assert_eq!(block_size(85.3, 44100), 4096);
        assert_eq!(block_size(170.6, 44100), 8192);
        assert_eq!(block_size(21.3, 44100), 1024);
        assert_eq!(block_size(10.0, 44100), 512);
    }

    #[test]
    fn block_size_is_power_of_two_in_range() {
        for sr in [8000u32, 44100, 48000, 96000, 192000] {
            let mut ms = 0.5f32;
            while ms < 400.0 {
                let b = block_size(ms, sr);
                assert!(b.is_power_of_two() && (MIN_BLOCK..=MAX_BLOCK).contains(&b), "{ms} {sr} {b}");
                ms += 0.7;
            }
        }
        assert_eq!(block_size(0.01, 48000), 8);
        assert_eq!(block_size(1000.0, 192000), 16384);
    }

    fn all_grids() -> Vec<Grid> {
        let mut v = Vec::new();
        for sr in [44100u32, 48000] {
            for ms in [42.6f32, 85.3, 170.6] {
                v.push(Grid::hybrid(sr, ms).unwrap());
            }
        }
        v
    }

    #[test]
    fn cutoffs_strictly_increasing() {
        for g in all_grids() {
            assert!(g.cutoffs_hz.windows(2).all(|w| w[0] < w[1]), "{} {}", g.sr, g.block);
        }
    }

    #[test]
    fn bin_stop_round_trip() {
        for g in all_grids() {
            for (i, &c) in g.cutoffs_hz.iter().enumerate() {
                assert_eq!(bin_stop(c as f64, g.block, g.sr), g.ranges[i].1 as i64);
            }
        }
    }

    #[test]
    fn last_cutoff_is_nyquist() {
        for g in all_grids() {
            let last = *g.cutoffs_hz.last().unwrap();
            assert_eq!(last, g.sr as f32 / 2.0);
            assert_eq!(g.ranges.last().unwrap().1, g.half());
        }
    }

    #[test]
    fn band_count_window() {
        let a = Grid::hybrid(48000, 85.3).unwrap();
        let b = Grid::hybrid(48000, 170.6).unwrap();
        assert!((300..=308).contains(&a.band_count()), "85.3 ms: {}", a.band_count());
        assert!((354..=362).contains(&b.band_count()), "170.6 ms: {}", b.band_count());
    }

    #[test]
    fn ranges_cover_bins_without_gaps() {
        for g in all_grids() {
            assert_eq!(g.ranges[0].0, 0);
            for w in g.ranges.windows(2) {
                assert_eq!(w[1].0, w[0].1 + 1);
                assert!(w[0].0 <= w[0].1);
            }
            assert_eq!(g.ranges.last().unwrap().1, g.half());
        }
    }

    #[test]
    fn layout_id_is_deterministic_and_sensitive() {
        let a = Grid::hybrid(48000, 85.3).unwrap();
        let b = Grid::hybrid(48000, 85.3).unwrap();
        assert_eq!(layout_id(&a), layout_id(&b));
        let c = Grid::hybrid(44100, 85.3).unwrap();
        let d = Grid::hybrid(48000, 170.6).unwrap();
        assert_ne!(layout_id(&a), layout_id(&c));
        assert_ne!(layout_id(&a), layout_id(&d));
        let mut e = a.clone();
        e.cutoffs_hz[10] = f32::from_bits(e.cutoffs_hz[10].to_bits() + 1);
        assert_ne!(layout_id(&a), layout_id(&e));
    }

    #[test]
    fn rejects_bad_inputs() {
        assert!(matches!(Grid::hybrid(100, 85.3), Err(GridError::SampleRate(100))));
        assert!(matches!(Grid::hybrid(48000, 1.0), Err(GridError::FrameMs(_))));
        assert!(matches!(Grid::hybrid(48000, f32::NAN), Err(GridError::FrameMs(_))));
        assert!(Grid::hybrid(192_000, 300.0).is_ok());
    }
}
