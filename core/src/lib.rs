//! eq-core: the equalizer's pure-Rust core, exposed to Kotlin through UniFFI.

use std::sync::Arc;

uniffi::setup_scaffolding!();

pub mod achieved;
pub mod dsp;
pub mod fit;
pub mod graphic;
pub mod grid;
pub mod history;
// Parser kept for presets (v2); not exported over FFI.
#[allow(dead_code)]
pub(crate) mod import;
pub mod model;

/// The single object Kotlin talks to. Later plans add state and methods here.
#[derive(uniffi::Object)]
pub struct EqCore {}

#[uniffi::export]
impl EqCore {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(EqCore {})
    }

    pub fn version(&self) -> String {
        env!("CARGO_PKG_VERSION").to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_is_semver_like() {
        let v = EqCore::new().version();
        assert!(v.chars().next().is_some_and(|c| c.is_ascii_digit()));
    }
}
