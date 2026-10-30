use std::sync::Arc;

use eq_core::error::EqError;
use eq_core::graphic;
use eq_core::headroom::{self, LimiterParams};
use eq_core::model::{Band, EqMode, FilterKind};
use eq_core::EqCore;

fn peak(freq: f32, gain: f32, q: f32) -> Band {
    Band { kind: FilterKind::Peak, freq, gain_db: gain, q, enabled: true }
}

fn parametric_with_lone_peak() -> Arc<EqCore> {
    let core = EqCore::new();
    core.set_mode(EqMode::Parametric);
    let n = core.snapshot().bands.len() as u32;
    for i in 0..n {
        let mut b = core.snapshot().bands[i as usize];
        b.enabled = false;
        core.set_band(i, b).unwrap();
    }
    core.set_band(0, peak(1000.0, 6.0, 1.0)).unwrap();
    core
}

#[test]
fn curve_flat_state_is_zero_in_both_modes() {
    let core = EqCore::new();
    for mode in [EqMode::Graphic, EqMode::Parametric] {
        core.set_mode(mode);
        if mode == EqMode::Parametric {
            for i in 0..core.snapshot().bands.len() {
                let mut b = core.snapshot().bands[i];
                b.gain_db = 0.0;
                core.set_band(i as u32, b).unwrap();
            }
        }
        let c = core.response_curve(256, 48000, 85.3).unwrap();
        assert_eq!(c.freqs_hz.len(), 256);
        assert!(c.ideal_db.iter().all(|&v| v.abs() < 1e-4), "{mode:?}");
        assert!(c.achieved_db.iter().all(|&v| v.abs() < 1e-4), "{mode:?}");
    }
}

#[test]
fn curve_grid_is_log_spaced_20_to_20k() {
    let c = EqCore::new().response_curve(512, 48000, 85.3).unwrap();
    assert_eq!(c.freqs_hz.len(), 512);
    assert_eq!(c.ideal_db.len(), 512);
    assert_eq!(c.achieved_db.len(), 512);
    assert_eq!(c.freqs_hz[0], 20.0);
    assert_eq!(*c.freqs_hz.last().unwrap(), 20_000.0);
    assert!(c.freqs_hz.windows(2).all(|w| w[1] > w[0]));
    let c2 = EqCore::new().response_curve(2, 48000, 85.3).unwrap();
    assert_eq!(c2.freqs_hz, vec![20.0, 20_000.0]);
}

#[test]
fn lone_peak_ideal_is_six_db_and_achieved_follows_the_shape() {
    let core = parametric_with_lone_peak();
    let c = core.response_curve(2048, 48000, 85.3).unwrap();
    let (imax, vmax) = c
        .ideal_db
        .iter()
        .enumerate()
        .fold((0, f32::MIN), |a, (i, &v)| if v > a.1 { (i, v) } else { a });
    assert!((vmax - 6.0).abs() < 1e-3, "{vmax}");
    assert!((c.freqs_hz[imax] - 1000.0).abs() < 10.0);
    // Achieved follows the same shape: peak near 6 dB, rising then falling, and flat far away.
    let amax = c.achieved_db.iter().cloned().fold(f32::MIN, f32::max);
    assert!((amax - 6.0).abs() < 1.0, "{amax}");
    assert!(c.achieved_db[0].abs() < 0.2);
    assert!(c.achieved_db[2047].abs() < 0.2);
    assert!(c.ideal_db[0].abs() < 0.5);
}

#[test]
fn disabled_bands_contribute_nothing_and_preamp_is_excluded() {
    let core = parametric_with_lone_peak();
    core.set_preamp(-9.0).unwrap();
    let with = core.response_curve(300, 48000, 85.3).unwrap();
    let mut b = core.snapshot().bands[0];
    b.enabled = false;
    core.set_band(0, b).unwrap();
    let off = core.response_curve(300, 48000, 85.3).unwrap();
    assert!(off.ideal_db.iter().all(|&v| v == 0.0));
    assert!(with.ideal_db.iter().cloned().fold(f32::MIN, f32::max) > 5.9);
}

