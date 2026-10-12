//! Graphic-EQ maths: ISO centre tables and the slider-to-curve mapping.
//!
//! The target curve is a monotone cubic (PCHIP, Fritsch-Carlson) in x = log2(f), y = dB:
//! it passes through every slider value at its centre, is C1-smooth, never overshoots
//! between neighbouring sliders and is held flat beyond the end bands. Pure functions.

/// 5 bands: every second octave band, nests inside the 10-band set. There is no ISO set;
/// Android's stock 60/230/910/3600/14000 was not adopted.
const C5: [f32; 5] = [63.0, 250.0, 1000.0, 4000.0, 16000.0];
const C10: [f32; 10] = [
    31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
];
const C15: [f32; 15] = [
    25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0,
    10000.0, 16000.0,
];
const C31: [f32; 31] = [
    20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0,
    500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0,
    8000.0, 10000.0, 12500.0, 16000.0, 20000.0,
];

/// Centre frequencies for a supported band count (5, 10, 15 or 31).
pub fn centres(count: u32) -> Option<&'static [f32]> {
    match count {
        5 => Some(&C5),
        10 => Some(&C10),
        15 => Some(&C15),
        31 => Some(&C31),
        _ => None,
    }
}

fn end_slope(h0: f64, h1: f64, s0: f64, s1: f64) -> f64 {
    let d = ((2.0 * h0 + h1) * s0 - h0 * s1) / (h0 + h1);
    if d.signum() != s0.signum() || d == 0.0 {
        0.0
    } else if s0.signum() != s1.signum() && d.abs() > 3.0 * s0.abs() {
        3.0 * s0
    } else {
        d
    }
}

/// Fritsch-Carlson slopes for nodes (x, y).
fn pchip_slopes(x: &[f64], y: &[f64]) -> Vec<f64> {
    let n = x.len();
    let mut d = vec![0.0; n];
    if n < 2 {
        return d;
    }
    let h: Vec<f64> = x.windows(2).map(|w| w[1] - w[0]).collect();
    let s: Vec<f64> = (0..n - 1).map(|i| (y[i + 1] - y[i]) / h[i]).collect();
    if n == 2 {
        d[0] = s[0];
        d[1] = s[0];
        return d;
    }
    for i in 1..n - 1 {
        if s[i - 1] * s[i] > 0.0 {
            let (w1, w2) = (2.0 * h[i] + h[i - 1], h[i] + 2.0 * h[i - 1]);
            d[i] = (w1 + w2) / (w1 / s[i - 1] + w2 / s[i]);
        }
    }
    d[0] = end_slope(h[0], h[1], s[0], s[1]);
    d[n - 1] = end_slope(h[n - 2], h[n - 3], s[n - 2], s[n - 3]);
    d
}

/// Target curve in dB at `f_hz` for sliders `gains_db` placed at `centres`.
/// Flat (the end gain) below the first and above the last centre.
pub fn target_db(centres: &[f32], gains_db: &[f32], f_hz: f32) -> f32 {
    let n = centres.len().min(gains_db.len());
    if n == 0 {
        return 0.0;
    }
    let x: Vec<f64> = centres[..n].iter().map(|&c| (c as f64).log2()).collect();
    let y: Vec<f64> = gains_db[..n].iter().map(|&g| g as f64).collect();
    let xf = (f_hz.max(f32::MIN_POSITIVE) as f64).log2();
    if n == 1 || xf <= x[0] {
        return y[0] as f32;
    }
    if xf >= x[n - 1] {
        return y[n - 1] as f32;
    }
    let d = pchip_slopes(&x, &y);
    let i = x.partition_point(|&v| v <= xf).min(n - 1) - 1;
    let h = x[i + 1] - x[i];
    let t = (xf - x[i]) / h;
    let (t2, t3) = (t * t, t * t * t);
    let v = (2.0 * t3 - 3.0 * t2 + 1.0) * y[i]
        + (t3 - 2.0 * t2 + t) * h * d[i]
        + (-2.0 * t3 + 3.0 * t2) * y[i + 1]
        + (t3 - t2) * h * d[i + 1];
    v as f32
}

