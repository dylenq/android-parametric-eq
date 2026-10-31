use eq_core::error::EqError;
use eq_core::model::{EqMode, FilterKind};
use eq_core::EqCore;

fn core() -> std::sync::Arc<EqCore> {
    EqCore::new()
}

#[test]
fn builtins_listed_in_order_in_snapshot() {
    let s = core().snapshot();
    let names: Vec<&str> = s.presets.iter().map(|p| p.name.as_str()).collect();
    assert_eq!(names, ["Flat", "Bass", "Treble", "Vocal"]);
    assert!(s.presets.iter().all(|p| p.builtin));
    assert_eq!(s.presets[1].key, "builtin:Bass");
    assert_eq!((s.active_preset_key, s.active_preset_name, s.preset_modified), (None, None, false));
}

#[test]
fn apply_builtin_switches_mode_and_undo_restores_everything() {
    let c = core();
    c.set_mode(EqMode::Parametric);
    c.set_bass(3.0).unwrap();
    c.set_balance(0.5).unwrap();
    c.set_preamp(2.0).unwrap();
    c.set_graphic_gain(0, -4.0).unwrap();
    let before = c.snapshot();
    assert!(c.apply_preset("builtin:Bass".into()).unwrap());
    let s = c.snapshot();
    assert_eq!(s.mode, EqMode::Graphic);
    assert_eq!(s.graphic_gains_db, vec![6.0, 5.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!((s.preamp_db, s.bass_db, s.treble_db, s.balance), (-6.0, 0.0, 0.0, 0.0));
    assert_eq!(s.active_preset_key.as_deref(), Some("builtin:Bass"));
    assert_eq!(s.active_preset_name.as_deref(), Some("Bass"));
    assert!(!s.preset_modified);
    assert!(s.can_undo);
    assert!(c.undo());
    assert_eq!(c.snapshot(), before_with_flags(before, c.snapshot()));
}

/// Undo/redo flags legitimately differ; compare everything else.
fn before_with_flags(mut before: eq_core::api::Snapshot, now: eq_core::api::Snapshot) -> eq_core::api::Snapshot {
    before.can_undo = now.can_undo;
    before.can_redo = now.can_redo;
    before
}

#[test]
fn apply_redo_restores_mode_and_marker() {
    let c = core();
    c.set_mode(EqMode::Parametric);
    c.apply_preset("builtin:Treble".into()).unwrap();
    assert!(c.undo());
    let s = c.snapshot();
    assert_eq!((s.mode, s.active_preset_key.clone()), (EqMode::Parametric, None));
    assert!(c.redo());
    let s = c.snapshot();
    assert_eq!(s.mode, EqMode::Graphic);
    assert_eq!(s.active_preset_key.as_deref(), Some("builtin:Treble"));
}

#[test]
fn undo_of_ordinary_edit_keeps_mode() {
    let c = core();
    c.set_preamp(-3.0).unwrap(); // edit made in Graphic
    c.set_mode(EqMode::Parametric);
    assert!(c.undo());
    let s = c.snapshot();
    assert_eq!(s.mode, EqMode::Parametric);
    assert_eq!(s.preamp_db, 0.0);
    assert!(c.redo());
    assert_eq!(c.snapshot().mode, EqMode::Parametric);
    assert_eq!(c.snapshot().preamp_db, -3.0);
}

#[test]
fn apply_same_twice_records_one_step() {
    let c = core();
    assert!(c.apply_preset("builtin:Vocal".into()).unwrap());
    assert!(!c.apply_preset("builtin:Vocal".into()).unwrap());
    assert!(c.undo());
    assert!(!c.snapshot().can_undo);
    assert_eq!(c.snapshot().active_preset_key, None);
}

#[test]
fn apply_unknown_key_is_not_found_and_changes_nothing() {
    let c = core();
    c.set_preamp(1.0).unwrap();
    let before = c.snapshot();
    for k in ["builtin:Nope", "user:Nope", "Bass", ""] {
        assert_eq!(c.apply_preset(k.into()), Err(EqError::NotFound), "{k}");
    }
    assert_eq!(c.snapshot(), before);
}

#[test]
fn apply_leaves_listening_settings_alone() {
    let c = core();
    c.set_bypass(true);
    c.set_limiter_enabled(false);
    c.set_frame_duration_ms(50.0).unwrap();
    c.apply_preset("builtin:Bass".into()).unwrap();
    let s = c.snapshot();
    assert!(s.bypass && !s.limiter_enabled);
    assert_eq!(s.frame_duration_ms, 50.0);
    let _ = FilterKind::Peak;
}

// ---- Task 2: save, rename, delete, names, modified ----

use eq_core::model::Band;

#[test]
fn presets_save_stores_current_state_sets_active_and_is_not_a_step() {
    let c = core();
    c.set_bass(2.0).unwrap();
    c.undo();
    c.redo();
    let before = c.snapshot();
    assert_eq!(c.save_preset("Night".into()).unwrap(), "user:Night");
    let s = c.snapshot();
    assert_eq!(s.active_preset_key.as_deref(), Some("user:Night"));
    assert!(!s.preset_modified);
    assert_eq!((s.can_undo, s.can_redo), (before.can_undo, before.can_redo));
    assert_eq!(s.presets.len(), 5);
    assert!(!s.presets[4].builtin);
}

#[test]
fn presets_save_overwrites_same_name_keeping_stored_name() {
    let c = core();
    c.save_preset("Night".into()).unwrap();
    c.set_preamp(-5.0).unwrap();
    assert_eq!(c.save_preset(" night ".into()).unwrap(), "user:Night");
    let s = c.snapshot();
    assert_eq!(s.presets.len(), 5);
    assert_eq!(s.active_preset_name.as_deref(), Some("Night"));
    assert!(!s.preset_modified);
    c.apply_preset("builtin:Flat".into()).unwrap();
    c.apply_preset("user:Night".into()).unwrap();
    assert_eq!(c.snapshot().preamp_db, -5.0);
}

#[test]
fn presets_save_rejects_builtin_names_and_bad_names() {
    let c = core();
    assert_eq!(c.save_preset("flat".into()), Err(EqError::NameTaken));
    assert_eq!(c.save_preset("  BASS ".into()), Err(EqError::NameTaken));
    for bad in ["", "  ", &"x".repeat(41), "a\nb", "tab\there"] {
        assert_eq!(c.save_preset(bad.to_string()), Err(EqError::InvalidName), "{bad:?}");
    }
    assert!(c.save_preset("x".repeat(40)).is_ok());
    assert!(c.save_preset("é".repeat(40)).is_ok());
    assert_eq!(c.save_preset("é".repeat(41)), Err(EqError::InvalidName));
    assert_eq!(c.save_preset("  padded  ".into()).unwrap(), "user:padded");
}

#[test]
fn presets_save_cap_is_one_hundred_and_overwrite_still_works() {
    let c = core();
    for i in 0..100 {
        c.save_preset(format!("p{i}")).unwrap();
    }
    assert_eq!(c.save_preset("p100".into()), Err(EqError::TooManyPresets));
    assert!(c.save_preset("P5".into()).is_ok());
    assert_eq!(c.snapshot().presets.len(), 104);
    c.delete_preset("user:p0".into()).unwrap();
    assert!(c.save_preset("p100".into()).is_ok());
}

#[test]
fn presets_rename_keeps_active_and_allows_case_only() {
    let c = core();
    c.save_preset("Night".into()).unwrap();
    assert_eq!(c.rename_preset("user:Night".into(), "Day".into()).unwrap(), "user:Day");
    assert_eq!(c.snapshot().active_preset_key.as_deref(), Some("user:Day"));
    assert_eq!(c.rename_preset("user:Day".into(), "day".into()).unwrap(), "user:day");
    assert_eq!(c.snapshot().active_preset_name.as_deref(), Some("day"));
    assert_eq!(c.rename_preset("user:day".into(), "  Day2 ".into()).unwrap(), "user:Day2");
}

#[test]
fn presets_rename_errors() {
    let c = core();
    c.save_preset("A".into()).unwrap();
    c.save_preset("B".into()).unwrap();
    assert_eq!(c.rename_preset("user:A".into(), "b".into()), Err(EqError::NameTaken));
    assert_eq!(c.rename_preset("user:A".into(), "flat".into()), Err(EqError::NameTaken));
    assert_eq!(c.rename_preset("user:A".into(), "".into()), Err(EqError::InvalidName));
    assert_eq!(c.rename_preset("builtin:Flat".into(), "X".into()), Err(EqError::ReadOnly));
    assert_eq!(c.rename_preset("user:Zed".into(), "X".into()), Err(EqError::NotFound));
    assert_eq!(c.rename_preset("junk".into(), "X".into()), Err(EqError::NotFound));
    assert_eq!(c.snapshot().presets.len(), 6);
}

#[test]
fn presets_delete_active_clears_marker_and_keeps_sound() {
    let c = core();
    c.set_preamp(-2.0).unwrap();
    c.save_preset("Keep".into()).unwrap();
    let e0 = c.engine_params(48_000, 85.3).unwrap();
    c.delete_preset("user:Keep".into()).unwrap();
    let s = c.snapshot();
    assert_eq!((s.active_preset_key, s.active_preset_name, s.preset_modified), (None, None, false));
    assert_eq!(c.engine_params(48_000, 85.3).unwrap(), e0);
    assert_eq!(c.delete_preset("user:Keep".into()), Err(EqError::NotFound));
    assert_eq!(c.delete_preset("builtin:Flat".into()), Err(EqError::ReadOnly));
}

#[test]
fn presets_delete_other_keeps_active() {
    let c = core();
    c.save_preset("A".into()).unwrap();
    c.save_preset("B".into()).unwrap();
    c.delete_preset("user:A".into()).unwrap();
    assert_eq!(c.snapshot().active_preset_key.as_deref(), Some("user:B"));
}

#[test]
fn presets_stale_ctx_key_restores_without_marker() {
    let c = core();
    c.save_preset("Gone".into()).unwrap();
    c.apply_preset("builtin:Bass".into()).unwrap();
    c.apply_preset("user:Gone".into()).unwrap(); // step 2: before had marker Bass
    c.apply_preset("builtin:Treble".into()).unwrap(); // step 3: before had marker Gone
    c.delete_preset("user:Gone".into()).unwrap();
    assert!(c.undo());
    assert_eq!(c.snapshot().active_preset_key, None);
    assert!(c.redo());
    assert_eq!(c.snapshot().active_preset_key.as_deref(), Some("builtin:Treble"));
}

#[test]
fn presets_undo_rename_redo_keeps_the_marker_on_the_renamed_preset() {
    let c = core();
    c.save_preset("A".into()).unwrap();
    c.apply_preset("builtin:Bass".into()).unwrap();
    c.apply_preset("user:A".into()).unwrap();
    assert!(c.undo());
    c.rename_preset("user:A".into(), "B".into()).unwrap();
    assert!(c.redo());
    assert_eq!(c.snapshot().active_preset_key.as_deref(), Some("user:B"));
    assert!(!c.snapshot().preset_modified);
}

#[test]
fn presets_delete_then_save_same_name_is_not_picked_up_by_history() {
    let c = core();
    c.save_preset("A".into()).unwrap(); // marker user:A
    c.apply_preset("builtin:Treble".into()).unwrap(); // undo entry carries marker user:A
    c.delete_preset("user:A".into()).unwrap();
    c.save_preset("A".into()).unwrap(); // an unrelated new preset with the old name
    assert!(c.undo());
    assert_eq!(c.snapshot().active_preset_key, None);
}

#[test]
fn presets_apply_mid_gesture_is_its_own_step_with_mode_restored() {
    let c = core();
    c.set_mode(EqMode::Parametric);
    c.begin_edit();
    c.set_preamp(-2.0).unwrap();
    assert!(c.apply_preset("builtin:Bass".into()).unwrap());
    c.end_edit();
    assert_eq!(c.snapshot().mode, EqMode::Graphic);
    assert!(c.undo()); // the apply
    let s = c.snapshot();
    assert_eq!((s.mode, s.active_preset_key.clone()), (EqMode::Parametric, None));
    assert_eq!(s.preamp_db, -2.0);
    assert!(c.undo()); // the drag
    assert_eq!(c.snapshot().preamp_db, 0.0);
    assert_eq!(c.snapshot().mode, EqMode::Parametric);
}

fn modified(c: &EqCore) -> bool {
    c.snapshot().preset_modified
}

#[test]
fn presets_modified_flips_on_each_captured_field() {
    type Edit = Box<dyn Fn(&EqCore)>;
    let mut band_flip: Band = core().snapshot().bands[1];
    band_flip.gain_db += 1.0;
    let mut kind = core().snapshot().bands[1];
    kind.kind = FilterKind::HighShelf;
    let mut en = core().snapshot().bands[1];
    en.enabled = false;
    let mut fq = core().snapshot().bands[1];
    fq.freq *= 1.1;
    let mut q = core().snapshot().bands[1];
    q.q += 0.5;
    let edits: Vec<(&str, Edit)> = vec![
        ("band gain", Box::new(move |c| c.set_band(1, band_flip).unwrap())),
        ("band kind", Box::new(move |c| c.set_band(1, kind).unwrap())),
        ("band enabled", Box::new(move |c| c.set_band(1, en).unwrap())),
        ("band freq", Box::new(move |c| c.set_band(1, fq).unwrap())),
        ("band q", Box::new(move |c| c.set_band(1, q).unwrap())),
        ("add band", Box::new(|c| { c.add_band().unwrap(); })),
        ("graphic gain", Box::new(|c| c.set_graphic_gain(2, 3.0).unwrap())),
        ("band count", Box::new(|c| c.set_graphic_band_count(15).unwrap())),
        ("preamp", Box::new(|c| c.set_preamp(1.0).unwrap())),
        ("bass", Box::new(|c| c.set_bass(1.0).unwrap())),
        ("treble", Box::new(|c| c.set_treble(1.0).unwrap())),
        ("balance", Box::new(|c| c.set_balance(0.4).unwrap())),
    ];
    for (name, edit) in edits {
        let c = core();
        c.apply_preset("builtin:Flat".into()).unwrap();
        c.save_preset("Mine".into()).unwrap();
        assert!(!modified(&c), "{name} start");
        edit(&c);
        assert!(modified(&c), "{name} modified");
        assert!(c.undo());
        assert!(!modified(&c), "{name} undo");
    }
}

#[test]
fn presets_modified_flips_on_mode_change() {
    let c = core();
    c.apply_preset("builtin:Flat".into()).unwrap();
    c.set_mode(EqMode::Parametric);
    assert!(modified(&c));
    c.set_mode(EqMode::Graphic);
    assert!(!modified(&c));
}

#[test]
fn presets_modified_tolerance() {
    let c = core();
    c.apply_preset("builtin:Flat".into()).unwrap();
    c.set_preamp(1e-5).unwrap();
    assert!(!modified(&c));
    c.set_preamp(1e-3).unwrap();
    assert!(modified(&c));
    c.set_preamp(0.0).unwrap();
    let mut b = c.snapshot().bands[3];
    b.freq *= 1.0 + 1e-5;
    c.set_band(3, b).unwrap();
    assert!(!modified(&c));
    b.freq *= 1.001;
    c.set_band(3, b).unwrap();
    assert!(modified(&c));
}

#[test]
fn presets_modified_is_false_without_an_active_preset() {
    let c = core();
    c.set_preamp(3.0).unwrap();
    assert!(!modified(&c));
}

#[test]
fn presets_apply_user_preset_in_parametric_mode_round_trips_all_fields() {
    let c = core();
    c.set_mode(EqMode::Parametric);
    c.set_bass(4.0).unwrap();
    c.set_balance(-0.5).unwrap();
    c.set_preamp(-3.0).unwrap();
    c.save_preset("P".into()).unwrap();
    c.apply_preset("builtin:Flat".into()).unwrap();
    assert_eq!(c.snapshot().mode, EqMode::Graphic);
    c.apply_preset("user:P".into()).unwrap();
    let s = c.snapshot();
    assert_eq!((s.mode, s.bass_db, s.balance, s.preamp_db), (EqMode::Parametric, 4.0, -0.5, -3.0));
    assert!(!s.preset_modified);
}