#[test]
fn graphic_mode_curve_follows_the_sliders() {
    let core = EqCore::new();
    core.set_graphic_gain(5, 8.0).unwrap(); // 10 bands: index 5 is 1 kHz
    let snap = core.snapshot();
    let centre = snap.graphic_centres_hz[5];
    let c = core.response_curve(2048, 48000, 85.3).unwrap();
    let (i, v) = c
        .ideal_db
        .iter()
        .enumerate()
        .fold((0, f32::MIN), |a, (i, &v)| if v > a.1 { (i, v) } else { a });
    assert!((v - 8.0).abs() < 0.1, "{v}");
    assert!((c.freqs_hz[i] / centre - 1.0).abs() < 0.05);
}

#[test]
fn engine_params_invariants() {
    let core = parametric_with_lone_peak();
    core.set_preamp(-2.5).unwrap();
    let p = core.engine_params(48000, 85.3).unwrap();
    assert_eq!(p.cutoffs_hz.len(), p.gains_db.len());
    assert!(!p.cutoffs_hz.is_empty());
    assert!(p.cutoffs_hz.windows(2).all(|w| w[1] > w[0]));
    assert!((p.cutoffs_hz.last().unwrap() - 24000.0).abs() < 0.5);
    assert!(p.gains_db.iter().all(|g| g.is_finite() && (-60.0..=30.0).contains(g)));
    assert_eq!(p.input_gain_db, -2.5);
    assert_eq!(p.limiter, LimiterParams::default());
    assert!(p.limiter_enabled && !p.bypass);
    assert_eq!(p.block_size, 4096);
    assert_eq!((p.sample_rate, p.frame_duration_ms), (48000, 85.3));
    // Suggested preamp matches headroom of the achieved peak.
    assert_eq!(p.suggested_preamp_db, headroom::suggested_preamp_db(p.peak_db as f64) as f32);
    assert!(p.peak_db > 3.0 && p.suggested_preamp_db < 0.0);
    assert!((p.headroom_after_preamp_db - (-(p.peak_db + p.input_gain_db))).abs() < 1e-4);
}

#[test]
fn engine_params_follow_flags_and_still_compute_gains_in_bypass() {
    let core = parametric_with_lone_peak();
    core.set_bypass(true);
    core.set_limiter_enabled(false);
    let p = core.engine_params(48000, 85.3).unwrap();
    assert!(p.bypass && !p.limiter_enabled);
    assert!(p.gains_db.iter().cloned().fold(f32::MIN, f32::max) > 3.0);
}

#[test]
fn layout_id_is_stable_across_edits_and_sensitive_to_layout() {
    let core = EqCore::new();
    let a = core.engine_params(48000, 85.3).unwrap().layout_id;
    core.set_graphic_gain(3, 5.0).unwrap();
    let b = core.engine_params(48000, 85.3).unwrap().layout_id;
    assert_eq!(a, b);
    assert_ne!(a, core.engine_params(44100, 85.3).unwrap().layout_id);
    assert_ne!(a, core.engine_params(48000, 170.6).unwrap().layout_id);
}

#[test]
fn errors_are_typed_not_panics() {
    let core = EqCore::new();
    assert_eq!(core.set_band(99, peak(1000.0, 0.0, 1.0)), Err(EqError::OutOfRange));
    assert_eq!(core.set_band(0, peak(f32::NAN, 0.0, 1.0)), Err(EqError::InvalidValue));
    assert_eq!(core.set_graphic_gain(0, f32::INFINITY), Err(EqError::InvalidValue));
    assert_eq!(core.set_graphic_gain(50, 1.0), Err(EqError::OutOfRange));
    assert_eq!(core.set_graphic_band_count(12), Err(EqError::InvalidValue));
    assert_eq!(core.set_preamp(f32::NAN), Err(EqError::InvalidValue));
    assert_eq!(core.remove_band(99), Err(EqError::OutOfRange));
    assert_eq!(core.set_frame_duration_ms(0.0), Err(EqError::InvalidValue));
    assert!(matches!(core.response_curve(1, 48000, 85.3), Err(EqError::InvalidValue)));
    assert!(matches!(core.response_curve(2049, 48000, 85.3), Err(EqError::InvalidValue)));
    assert!(matches!(core.response_curve(100, 100, 85.3), Err(EqError::InvalidValue)));
    assert!(matches!(core.response_curve(100, 48000, f32::NAN), Err(EqError::InvalidValue)));
    assert!(matches!(core.engine_params(1_000_000, 85.3), Err(EqError::InvalidValue)));
    assert!(matches!(core.engine_params(48000, 1.0), Err(EqError::InvalidValue)));
    assert!(!core.snapshot().can_undo);
}

