package com.dylenq.eq.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.dylenq.eq.MainActivity
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.RuntimeRule
import com.dylenq.eq.testsupport.ToneRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Real DynamicsProcessing on real AudioTrack sessions, driven by real broadcasts. */
@RunWith(AndroidJUnit4::class)
class SessionLifecycleTest {
    private val perm: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else GrantPermissionRule.grant()
    private val rt = RuntimeRule({ RealEffectFactory() })

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(perm).around(rt)

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun poll(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(25)
        }
        return cond()
    }

    private fun open(session: Int) = ctx.sendBroadcast(
        Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
            .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    )

    private fun close(session: Int) = ctx.sendBroadcast(
        Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
    )

    private fun count() = rt.runtime.inspect { it.sessionCount() }

    @Test
    fun open_tracks_once_matches_the_core_and_close_releases() {
        val runtime = rt.runtime
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var rig: ToneRig? = null
        try {
            assertTrue("service did not attach within 5 s", poll(5000) { runtime.isAttached })
            runtime.controller.edit {
                it.setMode(EqMode.PARAMETRIC)
                it.setBand(0u, Band(FilterKind.PEAK, 1000f, 6f, 4f, true))
            }
            rig = ToneRig().also { it.start() }
            val session = rig.sessionId

            open(session)
            assertTrue("session never tracked", poll(3000) { count() == 1 })
            val first = runtime.inspect { it.handleFor(session) }
            assertTrue(first != null)

            val expected = runtime.controller.engineParams(
                ToneRig.nativeRate().toUInt(), runtime.controller.snapshot().frameDurationMs,
            )
            val n = expected.cutoffsHz.size
            runtime.inspect { m ->
                val h = m.handleFor(session)!!
                for (i in listOf(0, 7, n / 2, n - 1)) {
                    assertEquals("gain band $i", expected.gainsDb[i], h.readBandGain(i), 0.01f)
                    assertEquals("cutoff band $i", expected.cutoffsHz[i], h.readBandCutoff(i), 0.5f)
                }
                assertEquals(expected.inputGainLeftDb, h.readInputGain(CH_LEFT), 0.01f)
                assertEquals(expected.inputGainRightDb, h.readInputGain(CH_RIGHT), 0.01f)
                assertEquals(expected.limiter.thresholdDb, h.readLimiterThresholdDb(), 0.01f)
                assertEquals(expected.limiterEnabled, h.readLimiterEnabled())
                assertTrue(h.readEnabled())
            }

            // Duplicate OPEN (explicit broadcasts plus both receivers): same handle object, one session.
            open(session)
            Thread.sleep(500)
            assertEquals(1, count())
            assertSame(first, runtime.inspect { it.handleFor(session) })

            close(session)
            assertTrue("session not released", poll(3000) { count() == 0 })
            assertNull(runtime.inspect { it.handleFor(session) })
        } finally {
            rig?.release()
            scenario.close()
        }
    }

    @Test
    fun invalid_broadcasts_create_nothing() {
        val runtime = rt.runtime
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            assertTrue(poll(5000) { runtime.isAttached })
            val a = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
            fun send(action: String, session: Int?, pkg: String?) {
                val i = Intent(action)
                if (session != null) i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                if (pkg != null) i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, pkg)
                ctx.sendBroadcast(i)
            }
            send(a, 0, "com.example.app")
            send(a, -5, "com.example.app")
            send(a, null, "com.example.app")
            send(a, 123456, null)
            send(a, 123456, "com." + "a".repeat(300))
            send(a, 123456, "bad pkg name")
            send(a, 123456, "nodots")
            send("com.example.NOT_OUR_ACTION", 123456, "com.example.app")
            Thread.sleep(1000)
            assertEquals(0, count())
            assertEquals(0, runtime.status.value.sessionCount)
        } finally {
            scenario.close()
        }
    }
}
