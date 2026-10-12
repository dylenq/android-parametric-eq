//! Converts a target curve into engine band gains.
//!
//! Each band's gain is the target sampled at the geometric centre of the band's bins. Gains are
//! clamped to [-60, +30] dB and never non-finite, and no band is ever disabled (the engine
//! applies input gain twice to a disabled band).
//!
//! D-06: the optional smear-compensation iteration (`gain += target - smeared`) is deferred and
//! deliberately has no code path, flag or constant in v1.

use crate::dsp::biquad::Coeffs;
use crate::graphic;
use crate::grid::Grid;
use crate::model::{Band, EqState};

/// Engine gain limits. A design choice, not a platform fact (assumption A3: the emulator only
/// exercised -12..+12 dB).
pub const MIN_ENGINE_DB: f64 = -60.0;
pub const MAX_ENGINE_DB: f64 = 30.0;

/// Frequency at which band `(start, stop)` is sampled: the geometric centre of its bins, with
/// bin 0 treated as half a bin so the centre stays positive.
pub fn band_centre_hz(start: usize, stop: usize, binw: f64) -> f64 {
    let fa = (start as f64).max(0.5) * binw;
    let fb = (stop as f64).max(0.5) * binw;
    (fa * fb).sqrt()
}

/// Gain in dB for every band of `grid`, from `target(f_hz)` in dB (evaluated in f64).
pub fn fit(grid: &Grid, target: impl Fn(f64) -> f64) -> Vec<f64> {
    grid.ranges
        .iter()
        .map(|&(start, stop)| {
            let v = target(band_centre_hz(start, stop, grid.binw));
            if v.is_finite() { v.clamp(MIN_ENGINE_DB, MAX_ENGINE_DB) } else { 0.0 }
        })
        .collect()
}

/// Target closure for a parametric state: sum of enabled bands, preamp excluded. Same maths as
/// `dsp::response::total_db`, kept in f64 end to end (total_db narrows to f32, which would cost
/// about 1e-6 dB against the Python oracle).
pub fn parametric_target(state: &EqState, sample_rate: f64) -> impl Fn(f64) -> f64 + use<> {
    let coeffs: Vec<Coeffs> = state
        .bands
        .iter()
        .filter(|b| b.enabled)
        .map(|b| Coeffs::for_band(b, sample_rate))
        .collect();
    move |f| coeffs.iter().map(|c| c.magnitude_db(f, sample_rate)).sum::<f64>()
}

/// Target closure for a single band, treated as enabled.
pub fn band_target(band: Band, sample_rate: f64) -> impl Fn(f64) -> f64 {
    let mut band = band;
    band.enabled = true;
    let c = Coeffs::for_band(&band, sample_rate);
    move |f| c.magnitude_db(f, sample_rate)
}

/// Target closure for graphic sliders at the standard centres for `count`.
/// Returns `None` for an unsupported count or short gain vector.
pub fn graphic_target<'a>(count: u32, gains_db: &'a [f32]) -> Option<impl Fn(f64) -> f64 + 'a> {
    let centres = graphic::centres(count)?;
    if gains_db.len() < centres.len() {
        return None;
    }
    Some(move |f: f64| graphic::target_db(centres, gains_db, f as f32) as f64)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flat_target_gives_flat_gains() {
        let g = Grid::hybrid(48000, 85.3).unwrap();
        let gains = fit(&g, |_| 0.0);
        assert_eq!(gains.len(), g.band_count());
        assert!(gains.iter().all(|&v| v == 0.0));
    }

    #[test]
    fn clamps_and_maps_non_finite() {
        let g = Grid::hybrid(48000, 85.3).unwrap();
        assert!(fit(&g, |_| 500.0).iter().all(|&v| v == MAX_ENGINE_DB));
        assert!(fit(&g, |_| -500.0).iter().all(|&v| v == MIN_ENGINE_DB));
        assert!(fit(&g, |_| f64::NAN).iter().all(|&v| v == 0.0));
        assert!(fit(&g, |_| f64::NEG_INFINITY).iter().all(|&v| v == 0.0));
    }

    #[test]
    fn centre_of_single_bin_band_is_the_bin_frequency() {
        assert!((band_centre_hz(5, 5, 11.71875) - 5.0 * 11.71875).abs() < 1e-9);
        assert!((band_centre_hz(0, 1, 10.0) - (5.0f64 * 10.0).sqrt()).abs() < 1e-9);
    }

    #[test]
    fn graphic_target_rejects_bad_input() {
        assert!(graphic_target(7, &[0.0; 7]).is_none());
        assert!(graphic_target(10, &[0.0; 5]).is_none());
        assert!(graphic_target(10, &[0.0; 10]).is_some());
    }
}
