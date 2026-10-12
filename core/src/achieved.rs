//! The achieved (engine) response: what DynamicsProcessing's pre-EQ actually does.
//!
//! Port of `band_model.py` (`bin_gains`, `_wmag`, `smeared_gain_db`). The engine applies a
//! piecewise-constant gain per FFT bin range; a steady pure tone sees those bin gains smeared by
//! the analysis window, modelled as a sine window (assumption A7: validated against the emulator
//! only for pure tones at Q 8). For broadband material read the result as the "expected
//! response". Gains are f64 here; the f32 values sent to the engine differ by about 1e-6 dB.

use crate::grid::Grid;

/// Per-bin gain (dB) for bins `0..=block/2`, following the engine loop: each band fills its
/// range; empty or over-Nyquist ranges write nothing.
pub fn bin_gains(grid: &Grid, band_gains_db: &[f64]) -> Vec<f64> {
    let half = grid.half();
    let mut g = vec![0.0; half + 1];
    for (i, &(start, stop)) in grid.ranges.iter().enumerate() {
        if stop < start {
            continue;
        }
        let top = stop.min(half);
        if start > top {
            continue;
        }
        let val = band_gains_db.get(i).copied().unwrap_or(0.0);
        for slot in &mut g[start..=top] {
            *slot = val;
        }
    }
    g
}

/// Sine analysis window magnitude at offset `d` bins.
pub fn wmag(d: f64) -> f64 {
    let d = d.abs();
    let den = (1.0 - 4.0 * d * d).abs();
    if den < 1e-9 {
        return std::f64::consts::FRAC_PI_4;
    }
    if d < 1.5 {
        (std::f64::consts::PI * d).cos() / den
    } else {
        0.0
    }
}

/// Gain (dB) a steady tone at `f_hz` sees: `20 log10(sum w^2 10^(g/20) / sum w^2)` over bins
/// `k0-4..=k0+4`, `k0 = floor(f / binw)`. Out-of-range bins are skipped, as in the model.
pub fn smeared_db(bins: &[f64], f_hz: f64, binw: f64) -> f64 {
    if bins.is_empty() {
        return 0.0;
    }
    let x = f_hz / binw;
    let k0 = x.floor() as i64;
    let lo = (k0 - 4).max(0);
    let hi = (k0 + 5).min(bins.len() as i64 - 1);
    let (mut num, mut den) = (0.0, 0.0);
    for k in lo..=hi {
        let w2 = wmag(k as f64 - x).powi(2);
        num += w2 * 10f64.powf(bins[k as usize] / 20.0);
        den += w2;
    }
    if den <= 0.0 {
        // Beyond the array by more than the window: use the nearest edge bin.
        let edge = if k0 < 0 { bins[0] } else { bins[bins.len() - 1] };
        return edge;
    }
    20.0 * (num / den).log10()
}

/// A bin array built once and reused for a whole curve.
pub struct BinGains {
    pub bins: Vec<f64>,
    pub binw: f64,
}

impl BinGains {
    pub fn new(grid: &Grid, band_gains_db: &[f64]) -> Self {
        BinGains { bins: bin_gains(grid, band_gains_db), binw: grid.binw }
    }

    /// Achieved gain in dB at `f_hz`.
    pub fn at(&self, f_hz: f64) -> f64 {
        smeared_db(&self.bins, f_hz, self.binw)
    }
}

/// One-off achieved gain at `f_hz` (builds the bin array; use [`BinGains`] for many points).
pub fn achieved_db_at(grid: &Grid, band_gains_db: &[f64], f_hz: f64) -> f64 {
    BinGains::new(grid, band_gains_db).at(f_hz)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grid() -> Grid {
        Grid::hybrid(48000, 85.3).unwrap()
    }

    #[test]
    fn flat_zero_achieved_is_zero_everywhere() {
        let g = grid();
        let b = BinGains::new(&g, &vec![0.0; g.band_count()]);
        for k in 0..2000 {
            let f = 5.0 + k as f64 * 11.9;
            assert!(b.at(f).abs() < 1e-12, "{f}");
        }
    }

    #[test]
    fn constant_boost_achieved_is_constant() {
        let g = grid();
        let b = BinGains::new(&g, &vec![6.0; g.band_count()]);
        for k in 0..2000 {
            let f = 5.0 + k as f64 * 11.9;
            assert!((b.at(f) - 6.0).abs() < 1e-9, "{f}");
        }
        assert!((achieved_db_at(&g, &vec![6.0; g.band_count()], 1000.0) - 6.0).abs() < 1e-9);
    }

    #[test]
    fn bin_gains_fill_ranges() {
        let g = grid();
        let gains: Vec<f64> = (0..g.band_count()).map(|i| i as f64).collect();
        let bins = bin_gains(&g, &gains);
        assert_eq!(bins.len(), g.half() + 1);
        for (i, &(a, b)) in g.ranges.iter().enumerate() {
            assert_eq!(bins[a], i as f64);
            assert_eq!(bins[b], i as f64);
        }
    }

    #[test]
    fn window_singularity_value() {
        assert!((wmag(0.5) - std::f64::consts::FRAC_PI_4).abs() < 1e-12);
        assert!((wmag(0.0) - 1.0).abs() < 1e-12);
        assert_eq!(wmag(1.5), 0.0);
    }
}
