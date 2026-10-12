//! The one error type that crosses the FFI.

#[derive(Debug, Clone, PartialEq, thiserror::Error, uniffi::Error)]
pub enum EqError {
    #[error("file error: {0}")]
    Io(String),
    #[error("the saved state is damaged")]
    Corrupt,
    #[error("the saved state is from a newer version ({0})")]
    UnsupportedVersion(u32),
    #[error("index is out of range")]
    OutOfRange,
    #[error("value is not valid (not a finite number or outside the allowed set)")]
    InvalidValue,
    #[error("a parametric EQ holds at most 16 bands")]
    TooManyBands,
}

impl From<crate::model::GraphicError> for EqError {
    fn from(e: crate::model::GraphicError) -> Self {
        match e {
            crate::model::GraphicError::BadIndex => EqError::OutOfRange,
            crate::model::GraphicError::NotFinite | crate::model::GraphicError::BadCount => {
                EqError::InvalidValue
            }
        }
    }
}
