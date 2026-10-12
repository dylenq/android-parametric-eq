package com.dylenq.eq.service

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.dylenq.eq.testsupport.RuntimeRule
import com.dylenq.eq.testsupport.ToneRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Measures the limiter (off vs on) through the production path and logs a table under EQ-LIMITER.
 * The test asserts the result after logging every row: the disabled reading must track the input
 * within 0.5 dB, pass-through rows must match the prediction within 0.5 dB, and compressive rows
 * (input above the threshold) must match within 1.5 dB. LIMITER-TABLE.md holds the narrative.
 */
@RunWith(AndroidJUnit4::class)
class LimiterRigTest {
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

    private val threshold = -6.0
    private val ratio = 10.0

    private data class Row(
        val frame: Float, val peak: Double, val preamp: Float,
        val off: Double, val on: Double, val inDbfs: Double, val offset: Double, val predicted: Double,
    )

    @Test
    fun produces_the_limiter_table() {
        val runtime = rt.runtime
        val c = runtime.controller
        val rows = listOf(-20.0 to 0f, -10.0 to 0f, -3.0 to 0f, -3.0 to 6f, -3.0 to 12f)
        val measured = mutableListOf<Row>()
        // Visualizer RMS is not in dBFS (a -20 dBFS peak sine, RMS -23.01 dBFS, reads about -55.5).
        // Calibrate the offset from the first row of each frame (-20 dBFS peak, limiter far below
        // its threshold), then compare in the Visualizer scale. Input level is analytic.
        var offset = 0.0
        for (frame in listOf(85.3f, 170.6f)) {
            offset = Double.NaN
            for ((peak, preamp) in rows) {
                c.edit { it.setFrameDurationMs(frame); it.setPreamp(preamp); it.setLimiterEnabled(false) }
                val rig = ToneRig(amplitudeDbfs = peak).also { it.start() }
                val vis = ToneRig.openVisualizer(0)
                try {
                    runtime.post(SessionIntent.Event.Open(rig.sessionId, "com.example.player"))
                    assertTrue("session not tracked", poll(3000) { runtime.inspect { it.sessionCount() } == 1 })
                    assertTrue("limiter off not applied", poll(3000) {
                        runtime.inspect { m -> m.handleFor(rig.sessionId)?.readLimiterEnabled() == false }
                    })
                    Thread.sleep(300)
                    val off = ToneRig.levelDb(vis, "lim-off f=$frame p=$peak pre=$preamp")
                    c.edit { it.setLimiterEnabled(true) }
                    assertTrue("limiter on not applied", poll(3000) {
                        runtime.inspect { m -> m.handleFor(rig.sessionId)?.readLimiterEnabled() == true }
                    })
                    Thread.sleep(300)
                    val on = ToneRig.levelDb(vis, "lim-on f=$frame p=$peak pre=$preamp")
                    val inDbfs = peak - 3.0103 + preamp
                    if (offset.isNaN()) offset = off - inDbfs
                    val predictedDbfs = if (inDbfs <= threshold) inDbfs else threshold + (inDbfs - threshold) / ratio
                    val predicted = predictedDbfs + offset
                    Log.i(
                        "EQ-LIMITER",
                        "ROW frame=$frame peak=$peak preamp=$preamp disabled=$off enabled=$on predicted=$predicted inDbfs=$inDbfs offset=$offset offDiff=${off - (inDbfs + offset)} diff=${on - predicted}",
                    )
                    measured.add(Row(frame, peak, preamp, off, on, inDbfs, offset, predicted))
                } finally {
                    vis.release()
                    rig.release()
                    runtime.post(SessionIntent.Event.Close(rig.sessionId, "com.example.player"))
                    poll(2000) { runtime.inspect { it.sessionCount() } == 0 }
                }
            }
        }
        assertEquals(10, measured.size)
        for (r in measured) {
            val tag = "frame=${r.frame} peak=${r.peak} preamp=${r.preamp}"
            val offDiff = r.off - (r.inDbfs + r.offset)
            assertTrue("disabled reading off by $offDiff dB at $tag", Math.abs(offDiff) <= 0.5)
            val diff = r.on - r.predicted
            val bound = if (r.inDbfs > threshold) 1.5 else 0.5
            assertTrue("enabled reading off prediction by $diff dB (bound $bound) at $tag", Math.abs(diff) <= bound)
        }
    }
}
