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
