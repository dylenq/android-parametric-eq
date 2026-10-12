package com.dylenq.eq.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativePingTest {
    @Test
    fun nativePingReturns42() {
        assertEquals(42, Native.ping())
    }
}
