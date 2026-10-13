package com.dylenq.eq.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.testsupport.FakeEffectFactory
import com.dylenq.eq.testsupport.RuntimeRule
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Global mode against the recording fake (suppression, fallback) and once against the real factory. */
@RunWith(AndroidJUnit4::class)
class GlobalModeTest {
    private val perm: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else GrantPermissionRule.grant()
    private val rt = RuntimeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(perm).around(rt)

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fake get() = rt.factoryAs<FakeEffectFactory>()

    private fun poll(ms: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun open(session: Int) = rt.runtime.post(SessionIntent.Event.Open(session, "com.example.player"))
    private fun count() = rt.runtime.inspect { it.sessionCount() }

    @Test
    fun global_on_attaches_session_zero_and_suppresses_per_session_effects() {
        open(5)
        assertTrue(poll { count() == 1 })
        val h5 = fake.handle(5)!!
        rt.runtime.setGlobalMode(true)
        assertTrue(poll { rt.runtime.status.value.globalMode })
        assertEquals(1, fake.callCount(0, "create"))
        assertTrue("existing per-session effect released", h5.released)

        open(6)
        assertTrue(poll { count() == 2 })
        assertEquals("a session opened while global is on gets no handle", 0, fake.callCount(6, "create"))
        assertEquals(1, fake.callCount(5, "create"))
        assertTrue(rt.settings.globalMode)
        assertTrue(rt.runtime.status.value.sessions.all { it.state == SessionState.SUPPRESSED })
    }

    @Test
    fun turning_global_off_by_hand_restores_per_session_effects() {
        open(5)
        assertTrue(poll { count() == 1 })
        rt.runtime.setGlobalMode(true)
        assertTrue(poll { rt.runtime.status.value.globalMode })
        open(6)
        assertTrue(poll { count() == 2 })
        val h0 = fake.handle(0)!!

        rt.runtime.setGlobalMode(false)
        assertTrue(poll { !rt.runtime.status.value.globalMode && fake.handle(6) != null })
        assertTrue("session 0 released", poll { h0.released })
        assertEquals(2, fake.callCount(5, "create"))
        assertEquals(1, fake.callCount(6, "create"))
        assertFalse(fake.handle(5)!!.released)
        assertFalse(fake.handle(6)!!.released)
        assertFalse(rt.settings.globalMode)
        assertNull("manual off is not a rejection", rt.runtime.status.value.notice)
    }

    private fun assertRejectedAndRestored() {
        open(5)
        assertTrue(poll { count() == 1 })
        rt.runtime.setGlobalMode(true)
        assertTrue("notice never published", poll { rt.runtime.status.value.notice != null })
        val st = rt.runtime.status.value
        assertTrue(st.notice!!, st.notice!!.startsWith("Global mode turned off"))
        assertFalse(st.globalMode)
        assertFalse("setting must be persisted off", rt.settings.globalMode)
        assertTrue(poll { fake.callCount(5, "create") == 2 })
        assertFalse("per-session effect is back", fake.handle(5)!!.released)
        assertEquals(1, count())
    }

    @Test
    fun a_create_exception_turns_global_off_with_a_notice() {
        fake.failSession(0, RuntimeException("Error: -3"))
        assertRejectedAndRestored()
        assertTrue(rt.runtime.status.value.notice!!.contains("-3"))
    }

    @Test
    fun missing_control_turns_global_off_with_a_notice() {
        fake.controlLost(0)
        assertRejectedAndRestored()
        assertTrue(fake.handle(0)!!.released)
    }

    @Test
    fun a_refused_enable_turns_global_off_with_a_notice() {
        fake.rejectEnable(0)
        assertRejectedAndRestored()
        assertTrue(fake.handle(0)!!.released)
    }

    @Test
    fun the_persisted_setting_is_applied_when_a_runtime_starts() {
        rt.settings.globalMode = true
        val f = FakeEffectFactory()
        val r2 = EqRuntime(f, File(rt.dir, "second").also { it.mkdirs() }, rt.settings)
        try {
            // The status is now seeded from the setting, so wait for the worker (which applies it
            // before its first publish) and check the manager really is in global mode.
            assertTrue(r2.inspect { it.isGlobalOn() })
            assertTrue(poll { r2.status.value.globalMode })
            assertEquals(1, f.callCount(0, "create"))
        } finally {
            r2.shutdown()
        }
    }

    @Test
    fun the_global_handle_follows_edits_bypass_and_frame_changes() {
        rt.runtime.setGlobalMode(true)
        assertTrue(poll { rt.runtime.status.value.globalMode })
        val c = rt.runtime.controller
        val before = c.engineParams(48000u, c.snapshot().frameDurationMs)
        c.edit { it.setGraphicGain(3u, 6f) }
        val after = c.engineParams(48000u, c.snapshot().frameDurationMs)
        val idx = after.gainsDb.indices.maxByOrNull { Math.abs(after.gainsDb[it] - before.gainsDb[it]) }!!
        assertTrue(Math.abs(after.gainsDb[idx] - before.gainsDb[idx]) > 0.5f)
        assertTrue(poll { rt.runtime.inspect { Math.abs(it.handleFor(0)!!.readBandGain(idx) - after.gainsDb[idx]) < 0.01f } })

        c.setBypass(true)
        assertTrue(poll { !rt.runtime.inspect { it.handleFor(0)!!.readEnabled() } })
        c.setBypass(false)
        assertTrue(poll { rt.runtime.inspect { it.handleFor(0)!!.readEnabled() } })

        val firstHandle = fake.handle(0)
        c.edit { it.setFrameDurationMs(170.6f) }
        assertTrue("recreated on a frame change", poll { fake.callCount(0, "create") == 2 })
        assertTrue(firstHandle!!.released)
        assertTrue(rt.runtime.status.value.globalMode)
    }

    @Test
    fun the_real_factory_accepts_session_zero_on_the_emulator() {
        val r = EqRuntime(RealEffectFactory(), File(rt.dir, "real").also { it.mkdirs() }, SettingsStore(null))
        try {
            r.setGlobalMode(true)
            assertTrue("global mode rejected: ${r.status.value.notice}", poll { r.status.value.globalMode })
            r.inspect { m ->
                val h = m.handleFor(0)
                assertNotNull(h)
                assertTrue(h!!.hasControl())
                assertTrue(h.readEnabled())
            }
        } finally {
            r.shutdown()
        }
    }

    @Test
    fun the_notification_text_follows_the_notice_while_the_service_runs() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        ctx.startForegroundService(Intent(ctx, EqService::class.java))
        try {
            fun text() = nm.activeNotifications.firstOrNull { it.id == EqService.NOTIFICATION_ID }
                ?.notification?.extras?.getString(Notification.EXTRA_TEXT)
            assertTrue("notification never posted", poll(5000) { text() != null })
            assertTrue(text()!!, poll { text()!!.startsWith("On - ") })

            fake.failSession(0, RuntimeException("Error: -3"))
            rt.runtime.setGlobalMode(true)
            assertTrue("notification: ${text()}", poll(5000) { text()?.startsWith("Global mode turned off") == true })
        } finally {
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }

    @Test
    fun status_text_covers_each_state() {
        assertEquals("On - 2 apps", NotificationFactory.statusText(ServiceStatus(sessionCount = 2)))
        assertEquals("On - 1 app", NotificationFactory.statusText(ServiceStatus(sessionCount = 1)))
        assertEquals("Bypassed", NotificationFactory.statusText(ServiceStatus(bypass = true, sessionCount = 2)))
        assertEquals("On - all audio", NotificationFactory.statusText(ServiceStatus(globalMode = true)))
        assertEquals("hello", NotificationFactory.statusText(ServiceStatus(notice = "hello", bypass = true)))
    }
}
