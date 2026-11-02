package com.dylenq.eq.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.RuntimeRule
import com.dylenq.eq.testsupport.ToneRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Per-app presets on the real DynamicsProcessing factory: two sessions with different configs both attach. */
@RunWith(AndroidJUnit4::class)
class RealFactoryPerAppTest {
    @get:Rule
    val rt = RuntimeRule({ RealEffectFactory() })

    private fun poll(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(25)
        }
        return cond()
    }

    private fun stateOf(session: Int) = rt.runtime.status.value.sessions.firstOrNull { it.id == session }?.state

    @Test
    fun two_sessions_with_different_configs_both_attach_on_the_real_factory() {
        val runtime = rt.runtime
        val ctl = runtime.controller
        ctl.edit {
            it.setMode(EqMode.PARAMETRIC)
            it.setBand(0u, Band(FilterKind.PEAK, 1000f, 9f, 2f, true))
        }
        val key = ctl.savePreset("Warm")
        ctl.resetBands()
        assertEquals(null, runtime.setAppPreset("com.example.a", key))

        val rigA = ToneRig()
        val rigB = ToneRig()
        try {
            rigA.start(); rigB.start()
            runtime.post(SessionIntent.Event.Open(rigA.sessionId, "com.example.a"))
            runtime.post(SessionIntent.Event.Open(rigB.sessionId, "com.example.b"))
            assertTrue(
                "both sessions tracked",
                poll(5000) { stateOf(rigA.sessionId) != null && stateOf(rigB.sessionId) != null },
            )
            val a = stateOf(rigA.sessionId)
            val b = stateOf(rigB.sessionId)
            // The platform effect may be missing or uncontrolled here; the existing real-factory tests skip the same way.
            assumeTrue("no platform effect: $a / $b", a == SessionState.OK && b == SessionState.OK)

            val rate = ToneRig.nativeRate().toUInt()
            val frame = ctl.snapshot().frameDurationMs
            val live = ctl.engineParams(rate, frame)
            val preset = ctl.engineParamsForPreset(key, rate, frame)
            assertNotEquals("the two configs differ", live.gainsDb, preset.gainsDb)
            // The runtime may cap the band count; read only the bands both handles certainly have.
            val n = minOf(live.gainsDb.size, runtime.status.value.engineMaxBands ?: Int.MAX_VALUE)
            runtime.inspect { m ->
                val ha = m.handleFor(rigA.sessionId)!!
                val hb = m.handleFor(rigB.sessionId)!!
                val gainsA = (0 until n).map { ha.readBandGain(it) }
                val gainsB = (0 until n).map { hb.readBandGain(it) }
                assertTrue("B follows the flat live EQ", gainsB.all { kotlin.math.abs(it) < 0.01f })
                assertTrue("A carries its boosted preset", gainsA.any { it > 1f })
                assertTrue(ha.readEnabled() && hb.readEnabled())
            }
        } finally {
            rigA.release(); rigB.release()
        }
    }
}