#[test]
fn seventeenth_band_fails_and_add_remove_flow_works() {
    let core = EqCore::new();
    let start = core.snapshot().bands.len() as u32;
    let mut last = 0;
    for k in 0..(16 - start) {
        last = core.add_band().unwrap();
        assert_eq!(last, start + k);
    }
    assert_eq!(last, 15);
    assert_eq!(core.add_band(), Err(EqError::TooManyBands));
    assert_eq!(core.snapshot().bands.len(), 16);
    core.remove_band(15).unwrap();
    assert_eq!(core.snapshot().bands.len(), 15);
    assert!(core.undo());
    assert_eq!(core.snapshot().bands.len(), 16);
}

#[test]
fn mode_switch_keeps_both_states_and_is_not_undoable() {
    let core = EqCore::new();
    core.set_graphic_gain(2, 4.0).unwrap();
    core.set_band(0, peak(500.0, 3.0, 2.0)).unwrap();
    core.set_mode(EqMode::Parametric);
    core.set_mode(EqMode::Graphic);
    let s = core.snapshot();
    assert_eq!(s.graphic_gains_db[2], 4.0);
    assert_eq!(s.bands[0].freq, 500.0);
    assert_eq!(s.mode, EqMode::Graphic);
    // Two edits made: exactly two undo steps.
    assert!(core.undo());
    assert!(core.undo());
    assert!(!core.undo());
}

#[test]
fn graphic_count_change_resamples_in_one_undoable_step() {
    let flat = EqCore::new();
    flat.set_graphic_band_count(5).unwrap();
    let s = flat.snapshot();
    assert_eq!(s.graphic_band_count, 5);
    assert!(s.graphic_gains_db.iter().all(|&g| g == 0.0));
    assert_eq!(s.graphic_centres_hz.len(), 5);

    let core = EqCore::new();
    core.set_graphic_gain(4, 6.0).unwrap();
    let before = core.snapshot();
    core.set_graphic_band_count(5).unwrap();
    assert_eq!(core.snapshot().graphic_gains_db.len(), 5);
    assert!(core.undo());
    let back = core.snapshot();
    assert_eq!(back.graphic_band_count, 10);
    assert_eq!(back.graphic_gains_db, before.graphic_gains_db);
}

#[test]
fn a_drag_through_the_public_api_is_one_undo_and_redo_restores() {
    let core = EqCore::new();
    core.begin_edit();
    for i in 1..=30 {
        core.set_graphic_gain(3, i as f32 * 0.3).unwrap();
    }
    core.end_edit();
    let end = core.snapshot();
    assert!(end.can_undo && !end.can_redo);
    assert!(core.undo());
    let s = core.snapshot();
    assert_eq!(s.graphic_gains_db[3], 0.0);
    assert!(!s.can_undo && s.can_redo);
    assert!(core.redo());
    assert_eq!(core.snapshot().graphic_gains_db, end.graphic_gains_db);
    assert!(!core.redo());
}

#[test]
fn save_then_load_through_eqcore_equals_snapshot() {
    let core = EqCore::new();
    core.set_mode(EqMode::Parametric);
    core.set_band(1, peak(2000.0, -4.0, 3.0)).unwrap();
    core.set_graphic_band_count(31).unwrap();
    core.set_graphic_gain(7, 3.5).unwrap();
    core.set_preamp(-1.5).unwrap();
    core.set_bypass(true);
    core.set_limiter_enabled(false);
    core.set_frame_duration_ms(170.6).unwrap();
    let path = std::env::temp_dir()
        .join(format!("eqcore-apiflow-{}.json", std::process::id()))
        .to_string_lossy()
        .into_owned();
    core.save(path.clone()).unwrap();
    let loaded = EqCore::load(path.clone()).unwrap();
    let mut want = core.snapshot();
    want.can_undo = false; // history is not persisted
    want.can_redo = false;
    assert_eq!(loaded.snapshot(), want);
    let _ = std::fs::remove_file(&path);
    assert_eq!(EqCore::load_or_default(path.clone()).snapshot(), EqCore::new().snapshot());
    assert!(matches!(EqCore::load(path), Err(EqError::Io(_))));
}

