package com.dylenq.eq.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The notification action toggles bypass on real effects; measured through Visualizer(0). */
@RunWith(AndroidJUnit4::class)
class BypassTest {
    private val perm: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
        } else {
            GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
        }
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

    @Test
    fun the_notification_action_bypasses_and_re_enables_real_effects() {
        val runtime = rt.runtime
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var rig: ToneRig? = null
        var vis: android.media.audiofx.Visualizer? = null
        try {
            assertTrue("service did not attach", poll(5000) { runtime.isAttached })
            runtime.controller.edit {
                it.setMode(EqMode.PARAMETRIC)
                it.setBand(0u, Band(FilterKind.PEAK, 1000f, 6f, 4f, true))
            }
            rig = ToneRig().also { it.start() }
            vis = ToneRig.openVisualizer(0)

            val baseline = ToneRig.levelDb(vis, "bypass-baseline-no-effect")
            runtime.post(SessionIntent.Event.Open(rig.sessionId, ctx.packageName))
            assertTrue("session not tracked", poll(3000) { runtime.inspect { it.sessionCount() } == 1 })
            val boosted = ToneRig.levelDb(vis, "bypass-boosted")
            assertTrue("boost should be audible in the measurement: base=$baseline boosted=$boosted", boosted - baseline > 3.0)

            fun toggle() {
                val n = poll(5000) {
                    nm.activeNotifications.any { it.id == EqService.NOTIFICATION_ID && it.notification.actions?.size == 1 }
                }
                assertTrue("notification with action not found", n)
                val sn = nm.activeNotifications.first { it.id == EqService.NOTIFICATION_ID }
                sn.notification.actions[0].actionIntent.send()
            }
            fun enabled() = runtime.inspect { m -> m.handleFor(rig.sessionId)!!.readEnabled() }

            toggle()
            assertTrue("bypass not applied", poll(3000) { !enabled() })
            assertTrue(runtime.controller.snapshot().bypass)
            val bypassed = ToneRig.levelDb(vis, "bypass-after-action")
            assertEquals("bypassed level returns to the no-EQ baseline", baseline, bypassed, 1.0)
            assertTrue("action title flips to Enable", poll(3000) {
                nm.activeNotifications.firstOrNull { it.id == EqService.NOTIFICATION_ID }
                    ?.notification?.actions?.firstOrNull()?.title?.toString() == "Enable"
            })

            toggle()
            assertTrue("re-enable not applied", poll(3000) { enabled() })
            assertTrue(!runtime.controller.snapshot().bypass)
            val restored = ToneRig.levelDb(vis, "bypass-after-second-action")
            assertEquals("level back to boosted", boosted, restored, 1.0)
        } finally {
            vis?.release()
            rig?.release()
            scenario.close()
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }

    @Test
    fun bassAndBalanceSilencedByBypass() {
        val runtime = rt.runtime
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var rig: ToneRig? = null
        var vis: android.media.audiofx.Visualizer? = null
        try {
            assertTrue("service did not attach", poll(5000) { runtime.isAttached })
            rig = ToneRig(channels = ToneRig.Channels.LEFT_ONLY).also { it.start() }
            vis = ToneRig.openVisualizer(0)
            val baseline = ToneRig.levelDb(vis, "tone-bypass-baseline")
            runtime.controller.edit { it.setBass(12f); it.setBalance(1f) }
            runtime.post(SessionIntent.Event.Open(rig.sessionId, ctx.packageName))
            assertTrue("session not tracked", poll(3000) { runtime.inspect { it.sessionCount() } == 1 })
            Thread.sleep(300)
            val active = ToneRig.levelDb(vis, "tone-bypass-active")
            assertTrue("balance should attenuate: base=$baseline active=$active", baseline - active > 40.0)

            runtime.controller.setBypass(true)
            assertTrue("bypass not applied", poll(3000) { !runtime.inspect { m -> m.handleFor(rig.sessionId)!!.readEnabled() } })
            val bypassed = ToneRig.levelDb(vis, "tone-bypass-bypassed")
            assertEquals("bypass restores flat baseline", baseline, bypassed, 0.5)

            runtime.controller.setBypass(false)
            assertTrue("re-enable not applied", poll(3000) { runtime.inspect { m -> m.handleFor(rig.sessionId)!!.readEnabled() } })
            val restored = ToneRig.levelDb(vis, "tone-bypass-restored")
            assertEquals("attenuation restored", active, restored, 1.0)
        } finally {
            vis?.release()
            rig?.release()
            scenario.close()
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }
}
