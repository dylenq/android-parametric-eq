use std::sync::atomic::{AtomicU32, Ordering};

use eq_core::error::EqError;
use eq_core::inner::Inner;
use eq_core::model::{Band, EqMode, FilterKind};
use eq_core::persist::{self, PersistedState};

static COUNTER: AtomicU32 = AtomicU32::new(0);

fn tmp_path(tag: &str) -> String {
    let n = COUNTER.fetch_add(1, Ordering::SeqCst);
    std::env::temp_dir()
        .join(format!("eqcore-{}-{}-{}.json", std::process::id(), tag, n))
        .to_string_lossy()
        .into_owned()
}

fn cleanup(path: &str) {
    let _ = std::fs::remove_file(path);
    let _ = std::fs::remove_file(format!("{path}.tmp"));
}

fn write(path: &str, bytes: &[u8]) {
    std::fs::write(path, bytes).unwrap();
}

fn state_eq(a: &Inner, b: &Inner) -> bool {
    PersistedState::from_inner(a) == PersistedState::from_inner(b)
}

fn heavy() -> Inner {
    let mut s = Inner::default();
    s.parametric.clear();
    for i in 0..16 {
        let kind = FilterKind::ALL[i % 5];
        let mut b = Band::new(kind, 40.0 * (i as f32 + 1.0) * 1.7);
        b.gain_db = i as f32 - 7.5;
        b.q = 0.5 + i as f32 * 0.25;
        b.enabled = i % 3 != 0;
        s.parametric.push(b);
    }
    s.set_graphic_band_count(31).unwrap();
    for i in 0..31 {
        s.set_graphic_gain(i, i as f32 * 0.75 - 11.0).unwrap();
    }
    s.set_preamp(-3.5).unwrap();
    s.limiter_enabled = false;
    s.bypass = true;
    s.mode = EqMode::Parametric;
    s.set_frame_duration_ms(170.6).unwrap();
    s
}

#[test]
fn round_trip_default_and_heavy() {
    for (tag, s) in [("def", Inner::default()), ("heavy", heavy())] {
        let p = tmp_path(tag);
        persist::save(&p, &s).unwrap();
        let back = persist::load(&p).unwrap();
        assert!(state_eq(&s, &back), "{tag}");
        cleanup(&p);
    }
}

