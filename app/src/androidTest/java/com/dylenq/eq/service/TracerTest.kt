package com.dylenq.eq.service

import android.Manifest
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.dylenq.eq.EqApp
import com.dylenq.eq.MainActivity
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.ToneRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TracerTest {
    @get:Rule
    val perm: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else GrantPermissionRule.grant()

    private fun poll(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    @Test
    fun open_broadcast_attaches_verified_dynamics_processing_and_close_releases() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = (ctx.applicationContext as EqApp).runtime()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var rig: ToneRig? = null
        try {
            assertTrue("service did not attach within 5 s", poll(5000) { runtime.isAttached })

            val core = runtime.controller
            core.edit {
                it.setMode(EqMode.PARAMETRIC)
                it.setBand(0u, Band(FilterKind.PEAK, 1000f, 6f, 4f, true))
            }

            rig = ToneRig().also { it.start() }
            val session = rig.sessionId
            ctx.sendBroadcast(
                Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
            )
            assertTrue("session never tracked", poll(3000) { runtime.inspect { it.sessionCount() } == 1 })
            Thread.sleep(500)
            assertEquals("both receivers delivered, still one session", 1, runtime.inspect { it.sessionCount() })

            val rate = ToneRig.nativeRate()
            val expected = core.engineParams(rate.toUInt(), core.snapshot().frameDurationMs)
            val n = expected.cutoffsHz.size
            val near1k = expected.cutoffsHz.indices.minByOrNull { Math.abs(expected.cutoffsHz[it] - 1000f) }!!
            val idx = listOf(0, 7, n / 2, n - 1, near1k).distinct()
            runtime.inspect { m ->
                val h = m.handleFor(session)!!
                for (i in idx) {
                    assertEquals("gain band $i", expected.gainsDb[i], h.readBandGain(i), 0.01f)
                    assertEquals("cutoff band $i", expected.cutoffsHz[i], h.readBandCutoff(i), 0.5f)
                }
                assertEquals("input gain", expected.inputGainDb, h.readInputGain(), 0.01f)
                assertTrue("enabled", h.readEnabled())
            }

            ctx.sendBroadcast(
                Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, ctx.packageName)
            )
            assertTrue("session not released", poll(3000) { runtime.inspect { it.sessionCount() } == 0 })
            assertNull(runtime.inspect { it.handleFor(session) })
        } finally {
            rig?.release()
            scenario.close()
        }
    }
}
