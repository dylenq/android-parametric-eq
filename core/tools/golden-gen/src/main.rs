//! Golden generator. Regeneration is a MANUAL step that needs the macOS checkout at
//! /Users/dylenquyn/Documents/eq (commit 03be7aa, clean src/). It includes the UNMODIFIED
//! macOS sources and prints JSON for core/tests/golden/macos_response.json. Never generate the
//! golden numbers from the ported Android code: that would be circular.
//!
//!   cargo run --offline --manifest-path core/tools/golden-gen/Cargo.toml \
//!     > core/tests/golden/macos_response.json
#![allow(dead_code, unused_imports)]

#[path = "/Users/dylenquyn/Documents/eq/src/state.rs"]
mod state;

mod dsp {
    #[path = "/Users/dylenquyn/Documents/eq/src/dsp/biquad.rs"]
    pub mod biquad;
}

use dsp::biquad::Coeffs;
use serde_json::json;
use state::FilterKind;
use std::process::Command;

const MACOS: &str = "/Users/dylenquyn/Documents/eq";

fn git(args: &[&str]) -> String {
    let out = Command::new("git").arg("-C").arg(MACOS).args(args).output().expect("git");
    String::from_utf8(out.stdout).expect("utf8").trim().to_string()
}

fn main() {
    let commit = git(&["rev-parse", "HEAD"]);
    assert!(commit.starts_with("03be7aa"), "macOS HEAD is {commit}, expected 03be7aa");
    assert!(git(&["status", "--porcelain", "src"]).is_empty(), "macOS src/ is not clean");

    let freqs: Vec<f64> = (0..40)
        .map(|i| 20.0 * (20_000.0f64 / 20.0).powf(i as f64 / 39.0))
        .collect();
    let mut cases = Vec::new();
    for kind in FilterKind::ALL {
        for sr in [44_100.0f64, 48_000.0] {
            for freq in [50.0f64, 100.0, 1000.0, 4000.0, 10000.0, 18000.0] {
                let gains: &[f64] = if kind.has_gain() { &[-12.0, -6.0, 0.0, 6.0, 12.0] } else { &[0.0] };
                for &gain in gains {
                    for q in [0.5f64, 0.707, 1.0, 2.0, 8.0, 18.0] {
                        let c = Coeffs::design(kind, freq, gain, q, sr);
                        let mags: Vec<_> =
                            freqs.iter().map(|&f| json!([f, c.magnitude_db(f, sr)])).collect();
                        cases.push(json!({
                            "kind": format!("{kind:?}"),
                            "freq": freq, "gain_db": gain, "q": q, "sample_rate": sr,
                            "coeffs": {"b0": c.b0, "b1": c.b1, "b2": c.b2, "a1": c.a1, "a2": c.a2},
                            "magnitudes": mags,
                        }));
                    }
                }
            }
        }
    }
    let doc = json!({
        "macos_commit": commit,
        "generated_by": "core/tools/golden-gen (unmodified macOS state.rs + dsp/biquad.rs)",
        "cases": cases,
    });
    println!("{}", serde_json::to_string(&doc).unwrap());
}
