//! Spike-only JNI probe: proves a cargo-ndk cdylib loads inside the app.
#[no_mangle]
pub extern "system" fn Java_com_dylenq_eq_spike_Native_ping(
    _env: *mut core::ffi::c_void,
    _this: *mut core::ffi::c_void,
) -> i32 {
    42
}
