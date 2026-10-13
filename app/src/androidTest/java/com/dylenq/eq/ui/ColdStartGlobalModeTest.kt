package com.dylenq.eq.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.service.SettingsStore
import com.dylenq.eq.testsupport.FakeEffectFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Carry-over (b): a persisted global mode must not flash "off" at cold start. */
class ColdStartGlobalModeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "eq_settings_coldstart_${System.nanoTime()}"

    @After fun cleanup() { ctx.deleteSharedPreferences(prefsName) }

    private fun persisted(global: Boolean): SettingsStore =
        SettingsStore(ctx, prefsName).also { it.globalMode = global }

    @Test fun firstEmissionCarriesPersistedGlobalModeOverRepeatedColdStarts() {
        repeat(25) { i ->
            val r = EqRuntime(FakeEffectFactory(), null, persisted(true))
            val h = UiStateHolder(r)
            try {
                assertTrue("cold start #$i: status seeded", r.status.value.globalMode)
                assertTrue("cold start #$i: first holder emission", h.ui.value.globalMode)
                r.inspect { } // the worker has run its first publish
                assertTrue("cold start #$i: stays true after the worker", r.status.value.globalMode)
                assertTrue("cold start #$i: the manager really is in global mode", r.inspect { it.isGlobalOn() })
            } finally {
                h.close()
                r.shutdown()
            }
        }
    }

    @Test fun rejectedGlobalModeEndsOffWithTheNoticeAndTheSettingTurnedOff() {
        val f = FakeEffectFactory().also { it.failSession(0, IllegalStateException("x")) }
        val settings = persisted(true)
        val r = EqRuntime(f, null, settings)
        val h = UiStateHolder(r)
        try {
            val s = runBlocking { withTimeout(5000) { r.status.first { !it.globalMode && it.notice != null } } }
            assertFalse(s.globalMode)
            assertTrue(s.notice!!.startsWith("Global mode turned off: this phone rejected it ("))
            assertFalse("persisted setting turned off", settings.globalMode)
            val ui = runBlocking { withTimeout(5000) { h.ui.first { it.notice != null } } }
            assertFalse(ui.globalMode)
        } finally {
            h.close()
            r.shutdown()
        }
    }

    @Test fun globalModeOffIsOffOnTheFirstEmissionToo() {
        repeat(5) {
            val r = EqRuntime(FakeEffectFactory(), null, persisted(false))
            val h = UiStateHolder(r)
            try {
                assertFalse(h.ui.value.globalMode)
                r.inspect { }
                assertFalse(r.status.value.globalMode)
                assertNull(r.status.value.notice)
                assertEquals(0, r.status.value.sessionCount)
                assertFalse(r.status.value.serviceRunning)
                assertNotNull(r.status.value)
            } finally {
                h.close()
                r.shutdown()
            }
        }
    }
}
