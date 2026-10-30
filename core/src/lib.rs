//! eq-core: the equalizer's pure-Rust core, exposed to Kotlin through UniFFI.

uniffi::setup_scaffolding!();

pub mod achieved;
pub mod api;
pub mod dsp;
pub mod error;
pub mod fit;
pub mod graphic;
pub mod grid;
pub mod headroom;
pub mod history;
pub mod inner;
// Parser kept for presets (v2); not exported over FFI.
#[allow(dead_code)]
pub(crate) mod import;
pub mod model;
pub mod persist;
pub mod presets;

pub use api::EqCore;
pub use error::EqError;


