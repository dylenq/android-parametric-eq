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
    s.set_bass(7.5).unwrap();
    s.set_treble(-4.0).unwrap();
    s.set_balance(0.35).unwrap();
    s.limiter_enabled = false;
    s.bypass = true;
    s.mode = EqMode::Parametric;
    s.set_frame_duration_ms(170.6).unwrap();
    s.save_preset("Heavy night").unwrap();
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

/// A v0.1-era state file: every key it ever wrote, none of the tone/balance keys.
const V01_FIXTURE: &str = r#"{"version":1,"mode":"Parametric","preamp_db":-3.5,
"limiter_enabled":false,"bypass":false,"frame_duration_ms":85.3}"#;

#[test]
fn v01_file_loads_with_zero_tone_and_balance() {
    let s = persist::parse(V01_FIXTURE.as_bytes()).unwrap();
    assert_eq!((s.bass_db, s.treble_db, s.balance), (0.0, 0.0, 0.0));
    assert_eq!(s.preamp_db, -3.5);
    let mut twin = Inner::default();
    twin.mode = EqMode::Parametric;
    twin.set_preamp(-3.5).unwrap();
    twin.limiter_enabled = false;
    let a = s.engine_params(48000, 85.3).unwrap();
    let b = twin.engine_params(48000, 85.3).unwrap();
    assert_eq!(a.gains_db, b.gains_db);
    assert_eq!(a.input_gain_left_db, b.input_gain_left_db);
    assert_eq!(a.input_gain_right_db, b.input_gain_right_db);
}

#[test]
fn new_keys_are_defaulted_not_required() {
    // Tracks the schema: if a key is added without serde(default), an old file breaks.
    let mut v = serde_json::to_value(PersistedState::from_inner(&heavy())).unwrap();
    let o = v.as_object_mut().unwrap();
    for k in ["bass_db", "treble_db", "balance"] {
        assert!(o.remove(k).is_some(), "{k}");
    }
    let s = persist::parse(&serde_json::to_vec(&v).unwrap()).unwrap();
    assert_eq!((s.bass_db, s.treble_db, s.balance), (0.0, 0.0, 0.0));
}

#[test]
fn tone_and_balance_round_trip_values() {
    let p = tmp_path("tone");
    persist::save(&p, &heavy()).unwrap();
    let back = persist::load(&p).unwrap();
    assert_eq!((back.bass_db, back.treble_db, back.balance), (7.5, -4.0, 0.35));
    cleanup(&p);
}

