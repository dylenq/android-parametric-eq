//! The only UniFFI surface. Task 2 of plan 02-04 replaces this tracer with the full object.

use std::sync::Arc;

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
