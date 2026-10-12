package com.dylenq.eq.service

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootReceiverTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "eq_settings_boot_${System.nanoTime()}"
    private lateinit var settings: SettingsStore
    private val starts = AtomicInteger()
    private val oldStarter = BootReceiver.starter
    private val oldProvider = BootReceiver.settingsProvider

    @Before
    fun setUp() {
        settings = SettingsStore(ctx, prefsName)
        BootReceiver.settingsProvider = { settings }
        BootReceiver.starter = { starts.incrementAndGet() }
    }

    @After
    fun tearDown() {
        BootReceiver.starter = oldStarter
        BootReceiver.settingsProvider = oldProvider
        ctx.deleteSharedPreferences(prefsName)
    }

    private fun fire(action: String?) =
        BootReceiver().onReceive(ctx, if (action == null) null else Intent(action))

    @Test
    fun start_on_boot_defaults_to_off() {
        assertFalse(settings.startOnBoot)
        assertFalse(SettingsStore(ctx, "eq_settings_never_written_${System.nanoTime()}").startOnBoot)
    }

    @Test
    fun with_the_setting_off_neither_action_starts_anything() {
        fire(Intent.ACTION_BOOT_COMPLETED)
        fire(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(0, starts.get())
    }

    @Test
    fun with_the_setting_on_each_action_starts_exactly_once() {
        settings.startOnBoot = true
        fire(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(1, starts.get())
        fire(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(2, starts.get())
    }

    @Test
    fun other_actions_and_a_null_intent_start_nothing() {
        settings.startOnBoot = true
        fire("com.example.FAKE_BOOT")
        fire(Intent.ACTION_USER_PRESENT)
        fire(null)
        BootReceiver().onReceive(ctx, Intent())
        assertEquals(0, starts.get())
    }

    @Test
    fun a_denied_background_start_is_swallowed() {
        settings.startOnBoot = true
        BootReceiver.starter = { starts.incrementAndGet(); throw IllegalStateException("denied") }
        fire(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(1, starts.get())
    }

    @Test
    fun any_other_failure_in_the_starter_does_not_escape_either() {
        settings.startOnBoot = true
        BootReceiver.starter = { throw SecurityException("nope") }
        fire(Intent.ACTION_BOOT_COMPLETED)
        assertTrue(true)
    }

    @Test
    fun the_manifest_receiver_is_not_exported_and_listens_to_both_actions() {
        val pm = ctx.packageManager
        val info = pm.getReceiverInfo(
            android.content.ComponentName(ctx, BootReceiver::class.java), 0,
        )
        assertFalse(info.exported)
        val svc = pm.getServiceInfo(android.content.ComponentName(ctx, EqService::class.java), 0)
        assertFalse(svc.exported)
        val q = { a: String ->
            pm.queryBroadcastReceivers(Intent(a).setPackage(ctx.packageName), 0)
                .any { it.activityInfo.name == BootReceiver::class.java.name }
        }
        assertTrue(q(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(q(Intent.ACTION_MY_PACKAGE_REPLACED))
    }
}