#[test]
fn snapshot_centres_match_graphic_centres() {
    let core = EqCore::new();
    for count in [5u32, 10, 15, 31] {
        core.set_graphic_band_count(count).unwrap();
        let want: Vec<f32> = graphic::centres(count).unwrap().to_vec();
        assert_eq!(core.snapshot().graphic_centres_hz, want);
    }
}

#[test]
fn concurrency_smoke_four_threads() {
    let core = EqCore::new();
    let handles: Vec<_> = (0..4)
        .map(|t| {
            let c = Arc::clone(&core);
            std::thread::spawn(move || {
                for i in 0..500 {
                    c.set_graphic_gain((i + t) % 10, ((i % 20) as f32) - 10.0).unwrap();
                    let curve = c.response_curve(64, 48000, 85.3).unwrap();
                    assert_eq!(curve.freqs_hz.len(), 64);
                }
            })
        })
        .collect();
    for h in handles {
        h.join().unwrap();
    }
    assert_eq!(core.snapshot().graphic_gains_db.len(), 10);
}

#[test]
fn reset_bands_through_the_object_is_one_undo_step() {
    let core = EqCore::new();
    let defaults = core.snapshot().bands;
    core.set_band(0, peak(5000.0, 9.0, 1.0)).unwrap();
    let edited = core.snapshot().bands;
    core.reset_bands();
    assert_eq!(core.snapshot().bands, defaults);
    assert!(core.undo());
    assert_eq!(core.snapshot().bands, edited);
}

#[test]
fn reset_bands_inside_a_gesture_is_one_step() {
    let core = EqCore::new();
    core.set_band(0, peak(5000.0, 9.0, 1.0)).unwrap();
    let edited = core.snapshot().bands;
    core.begin_edit();
    core.reset_bands();
    core.end_edit();
    assert!(core.undo());
    assert_eq!(core.snapshot().bands, edited);
}

#[test]
fn band_response_db_through_the_object() {
    let core = parametric_with_lone_peak();
    let r = core.band_response_db(0, 64, 48_000).unwrap();
    assert_eq!(r.len(), 64);
    assert_eq!(core.band_response_db(99, 64, 48_000), Err(EqError::OutOfRange));
}

#[test]
fn engine_band_cap_tracer() {
    let core = EqCore::new();
    let full = core.engine_params(48000, 85.3).unwrap();
    core.set_engine_band_cap(Some(64));
    let capped = core.engine_params(48000, 85.3).unwrap();
    assert!(capped.cutoffs_hz.len() <= 64, "{}", capped.cutoffs_hz.len());
    assert_ne!(capped.layout_id, full.layout_id);
}

#[test]
fn engine_band_cap_curve_stays_honest_and_same_length() {
    let core = parametric_with_lone_peak();
    let full = core.response_curve(256, 48000, 85.3).unwrap();
    core.set_engine_band_cap(Some(64));
    let capped = core.response_curve(256, 48000, 85.3).unwrap();
    assert_eq!(capped.freqs_hz.len(), full.freqs_hz.len());
    assert_eq!(capped.ideal_db, full.ideal_db);
    assert!(capped.achieved_db.iter().all(|v| v.is_finite()));
    let worst = capped
        .achieved_db
        .iter()
        .zip(&capped.ideal_db)
        .map(|(a, i)| (a - i).abs())
        .fold(0.0f32, f32::max);
    assert!(worst.is_finite() && worst < 12.0, "worst deviation {worst}");
}

#[test]
fn engine_band_cap_clearing_restores_original_output_exactly() {
    let core = parametric_with_lone_peak();
    let before = core.engine_params(48000, 85.3).unwrap();
    core.set_engine_band_cap(Some(32));
    let capped = core.engine_params(48000, 85.3).unwrap();
    assert!(capped.cutoffs_hz.len() <= 32);
    assert_ne!(capped.layout_id, before.layout_id);
    core.set_engine_band_cap(None);
    let after = core.engine_params(48000, 85.3).unwrap();
    assert_eq!(after.layout_id, before.layout_id);
    assert_eq!(after.cutoffs_hz, before.cutoffs_hz);
    assert_eq!(after.gains_db, before.gains_db);
}

