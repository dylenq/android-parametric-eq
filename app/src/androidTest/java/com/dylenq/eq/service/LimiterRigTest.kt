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
 * The test asserts only that the table was produced; the verdict is recorded in LIMITER-TABLE.md.
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

    @Test
    fun produces_the_limiter_table() {
        val runtime = rt.runtime
        val c = runtime.controller
        val rows = listOf(-20.0 to 0f, -10.0 to 0f, -3.0 to 0f, -3.0 to 6f, -3.0 to 12f)
        var produced = 0
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
                    produced++
                } finally {
                    vis.release()
                    rig.release()
                    runtime.post(SessionIntent.Event.Close(rig.sessionId, "com.example.player"))
                    poll(2000) { runtime.inspect { it.sessionCount() } == 0 }
                }
            }
        }
        assertEquals(10, produced)
    }
}
