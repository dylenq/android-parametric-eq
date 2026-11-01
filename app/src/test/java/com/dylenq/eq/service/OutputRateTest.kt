package com.dylenq.eq.service

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputRateTest {
    @Test
    fun a_real_rate_passes_through() {
        assertEquals(44_100u, usableSampleRate(44_100))
        assertEquals(192_000u, usableSampleRate(192_000))
    }

    @Test
    fun zero_and_error_codes_fall_back_instead_of_wrapping_to_a_huge_unsigned_rate() {
        assertEquals(48_000u, usableSampleRate(0))
        assertEquals(48_000u, usableSampleRate(-1))
        assertEquals(48_000u, usableSampleRate(Int.MAX_VALUE))
    }
}
