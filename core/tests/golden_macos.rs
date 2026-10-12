//! Compares the ported Rust against numbers generated once from the unmodified macOS sources.
//! A mismatch is a bug in the port; never widen the tolerances.

use eq_core::dsp::biquad::Coeffs;
use eq_core::model::FilterKind;
use serde::Deserialize;

const GOLDEN: &str = include_str!("golden/macos_response.json");

#[derive(Deserialize)]
struct Golden {
    macos_commit: String,
    cases: Vec<Case>,
}

#[derive(Deserialize)]
struct Case {
    kind: String,
    freq: f64,
    gain_db: f64,
    q: f64,
    sample_rate: f64,
    coeffs: C,
    magnitudes: Vec<(f64, f64)>,
}

#[derive(Deserialize)]
struct C {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
}

fn kind(name: &str) -> FilterKind {
    FilterKind::ALL
        .into_iter()
        .find(|k| format!("{k:?}") == name)
        .unwrap_or_else(|| panic!("unknown kind {name}"))
}

#[test]
fn matches_macos_coefficients_and_magnitudes() {
    let golden: Golden = serde_json::from_str(GOLDEN).unwrap();
    assert!(golden.macos_commit.starts_with("03be7aa"), "{}", golden.macos_commit);
    assert!(golden.cases.len() >= 5 * 2 * 6, "only {} cases", golden.cases.len());

    for kind_name in ["Peak", "LowShelf", "HighShelf", "LowPass", "HighPass"] {
        for sr in [44_100.0, 48_000.0] {
            assert!(
                golden.cases.iter().any(|c| c.kind == kind_name && c.sample_rate == sr),
                "no golden case for {kind_name} at {sr}"
            );
        }
    }

    for c in &golden.cases {
        let ours = Coeffs::design(kind(&c.kind), c.freq, c.gain_db, c.q, c.sample_rate);
        let ctx = format!("{} f={} g={} q={} sr={}", c.kind, c.freq, c.gain_db, c.q, c.sample_rate);
        for (name, a, b) in [
            ("b0", ours.b0, c.coeffs.b0),
            ("b1", ours.b1, c.coeffs.b1),
            ("b2", ours.b2, c.coeffs.b2),
            ("a1", ours.a1, c.coeffs.a1),
            ("a2", ours.a2, c.coeffs.a2),
        ] {
            assert!((a - b).abs() < 1e-9, "{ctx}: {name} {a} vs {b}");
        }
        for &(f, db) in &c.magnitudes {
            let got = ours.magnitude_db(f, c.sample_rate);
            assert!((got - db).abs() < 1e-6, "{ctx}: {f} Hz {got} vs {db}");
        }
    }
}