#[test]
fn engine_band_cap_non_binding_keeps_layout_id() {
    let core = EqCore::new();
    let before = core.engine_params(48000, 85.3).unwrap();
    core.set_engine_band_cap(Some(10_000));
    let after = core.engine_params(48000, 85.3).unwrap();
    assert_eq!(after.layout_id, before.layout_id);
    assert_eq!(after.gains_db, before.gains_db);
}

#[test]
fn engine_band_cap_lone_peak_gains_are_finite_and_bounded() {
    let core = parametric_with_lone_peak();
    core.set_engine_band_cap(Some(64));
    let e = core.engine_params(48000, 85.3).unwrap();
    assert!(e.peak_db.is_finite());
    assert_eq!(e.cutoffs_hz.len(), e.gains_db.len());
    assert!(e.gains_db.iter().all(|g| (-60.0..=30.0).contains(g)), "{:?}", e.gains_db);
}

#[test]
fn engine_band_cap_is_untouched_by_undo_redo_and_not_saved() {
    let core = EqCore::new();
    core.set_engine_band_cap(Some(64));
    core.set_preamp(-2.0).unwrap();
    assert!(core.undo());
    assert!(core.engine_params(48000, 85.3).unwrap().cutoffs_hz.len() <= 64);
    assert!(core.redo());
    assert!(core.engine_params(48000, 85.3).unwrap().cutoffs_hz.len() <= 64);

    let path = std::env::temp_dir()
        .join(format!("eqcore-bandcap-{}.json", std::process::id()))
        .to_string_lossy()
        .into_owned();
    core.save(path.clone()).unwrap();
    let loaded = EqCore::load(path.clone()).unwrap();
    let _ = std::fs::remove_file(&path);
    let want = EqCore::new().engine_params(48000, 85.3).unwrap();
    let got = loaded.engine_params(48000, 85.3).unwrap();
    assert_eq!(got.layout_id, want.layout_id);
    assert!(got.cutoffs_hz.len() > 64);
}

// ---------------------------------------------------------------- tone and balance

fn ideal_at(c: &eq_core::api::CurveData, hz: f32) -> f32 {
    let i = c
        .freqs_hz
        .iter()
        .enumerate()
        .min_by(|a, b| (a.1 - hz).abs().partial_cmp(&(b.1 - hz).abs()).unwrap())
        .unwrap()
        .0;
    c.ideal_db[i]
}

#[test]
fn tone_tracer() {
    let flat = EqCore::new();
    let core = EqCore::new();
    core.set_bass(12.0).unwrap();
    assert_eq!(core.snapshot().bass_db, 12.0);
    let c = core.response_curve(512, 48000, 85.3).unwrap();
    let f = flat.response_curve(512, 48000, 85.3).unwrap();
    assert!(ideal_at(&c, 50.0) > 6.0, "{}", ideal_at(&c, 50.0));
    assert!(ideal_at(&c, 20.0) > 10.0);
    assert!(ideal_at(&c, 8000.0).abs() < 0.5);
    assert_ne!(c.achieved_db, f.achieved_db);
    let e = core.engine_params(48000, 85.3).unwrap();
    let e0 = flat.engine_params(48000, 85.3).unwrap();
    assert_eq!(e0.peak_db, 0.0);
    assert!(e.peak_db > 9.0, "{}", e.peak_db);
    assert!(e.suggested_preamp_db < -9.0, "{}", e.suggested_preamp_db);
    assert!(e.suggested_preamp_db < e0.suggested_preamp_db);
    // Treble moves the high end.
    let t = EqCore::new();
    t.set_treble(-12.0).unwrap();
    let tc = t.response_curve(512, 48000, 85.3).unwrap();
    assert!(ideal_at(&tc, 18000.0) < -8.0);
    assert!(ideal_at(&tc, 200.0).abs() < 0.5);
}

