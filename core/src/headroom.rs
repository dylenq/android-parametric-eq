//! Headroom, suggested preamp and limiter data.
//!
//! The achieved peak is the real clip defence: the engine's limiter acts on a per-block RMS
//! envelope, so it is a sustained-overload guard and cannot stop peak clipping.

use crate::achieved::BinGains;
use crate::grid::Grid;

/// The default limiter values were measured on the emulator (AOSP effect library) with the
/// Phase 3 Visualizer rig: all 10 rows (2 frame durations x 5 levels) matched the threshold/ratio
/// prediction within 0.01 dB, inside the 1.5 dB bound. See
/// `.planning/milestones/v0.1-phases/03-service-and-sessions/evidence/LIMITER-TABLE.md`. This says nothing about
/// OEM vendor DSPs, and the limiter still does not stop peak clipping.
pub const LIMITER_VERIFIED: bool = true;

/// Lowest and highest preamp the suggestion may return (the UI preamp range is +/- MAX_GAIN_DB).
pub const MIN_PREAMP_DB: f64 = -24.0;

/// DynamicsProcessing limiter stage settings.
///
/// Verified on the emulator ([`LIMITER_VERIFIED`] is true, LIMITER-TABLE.md): the limiter acts
/// on a per-block RMS envelope with threshold -6 dB and ratio 10 and cannot prevent peak
/// clipping. The preamp headroom from the achieved peak is the real clip defence. There is
/// deliberately no make-up gain.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct LimiterParams {
    pub link_group: u32,
    pub attack_ms: f32,
    pub release_ms: f32,
    pub ratio: f32,
    pub threshold_db: f32,
    pub post_gain_db: f32,
}

impl Default for LimiterParams {
    fn default() -> Self {
        LimiterParams {
            link_group: 0,
            attack_ms: 1.0,
            release_ms: 100.0,
            ratio: 10.0,
            threshold_db: -6.0,
            post_gain_db: 0.0,
        }
    }
}

/// Peak of the achieved (smeared) curve, sampled at every bin centre up to 20 kHz.
pub fn peak_db(grid: &Grid, band_gains_db: &[f64]) -> f64 {
    let bg = BinGains::new(grid, band_gains_db);
    let last = ((20_000.0 / grid.binw).floor() as usize).min(grid.half());
    let mut peak = f64::NEG_INFINITY;
    for k in 1..=last {
        peak = peak.max(bg.at(k as f64 * grid.binw));
    }
    if peak.is_finite() { peak } else { 0.0 }
}

/// Preamp that brings the achieved peak to at most 0 dB: minus the peak rounded away from zero
/// to 0.1 dB, never positive, clamped to [-24, 0].
pub fn suggested_preamp_db(peak_db: f64) -> f64 {
    if !peak_db.is_finite() || peak_db <= 0.0 {
        return 0.0;
    }
    // Round the peak up to 0.1 dB. The small guard stops representation noise (e.g. 6.0000000001)
    // from costing a whole extra step.
    let steps = (peak_db * 10.0 - 1e-9).ceil();
    (-(steps / 10.0)).clamp(MIN_PREAMP_DB, 0.0)
}

/// Remaining headroom below full scale once `preamp_db` is applied.
pub fn headroom_after_preamp_db(peak_db: f64, preamp_db: f64) -> f64 {
    -(peak_db + preamp_db)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fit::{band_target, fit};
    use crate::model::{Band, FilterKind};

    fn grid() -> Grid {
        Grid::hybrid(48000, 85.3).unwrap()
    }

    #[test]
    fn zero_fit_peak_is_zero() {
        let g = grid();
        assert!(peak_db(&g, &vec![0.0; g.band_count()]).abs() < 1e-9);
    }

    #[test]
    fn uniform_boost_peak() {
        let g = grid();
        assert!((peak_db(&g, &vec![6.0; g.band_count()]) - 6.0).abs() < 1e-6);
    }

    #[test]
    fn single_peak_range() {
        let g = grid();
        let mut b = Band::new(FilterKind::Peak, 1000.0);
        b.gain_db = 12.0;
        b.q = 1.0;
        let gains = fit(&g, band_target(b, 48000.0));
        let p = peak_db(&g, &gains);
        assert!((10.5..=12.1).contains(&p), "{p}");
    }

    #[test]
    fn preamp_is_never_positive() {
        for p in [-30.0, -0.1, 0.0, f64::NAN] {
            assert_eq!(suggested_preamp_db(p), 0.0);
        }
        assert!(suggested_preamp_db(3.0) <= 0.0);
    }

    #[test]
    fn preamp_rounds_away_from_zero() {
        assert!((suggested_preamp_db(5.01) + 5.1).abs() < 1e-12);
        assert!((suggested_preamp_db(5.1) + 5.1).abs() < 1e-12);
        assert!((suggested_preamp_db(0.01) + 0.1).abs() < 1e-12);
        assert!((suggested_preamp_db(11.99) + 12.0).abs() < 1e-12);
    }

    #[test]
    fn preamp_clamps_at_minus_24() {
        assert_eq!(suggested_preamp_db(40.0), -24.0);
        assert_eq!(suggested_preamp_db(24.0), -24.0);
    }

    #[test]
    fn preamp_brings_peak_under_zero_property() {
        let mut state: u64 = 0x1234_5678_9abc_def0;
        for _ in 0..5000 {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let peak = ((state >> 11) as f64 / (1u64 << 53) as f64) * 24.0;
            let pre = suggested_preamp_db(peak);
            assert!(peak + pre <= 1e-9, "peak {peak} preamp {pre}");
            assert!(headroom_after_preamp_db(peak, pre) >= -1e-9);
            // Never over-corrects by more than one 0.1 dB step.
            assert!(peak + pre >= -0.1 - 1e-9, "peak {peak} preamp {pre}");
        }
    }

    #[test]
    fn headroom_value() {
        assert!((headroom_after_preamp_db(6.0, -6.0)).abs() < 1e-12);
        assert!((headroom_after_preamp_db(3.0, -6.0) - 3.0).abs() < 1e-12);
    }

    #[test]
    fn limiter_defaults() {
        let l = LimiterParams::default();
        assert_eq!(l.link_group, 0);
        assert_eq!(l.attack_ms, 1.0);
        assert_eq!(l.release_ms, 100.0);
        assert_eq!(l.ratio, 10.0);
        assert_eq!(l.threshold_db, -6.0);
        assert_eq!(l.post_gain_db, 0.0);
    }

    #[test]
    fn limiter_is_verified_by_the_phase3_table() {
        // Flipped only because LIMITER-TABLE.md shows every row within 1.5 dB.
        assert!(LIMITER_VERIFIED);
    }
}