#[test]
fn tone_and_balance_clamp_and_non_finite() {
    let s = persist::parse(br#"{"version":1,"bass_db":99.0,"treble_db":-99.0,"balance":-5}"#).unwrap();
    assert_eq!((s.bass_db, s.treble_db, s.balance), (12.0, -12.0, -1.0));
    let s = persist::parse(br#"{"version":1,"balance":0.005}"#).unwrap();
    assert_eq!(s.balance, 0.0);
    for bad in [
        r#"{"version":1,"bass_db":1e999}"#,
        r#"{"version":1,"treble_db":-1e999}"#,
        r#"{"version":1,"balance":null}"#,
        r#"{"version":1,"balance":"x"}"#,
    ] {
        assert!(matches!(persist::parse(bad.as_bytes()), Err(EqError::Corrupt)), "{bad}");
    }
    assert_eq!(persist::SCHEMA_VERSION, 1);
}

// ---- Phase 9: presets ----

fn preset_json(name: &str, extra: &str) -> String {
    let gains = vec!["0.0"; 10].join(",");
    let bands = serde_json::to_string(&Inner::default().parametric).unwrap();
    format!(
        r#"{{"name":{name},"curve":{{"mode":"Graphic","bands":{bands},"graphic_band_count":10,"graphic_gains_db":[{gains}],"preamp_db":0.0,"bass_db":0.0,"treble_db":0.0,"balance":0.0{extra}}}}}"#,
        name = serde_json::to_string(name).unwrap(),
    )
}

fn load_json(tag: &str, json: &str) -> Result<Inner, EqError> {
    let p = tmp_path(tag);
    write(&p, json.as_bytes());
    let r = persist::load(&p);
    cleanup(&p);
    r
}

fn names(s: &Inner) -> Vec<String> {
    s.presets.iter().map(|p| p.name.clone()).collect()
}

#[test]
fn presets_old_and_phase8_files_load_with_none() {
    let a = load_json("p0", r#"{"version":1}"#).unwrap();
    assert!(a.presets.is_empty() && a.active_preset.is_none());
    let b = load_json("p8", r#"{"version":1,"mode":"Parametric","bass_db":2.0,"balance":0.3}"#).unwrap();
    assert!(b.presets.is_empty() && b.active_preset.is_none());
    assert_eq!(persist::SCHEMA_VERSION, 1);
}

#[test]
fn presets_round_trip_parametric_preset_with_tone_and_balance() {
    let mut s = Inner::default();
    s.mode = EqMode::Parametric;
    s.set_bass(5.0).unwrap();
    s.set_balance(-0.4).unwrap();
    s.set_preamp(-2.0).unwrap();
    s.save_preset("Para").unwrap();
    let p = tmp_path("pr");
    persist::save(&p, &s).unwrap();
    let back = persist::load(&p).unwrap();
    cleanup(&p);
    assert!(state_eq(&s, &back));
    assert_eq!(back.active_preset.as_deref(), Some("user:Para"));
    assert!(!back.is_modified());
    let c = &back.presets[0].curve;
    assert_eq!((c.mode, c.bass_db, c.balance), (EqMode::Parametric, 5.0, -0.4));
    assert!(back.presets.len() == 1);
}

#[test]
fn presets_bad_ones_are_dropped_individually() {
    let long = "x".repeat(41);
    let good = |n: &str| preset_json(n, "");
    let bad_count = preset_json("BadCount", "").replace("\"graphic_band_count\":10", "\"graphic_band_count\":7");
    let bad_len = preset_json("BadLen", "").replace("\"graphic_gains_db\":[0.0,", "\"graphic_gains_db\":[");
    let bad_mode = preset_json("BadMode", "").replace("\"Graphic\"", "\"Wat\"");
    let list = vec![
        good("Keep1"),
        good(""),
        good(&long),
        good("ctl\u{7}x"),
        good("FLAT"),
        good("Keep1"),
        good("keep1"),
        bad_count,
        bad_len,
        bad_mode,
        "42".to_string(),
        good("Keep2"),
    ];
    let json = format!(r#"{{"version":1,"presets":[{}],"active_preset":"user:Keep2"}}"#, list.join(","));
    let s = load_json("pbad", &json).unwrap();
    assert_eq!(names(&s), ["Keep1", "Keep2"]);
    assert_eq!(s.active_preset.as_deref(), Some("user:Keep2"));
}

#[test]
fn presets_wrong_shape_never_corrupts_the_file() {
    for extra in [r#""presets":"nope""#, r#""presets":{"a":1}"#, r#""presets":null"#, r#""active_preset":5"#] {
        let json = format!(r#"{{"version":1,"preamp_db":-2.0,{extra}}}"#);
        let s = load_json("pshape", &json).unwrap();
        assert!(s.presets.is_empty());
        assert_eq!(s.preamp_db, -2.0);
    }
}

#[test]
fn presets_out_of_range_values_are_clamped_on_load() {
    let json = format!(
        r#"{{"version":1,"presets":[{}]}}"#,
        preset_json("Loud", "").replace("\"preamp_db\":0.0", "\"preamp_db\":99.0").replace("\"bass_db\":0.0", "\"bass_db\":50.0")
    );
    let s = load_json("pclamp", &json).unwrap();
    assert_eq!((s.presets[0].curve.preamp_db, s.presets[0].curve.bass_db), (24.0, 12.0));
}

#[test]
fn presets_non_finite_floats_drop_that_preset() {
    use eq_core::persist::sanitize;
    let mut st = PersistedState::from_inner(&Inner::default());
    let mut a = Inner::default();
    a.save_preset("Good").unwrap();
    let mut b = Inner::default();
    b.save_preset("Nan").unwrap();
    b.presets[0].curve.graphic_gains_db[0] = f32::NAN;
    let mut c = Inner::default();
    c.save_preset("Inf").unwrap();
    c.presets[0].curve.preamp_db = f32::INFINITY;
    st.presets = vec![a.presets[0].clone(), b.presets[0].clone(), c.presets[0].clone()];
    let out = sanitize(st).unwrap();
    assert_eq!(out.presets.len(), 1);
    assert_eq!(out.presets[0].name, "Good");
}

#[test]
fn presets_more_than_a_hundred_truncate() {
    let list: Vec<String> = (0..130).map(|i| preset_json(&format!("p{i}"), "")).collect();
    let json = format!(r#"{{"version":1,"presets":[{}]}}"#, list.join(","));
    let s = load_json("pmany", &json).unwrap();
    assert_eq!(s.presets.len(), 100);
    assert_eq!(s.presets[99].name, "p99");
}

#[test]
fn presets_dangling_active_key_loads_as_none() {
    let one = preset_json("Here", "");
    for (key, expect) in [
        ("user:Here", Some("user:Here")),
        ("builtin:Bass", Some("builtin:Bass")),
        ("user:Missing", None),
        ("builtin:Nope", None),
        ("weird:Here", None),
        ("Here", None),
    ] {
        let json = format!(r#"{{"version":1,"presets":[{one}],"active_preset":"{key}"}}"#);
        let s = load_json("pact", &json).unwrap();
        assert_eq!(s.active_preset.as_deref(), expect, "{key}");
    }
}

#[test]
fn presets_hundred_worst_case_fit_under_half_the_file_cap() {
    let mut s = Inner::default();
    s.parametric.clear();
    for i in 0..16 {
        let mut b = Band::new(FilterKind::ALL[i % 5], 37.123_457 * (i as f32 + 1.0) * 3.3);
        b.gain_db = -23.456_78 + i as f32;
        b.q = 0.123_456_79 + i as f32;
        s.parametric.push(b);
    }
    s.set_graphic_band_count(31).unwrap();
    for i in 0..31 {
        s.set_graphic_gain(i, -23.456_78 + i as f32 * 0.123_456_79).unwrap();
    }
    s.set_preamp(-17.654_322).unwrap();
    s.set_bass(-11.234_567).unwrap();
    s.set_treble(10.987_654).unwrap();
    s.set_balance(0.333_333_34).unwrap();
    for i in 0..100 {
        s.save_preset(&format!("{i:03}-{}", "n".repeat(36))).unwrap();
    }
    let p = tmp_path("psize");
    persist::save(&p, &s).unwrap();
    let len = std::fs::metadata(&p).unwrap().len();
    assert!(len < persist::MAX_FILE_BYTES / 2, "{len} bytes");
    let back = persist::load(&p).unwrap();
    cleanup(&p);
    assert_eq!(back.presets.len(), 100);
    assert!(state_eq(&s, &back));
}
