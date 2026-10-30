package com.dylenq.eq.service

import android.Manifest
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.dylenq.eq.testsupport.RuntimeRule
import com.dylenq.eq.testsupport.ToneRig
import com.dylenq.eq.testsupport.ToneRig.Channels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Balance measured through the production effect path with Visualizer(0): a one-sided test tone
 * is attenuated only when the balance pushes against that side. Also settles DynamicsProcessing
 * channel order (channel 0 = left) and that the -84 dB absolute floor (preamp -24 + the full -60 dB balance attenuation) is accepted by the real effect.
 */
@RunWith(AndroidJUnit4::class)
class BalanceMeasuredTest {
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

    /** Baseline level before any effect, then the level after [configure] with the session attached. */
    private fun measure(side: Channels, label: String, configure: () -> Unit): Pair<Double, Double> {
        val runtime = rt.runtime
        val rig = ToneRig(channels = side).also { it.start() }
        val vis: Visualizer = ToneRig.openVisualizer(0)
        try {
            val baseline = ToneRig.levelDb(vis, "$label-baseline")
            configure()
            runtime.post(SessionIntent.Event.Open(rig.sessionId, "com.example.player"))
            assertTrue("session not tracked", poll(3000) { runtime.inspect { it.sessionCount() } == 1 })
            Thread.sleep(300)
            val level = ToneRig.levelDb(vis, "$label-after")
            Log.i("EQ-ROUTE", "BALANCE $label baseline=$baseline after=$level delta=${level - baseline}")
            return baseline to level
        } finally {
            vis.release()
            rig.release()
            runtime.post(SessionIntent.Event.Close(rig.sessionId, "com.example.player"))
        }
    }

    private fun balance(b: Float) = rt.runtime.controller.edit { it.setBalance(b) }

    @Test
    fun channelZeroIsLeftAndFloorIsAccepted() {
        val runtime = rt.runtime
        val rig = ToneRig(channels = Channels.LEFT_ONLY).also { it.start() }
        val vis = ToneRig.openVisualizer(0)
        try {
            val baseline = ToneRig.levelDb(vis, "probe-baseline")
            runtime.controller.edit { it.setPreamp(-24f); it.setBalance(1f) }
            runtime.post(SessionIntent.Event.Open(rig.sessionId, "com.example.player"))
            assertTrue("session not tracked", poll(3000) { runtime.inspect { it.sessionCount() } == 1 })
            Thread.sleep(300)
            val l = runtime.inspect { m -> m.handleFor(rig.sessionId)!!.readInputGain(CH_LEFT) }
            val r = runtime.inspect { m -> m.handleFor(rig.sessionId)!!.readInputGain(CH_RIGHT) }
            assertEquals("left gain (preamp -24 + 60 dB attenuation) read back", -84.0, l.toDouble(), 0.01)
            assertEquals("right gain is the preamp only", -24.0, r.toDouble(), 0.01)
            assertTrue("session must not be FAILED", runtime.inspect { m ->
                m.sessionInfos().none { it.state == SessionState.FAILED }
            })
            val level = ToneRig.levelDb(vis, "probe-after")
            Log.i("EQ-ROUTE", "BALANCE probe baseline=$baseline after=$level drop=${baseline - level}")
            assertTrue("left-only tone should drop >= 40 dB (drop=${baseline - level})", baseline - level >= 40.0)
        } finally {
            vis.release()
            rig.release()
            runtime.post(SessionIntent.Event.Close(rig.sessionId, "com.example.player"))
        }
    }

    @Test
    fun rightOnlyToneIsUnchangedWhenBalanceIsRight() {
        val (base, after) = measure(Channels.RIGHT_ONLY, "right-bal+1") { balance(1f) }
        assertEquals("right-only level unchanged", base, after, 0.5)
    }

    @Test
    fun mirroredForLeftBalance() {
        val (lb, la) = measure(Channels.LEFT_ONLY, "left-bal-1") { balance(-1f) }
        assertEquals("left-only unchanged at balance -1", lb, la, 0.5)
    }

    @Test
    fun mirroredForLeftBalanceAttenuatesRight() {
        val (rb, ra) = measure(Channels.RIGHT_ONLY, "right-bal-1") { balance(-1f) }
        assertTrue("right-only should drop >= 40 dB (drop=${rb - ra})", rb - ra >= 40.0)
    }

    @Test
    fun halfBalanceAttenuatesLeftBySixDb() {
        val (b, a) = measure(Channels.LEFT_ONLY, "left-bal+0.5") { balance(0.5f) }
        assertEquals("left-only drop at balance +0.5", 6.0, b - a, 1.0)
    }
}
