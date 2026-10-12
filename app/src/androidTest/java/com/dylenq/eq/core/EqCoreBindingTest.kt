package com.dylenq.eq.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EqCoreBindingTest {
    @Test
    fun version_comes_back_from_rust() {
        EqCore().use { core ->
            val v = core.version()
            assertTrue("version was '$v'", v.isNotEmpty() && v[0].isDigit())
        }
    }
}