#[test]
fn zero_tone_is_bit_identical() {
    let fresh = EqCore::new();
    let core = EqCore::new();
    core.set_bass(5.0).unwrap();
    core.set_treble(-3.0).unwrap();
    core.set_bass(0.0).unwrap();
    core.set_treble(0.0).unwrap();
    for mode in [EqMode::Graphic, EqMode::Parametric] {
        fresh.set_mode(mode);
        core.set_mode(mode);
        let a = fresh.engine_params(48000, 85.3).unwrap();
        let b = core.engine_params(48000, 85.3).unwrap();
        assert_eq!(a.gains_db, b.gains_db);
        assert_eq!(a.layout_id, b.layout_id);
        assert_eq!(a.peak_db, b.peak_db);
        assert_eq!(a.suggested_preamp_db, b.suggested_preamp_db);
        assert_eq!(
            fresh.response_curve(256, 48000, 85.3).unwrap(),
            core.response_curve(256, 48000, 85.3).unwrap()
        );
    }
}

#[test]
fn tone_setters_clamp_and_reject_non_finite() {
    let core = EqCore::new();
    core.set_bass(99.0).unwrap();
    core.set_treble(-99.0).unwrap();
    let s = core.snapshot();
    assert_eq!((s.bass_db, s.treble_db), (12.0, -12.0));
    let before = core.snapshot();
    for bad in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        assert_eq!(core.set_bass(bad), Err(EqError::InvalidValue));
        assert_eq!(core.set_treble(bad), Err(EqError::InvalidValue));
        assert_eq!(core.set_balance(bad), Err(EqError::InvalidValue));
    }
    assert_eq!(core.snapshot(), before);
    let fresh = EqCore::new();
    assert_eq!(fresh.set_bass(f32::NAN), Err(EqError::InvalidValue));
    assert!(!fresh.snapshot().can_undo);
}

#[test]
fn tone_edit_is_undoable() {
    let core = EqCore::new();
    core.set_bass(6.0).unwrap();
    assert!(core.snapshot().can_undo);
    assert!(core.undo());
    assert_eq!(core.snapshot().bass_db, 0.0);
    assert!(core.redo());
    assert_eq!(core.snapshot().bass_db, 6.0);
    core.begin_edit();
    for v in [7.0, 8.0, 9.0] {
        core.set_treble(v).unwrap();
    }
    core.end_edit();
    assert_eq!(core.snapshot().treble_db, 9.0);
    assert!(core.undo());
    assert_eq!(core.snapshot().treble_db, 0.0);
    assert_eq!(core.snapshot().bass_db, 6.0);
}

fn near(a: f32, b: f32, tol: f32) -> bool {
    (a - b).abs() <= tol
}

#[test]
fn balance_attenuates_only_the_quieter_side() {
    let core = EqCore::new();
    core.set_preamp(-2.5).unwrap();
    core.set_balance(0.5).unwrap();
    let e = core.engine_params(48000, 85.3).unwrap();
    assert_eq!(e.input_gain_right_db, -2.5);
    assert!(near(e.input_gain_left_db, -2.5 - 6.0206, 1e-3), "{}", e.input_gain_left_db);
    assert_eq!(e.input_gain_db, -2.5);
}

#[test]
fn balance_sign_convention() {
    let core = EqCore::new();
    core.set_balance(1.0).unwrap();
    let e = core.engine_params(48000, 85.3).unwrap();
    assert_eq!(e.input_gain_right_db, 0.0);
    assert_eq!(e.input_gain_left_db, -60.0);
    core.set_balance(-1.0).unwrap();
    let e = core.engine_params(48000, 85.3).unwrap();
    assert_eq!(e.input_gain_left_db, 0.0);
    assert_eq!(e.input_gain_right_db, -60.0);
    core.set_balance(-0.5).unwrap();
    let e = core.engine_params(48000, 85.3).unwrap();
    assert_eq!(e.input_gain_left_db, 0.0);
    assert!(e.input_gain_right_db < -6.0 && e.input_gain_right_db > -6.1);
}

