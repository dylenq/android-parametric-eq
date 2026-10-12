package com.dylenq.eq.service

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.RuntimeRule
import com.dylenq.eq.testsupport.ToneRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The core's achieved response at 1 kHz, measured through the production effect path with
 * Visualizer(0). The expectation is the core's own achieved value (it already includes the engine's
 * bin smearing), within 0.5 dB.
 */
@RunWith(AndroidJUnit4::class)
class MeasuredResponseTest {
    private val perm = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    private val rt = RuntimeRule({ RealEffectFactory() })

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(perm).around(rt)

    private fun poll(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(25)
        }
        return cond()
    }

    private fun check(frameMs: Float) {
        val runtime = rt.runtime
        val c = runtime.controller
        c.edit {
            it.setFrameDurationMs(frameMs)
            it.setMode(EqMode.PARAMETRIC)
            it.setPreamp(0f)
            it.setBand(0u, Band(FilterKind.PEAK, 1000f, 6f, 4f, true))
        }
        val rate = ToneRig.nativeRate().toUInt()
        val curve = c.edit { it.responseCurve(2000u, rate, frameMs) }
        val i = curve.freqsHz.indices.minByOrNull { Math.abs(curve.freqsHz[it] - 1000f) }!!
        val expected = curve.achievedDb[i].toDouble()

        val rig = ToneRig().also { it.start() }
        val vis = ToneRig.openVisualizer(0)
        try {
            val baseline = ToneRig.levelDb(vis, "measured-$frameMs-baseline")
            runtime.post(SessionIntent.Event.Open(rig.sessionId, "com.example.player"))
            assertTrue("session not tracked", poll(3000) { runtime.inspect { it.sessionCount() } == 1 })
            val wantGain = runtime.inspect { m -> m.handleFor(rig.sessionId) != null }
            assertTrue(wantGain)
            Thread.sleep(300)
            val boosted = ToneRig.levelDb(vis, "measured-$frameMs-boosted")
            val delta = boosted - baseline
            Log.i(
                "EQ-ROUTE",
                "MEASURED frame=$frameMs baseline=$baseline boosted=$boosted delta=$delta expected=$expected diff=${delta - expected} at=${curve.freqsHz[i]}Hz",
            )
            assertEquals("measured change vs core achieved at 1 kHz (frame $frameMs)", expected, delta, 0.5)
        } finally {
            vis.release()
            rig.release()
            runtime.post(SessionIntent.Event.Close(rig.sessionId, "com.example.player"))
        }
    }

    @Test fun measured_matches_achieved_at_85_3() = check(85.3f)

    @Test fun measured_matches_achieved_at_170_6() = check(170.6f)
}