/// Gains for `new_count` bands that reproduce the old curve at the new centres. Returns an
/// all-zero vector of the new size if either count is unsupported or the old gains are short.
pub fn resample(old_count: u32, old_gains: &[f32], new_count: u32) -> Vec<f32> {
    let (Some(old_c), Some(new_c)) = (centres(old_count), centres(new_count)) else {
        return vec![0.0; new_count as usize];
    };
    if old_gains.len() != old_c.len() {
        return vec![0.0; new_c.len()];
    }
    new_c.iter().map(|&f| target_db(old_c, old_gains, f)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const COUNTS: [u32; 4] = [5, 10, 15, 31];

    /// Small deterministic LCG, uniform in [-range, range].
    struct Lcg(u64);
    impl Lcg {
        fn next(&mut self, range: f32) -> f32 {
            self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let u = ((self.0 >> 33) as f32) / ((1u64 << 31) as f32);
            (u * 2.0 - 1.0) * range
        }
    }

    fn random_gains(rng: &mut Lcg, n: usize) -> Vec<f32> {
        (0..n).map(|_| rng.next(24.0)).collect()
    }

    #[test]
    fn tables_have_the_right_sizes_and_values() {
        for count in COUNTS {
            let c = centres(count).unwrap();
            assert_eq!(c.len(), count as usize);
            assert!(c.windows(2).all(|w| w[0] < w[1]), "ascending for {count}");
        }
        assert_eq!(centres(5).unwrap(), &[63.0, 250.0, 1000.0, 4000.0, 16000.0]);
        assert_eq!(centres(10).unwrap()[0], 31.5);
        assert_eq!(centres(15).unwrap().len(), 15);
        assert_eq!(centres(31).unwrap().len(), 31);
        assert_eq!(centres(31).unwrap()[0], 20.0);
        assert_eq!(centres(31).unwrap()[30], 20000.0);
    }

    #[test]
    fn rejects_unsupported_counts() {
        for count in [0, 1, 4, 6, 9, 11, 16, 30, 32, 100] {
            assert!(centres(count).is_none(), "{count}");
        }
    }

    #[test]
    fn passes_through_every_node() {
        let mut rng = Lcg(1);
        for count in COUNTS {
            let c = centres(count).unwrap();
            for _ in 0..20 {
                let g = random_gains(&mut rng, c.len());
                for (i, &f) in c.iter().enumerate() {
                    let v = target_db(c, &g, f);
                    assert!((v - g[i]).abs() < 1e-4, "count {count} node {i}: {v} vs {}", g[i]);
                }
            }
        }
    }

    fn assert_no_overshoot(c: &[f32], g: &[f32]) {
        for i in 0..c.len() - 1 {
            let (lo, hi) = (g[i].min(g[i + 1]), g[i].max(g[i + 1]));
            let (x0, x1) = ((c[i] as f64).log2(), (c[i + 1] as f64).log2());
            for k in 0..=50 {
                let f = 2f64.powf(x0 + (x1 - x0) * k as f64 / 50.0) as f32;
                let v = target_db(c, g, f);
                assert!(v >= lo - 1e-3 && v <= hi + 1e-3, "{v} outside [{lo},{hi}] at {f} Hz");
            }
        }
    }

    #[test]
    fn never_overshoots_between_neighbours() {
        let mut rng = Lcg(7);
        for count in COUNTS {
            let c = centres(count).unwrap();
            for _ in 0..20 {
                assert_no_overshoot(c, &random_gains(&mut rng, c.len()));
            }
            let mut spike = vec![0.0; c.len()];
            spike[c.len() / 2] = 12.0;
            assert_no_overshoot(c, &spike);
            let alt: Vec<f32> = (0..c.len()).map(|i| if i % 2 == 0 { 12.0 } else { -12.0 }).collect();
            assert_no_overshoot(c, &alt);
        }
    }

    #[test]
    fn flat_beyond_the_end_bands() {
        let mut rng = Lcg(3);
        for count in COUNTS {
            let c = centres(count).unwrap();
            let g = random_gains(&mut rng, c.len());
            assert_eq!(target_db(c, &g, 1.0), g[0]);
            assert_eq!(target_db(c, &g, 10.0), g[0]);
            assert_eq!(target_db(c, &g, 24000.0), g[c.len() - 1]);
            assert_eq!(target_db(c, &g, 20000.0).is_finite(), true);
            if c[c.len() - 1] < 20000.0 {
                assert_eq!(target_db(c, &g, 20000.0), g[c.len() - 1]);
            }
        }
    }

    #[test]
    fn slope_is_continuous_across_nodes() {
        let mut rng = Lcg(11);
        for count in COUNTS {
            let c = centres(count).unwrap();
            let g = random_gains(&mut rng, c.len());
            let eps = 1e-4f64;
            for i in 1..c.len() - 1 {
                let x = (c[i] as f64).log2();
                let at = |dx: f64| target_db(c, &g, 2f64.powf(x + dx) as f32) as f64;
                let left = (at(0.0) - at(-eps)) / eps;
                let right = (at(eps) - at(0.0)) / eps;
                // f32 evaluation noise dominates at this step, so the tolerance is in dB/octave.
                assert!((left - right).abs() < 0.5, "count {count} node {i}: {left} vs {right}");
            }
        }
    }

    #[test]
    fn resample_keeps_a_flat_curve_flat() {
        for from in COUNTS {
            for to in COUNTS {
                let flat = vec![3.5; centres(from).unwrap().len()];
                let out = resample(from, &flat, to);
                assert_eq!(out.len(), to as usize);
                assert!(out.iter().all(|&v| (v - 3.5).abs() < 1e-5));
            }
        }
    }

    #[test]
    fn resample_follows_the_old_curve() {
        let mut rng = Lcg(5);
        let old = random_gains(&mut rng, 31);
        let out = resample(31, &old, 10);
        for (i, &f) in centres(10).unwrap().iter().enumerate() {
            assert_eq!(out[i], target_db(centres(31).unwrap(), &old, f));
        }
    }

    #[test]
    fn resample_nested_5_in_10() {
        let mut rng = Lcg(9);
        let ten = random_gains(&mut rng, 10);
        let five = resample(10, &ten, 5);
        for (k, idx) in [1usize, 3, 5, 7, 9].into_iter().enumerate() {
            assert!((five[k] - ten[idx]).abs() < 1e-4, "{k}");
        }
    }

    #[test]
    fn resample_with_unsupported_count_is_zeroed() {
        assert_eq!(resample(10, &[0.0; 10], 7), vec![0.0; 7]);
    }
}