#[test]
fn balance_floor_and_channel_floor() {
    let core = EqCore::new();
    core.set_preamp(-24.0).unwrap();
    core.set_balance(1.0).unwrap();
    let e = core.engine_params(48000, 85.3).unwrap();
    // The louder side is exactly the preamp; the quieter side is a full 60 dB below it.
    assert_eq!(e.input_gain_right_db, -24.0);
    assert_eq!(e.input_gain_left_db, -24.0 + eq_core::inner::MAX_BALANCE_ATTENUATION_DB);
    assert!(e.input_gain_left_db >= eq_core::inner::MIN_CHANNEL_GAIN_DB);
    for p in [-24.0f32, -12.0, 0.0, 24.0] {
        for b in [-1.0f32, -0.99, -0.5, 0.0, 0.5, 0.99, 1.0] {
            core.set_preamp(p).unwrap();
            core.set_balance(b).unwrap();
            let e = core.engine_params(48000, 85.3).unwrap();
            let (l, r) = (e.input_gain_left_db, e.input_gain_right_db);
            assert!(l.is_finite() && r.is_finite());
            assert!(l >= eq_core::inner::MIN_CHANNEL_GAIN_DB && r >= eq_core::inner::MIN_CHANNEL_GAIN_DB);
            // The louder side is the preamp; the quieter side is never more than 60 dB below it,
            // independent of the preamp.
            assert_eq!(l.max(r), p);
            let rel = l.min(r) - p;
            assert!(rel >= eq_core::inner::MAX_BALANCE_ATTENUATION_DB - 1e-3, "p={p} b={b} rel={rel}");
            if b >= 0.99 {
                assert!(rel <= -40.0, "p={p} b={b} rel={rel}");
            }
        }
    }
}

#[test]
fn balance_snap_and_clamp_and_non_finite() {
    let core = EqCore::new();
    core.set_balance(0.005).unwrap();
    assert_eq!(core.snapshot().balance, 0.0);
    core.set_balance(-0.01).unwrap();
    assert_eq!(core.snapshot().balance, 0.0);
    assert!(!core.snapshot().can_undo);
    core.set_balance(5.0).unwrap();
    assert_eq!(core.snapshot().balance, 1.0);
    core.set_balance(-5.0).unwrap();
    assert_eq!(core.snapshot().balance, -1.0);
    assert_eq!(core.set_balance(f32::NAN), Err(EqError::InvalidValue));
    assert_eq!(core.snapshot().balance, -1.0);
}

#[test]
fn balance_does_not_change_suggested_preamp() {
    let a = EqCore::new();
    let b = EqCore::new();
    for c in [&a, &b] {
        c.set_graphic_gain(3, 9.0).unwrap();
    }
    b.set_balance(0.7).unwrap();
    let ea = a.engine_params(48000, 85.3).unwrap();
    let eb = b.engine_params(48000, 85.3).unwrap();
    assert_eq!(ea.peak_db, eb.peak_db);
    assert_eq!(ea.suggested_preamp_db, eb.suggested_preamp_db);
    assert_eq!(ea.gains_db, eb.gains_db);
}

#[test]
fn balance_centre_equals_preamp() {
    let core = EqCore::new();
    core.set_preamp(-7.25).unwrap();
    let e = core.engine_params(48000, 85.3).unwrap();
    assert_eq!(e.input_gain_left_db, -7.25);
    assert_eq!(e.input_gain_right_db, -7.25);
    assert_eq!(e.input_gain_db, -7.25);
}

#[test]
fn balance_is_undoable() {
    let core = EqCore::new();
    core.set_balance(0.4).unwrap();
    assert!(core.undo());
    assert_eq!(core.snapshot().balance, 0.0);
    assert!(core.redo());
    assert_eq!(core.snapshot().balance, 0.4);
    core.begin_edit();
    for v in [0.5, 0.6, 0.7] {
        core.set_balance(v).unwrap();
    }
    core.end_edit();
    assert!(core.undo());
    assert_eq!(core.snapshot().balance, 0.4);
}

#[test]
fn reset_bands_leaves_tone_and_balance_alone() {
    let core = EqCore::new();
    core.set_bass(4.0).unwrap();
    core.set_treble(-2.0).unwrap();
    core.set_balance(0.3).unwrap();
    core.set_preamp(-3.0).unwrap();
    core.reset_bands();
    let s = core.snapshot();
    assert_eq!((s.bass_db, s.treble_db, s.balance, s.preamp_db), (4.0, -2.0, 0.3, 0.0));
}

#[test]
fn tone_and_balance_do_not_clear_bypass_flag() {
    let core = EqCore::new();
    core.set_bass(6.0).unwrap();
    core.set_balance(0.5).unwrap();
    core.set_bypass(true);
    let e = core.engine_params(48000, 85.3).unwrap();
    assert!(e.bypass);
}