#[test]
fn old_file_with_missing_fields_loads_defaults() {
    let p = tmp_path("old");
    write(&p, br#"{"version":1}"#);
    let back = persist::load(&p).unwrap();
    assert!(state_eq(&back, &Inner::default()));
    cleanup(&p);
}

#[test]
fn garbage_truncated_and_empty_are_corrupt() {
    let full = serde_json::to_vec(&PersistedState::from_inner(&heavy())).unwrap();
    let cases: Vec<(&str, Vec<u8>)> = vec![
        ("garbage", b"not json at all \x00\xff".to_vec()),
        ("truncated", full[..full.len() / 2].to_vec()),
        ("empty", Vec::new()),
    ];
    for (tag, bytes) in cases {
        let p = tmp_path(tag);
        write(&p, &bytes);
        assert_eq!(persist::load(&p).err(), Some(EqError::Corrupt), "{tag}");
        assert!(state_eq(&persist::load_or_default(&p), &Inner::default()), "{tag}");
        cleanup(&p);
    }
}

#[test]
fn missing_file_loads_defaults() {
    let p = tmp_path("missing");
    assert!(matches!(persist::load(&p), Err(EqError::Io(_))));
    assert!(state_eq(&persist::load_or_default(&p), &Inner::default()));
}

#[test]
fn future_version_is_unsupported() {
    let p = tmp_path("future");
    write(&p, br#"{"version":2}"#);
    assert_eq!(persist::load(&p).err(), Some(EqError::UnsupportedVersion(2)));
    write(&p, br#"{"version":2,"mode":"Something New","bands":[]}"#);
    assert_eq!(persist::load(&p).err(), Some(EqError::UnsupportedVersion(2)));
    cleanup(&p);
}

#[test]
fn oversized_file_is_rejected_without_parsing() {
    let p = tmp_path("big");
    // Valid JSON padded with spaces: it would parse if the size cap were not applied first.
    let mut bytes = br#"{"version":1}"#.to_vec();
    bytes.resize(1024 * 1024 + 1, b' ');
    write(&p, &bytes);
    assert_eq!(persist::load(&p).err(), Some(EqError::Corrupt));
    bytes.truncate(1024 * 1024);
    write(&p, &bytes);
    assert!(persist::load(&p).is_ok());
    cleanup(&p);
}

#[test]
fn more_than_sixteen_bands_are_truncated() {
    let bands: Vec<String> = (0..40)
        .map(|i| format!(r#"{{"kind":"Peak","freq":{},"gain_db":1.0,"q":1.0,"enabled":true}}"#, 100 + i))
        .collect();
    let json = format!(r#"{{"version":1,"parametric":{{"bands":[{}]}}}}"#, bands.join(","));
    let s = persist::parse(json.as_bytes()).unwrap();
    assert_eq!(s.parametric.len(), 16);
}

#[test]
fn out_of_range_values_are_clamped() {
    let json = r#"{"version":1,"preamp_db":500.0,"frame_duration_ms":9999.0,
        "parametric":{"bands":[{"kind":"Peak","freq":1e9,"gain_db":500.0,"q":1e9,"enabled":true}]}}"#;
    let s = persist::parse(json.as_bytes()).unwrap();
    let b = s.parametric[0];
    assert_eq!((b.freq, b.gain_db, b.q), (20_000.0, 24.0, 18.0));
    assert_eq!(s.preamp_db, 24.0);
    assert_eq!(s.frame_duration_ms, 85.3);
}

#[test]
fn bad_graphic_state_is_corrupt() {
    let bad_count = r#"{"version":1,"graphic":{"band_count":7,"gains_db":[0,0,0,0,0,0,0]}}"#;
    let bad_len = r#"{"version":1,"graphic":{"band_count":10,"gains_db":[0,0,0]}}"#;
    assert_eq!(persist::parse(bad_count.as_bytes()).err(), Some(EqError::Corrupt));
    assert_eq!(persist::parse(bad_len.as_bytes()).err(), Some(EqError::Corrupt));
}

#[test]
fn non_finite_numbers_are_corrupt() {
    let inf = r#"{"version":1,"preamp_db":1e999}"#;
    let null = r#"{"version":1,"preamp_db":null}"#;
    let band = r#"{"version":1,"parametric":{"bands":[{"kind":"Peak","freq":1e999,"gain_db":0,"q":1,"enabled":true}]}}"#;
    for j in [inf, null, band] {
        assert_eq!(persist::parse(j.as_bytes()).err(), Some(EqError::Corrupt), "{j}");
    }
}

#[test]
fn save_into_missing_directory_is_io_and_leaves_other_file_alone() {
    let keep = tmp_path("keep");
    persist::save(&keep, &heavy()).unwrap();
    let before = std::fs::read(&keep).unwrap();
    let bad = std::env::temp_dir()
        .join(format!("no-such-dir-{}", std::process::id()))
        .join("state.json")
        .to_string_lossy()
        .into_owned();
    assert!(matches!(persist::save(&bad, &Inner::default()), Err(EqError::Io(_))));
    assert_eq!(std::fs::read(&keep).unwrap(), before);
    cleanup(&keep);
}

#[test]
fn failed_rename_keeps_the_target_and_removes_the_tmp() {
    // The target path is a directory, so the rename must fail.
    let dir = tmp_path("dirtarget");
    std::fs::create_dir(&dir).unwrap();
    assert!(matches!(persist::save(&dir, &Inner::default()), Err(EqError::Io(_))));
    assert!(!std::path::Path::new(&format!("{dir}.tmp")).exists());
    assert!(std::path::Path::new(&dir).is_dir());
    std::fs::remove_dir(&dir).unwrap();
}

#[test]
fn no_tmp_file_remains_after_success() {
    let p = tmp_path("notmp");
    persist::save(&p, &Inner::default()).unwrap();
    assert!(!std::path::Path::new(&format!("{p}.tmp")).exists());
    cleanup(&p);
}

#[test]
fn saving_over_an_existing_file_replaces_it() {
    let p = tmp_path("replace");
    persist::save(&p, &Inner::default()).unwrap();
    let new = heavy();
    persist::save(&p, &new).unwrap();
    assert!(state_eq(&persist::load(&p).unwrap(), &new));
    cleanup(&p);
}
