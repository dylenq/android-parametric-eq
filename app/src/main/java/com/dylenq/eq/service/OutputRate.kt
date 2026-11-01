package com.dylenq.eq.service

/** Assumed output rate when the platform reports none. */
private const val FALLBACK_SAMPLE_RATE = 48_000

/**
 * The platform returns zero or a negative error code when the output is not ready (during a device
 * change, for example). The core rejects those rates, so a bad reading would fail every attach.
 */
fun usableSampleRate(reported: Int): UInt =
    (if (reported in 8_000..384_000) reported else FALLBACK_SAMPLE_RATE).toUInt()
