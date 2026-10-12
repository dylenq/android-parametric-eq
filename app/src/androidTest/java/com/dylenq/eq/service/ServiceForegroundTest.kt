package com.dylenq.eq.service

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.dylenq.eq.MainActivity
import com.dylenq.eq.testsupport.RuntimeRule
import java.io.FileInputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceForegroundTest {
    private val perm: GrantPermissionRule =
        if (Build.VERSION.SDK_INT >= 33) GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        else GrantPermissionRule.grant()
    private val rt = RuntimeRule()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(perm).around(rt)

    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun poll(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(50)
        }
        return cond()
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
    }

    private fun serviceDump() = shell("dumpsys activity services com.dylenq.eq")

    @Test
    fun the_service_stays_foreground_with_its_notification_after_the_activity_finishes() {
        val runtime = rt.runtime
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        assertTrue("service did not attach", poll(5000) { runtime.isAttached })
        scenario.close()
        Thread.sleep(1500) // the Activity is gone; give the system time to misbehave if it is going to

        try {
            assertTrue("runtime still attached", runtime.isAttached)
            val dump = serviceDump()
            assertTrue("EqService not in dump:\n$dump", dump.contains("EqService"))
            assertTrue("not foreground:\n$dump", dump.contains("isForeground=true"))
            if (Build.VERSION.SDK_INT >= 34) {
                val info = ctx.packageManager.getServiceInfo(ComponentName(ctx, EqService::class.java), 0)
                assertTrue(
                    "foregroundServiceType=${info.foregroundServiceType}",
                    info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
                )
            }
            assertTrue("notification missing", poll(3000) {
                nm.activeNotifications.any { it.id == EqService.NOTIFICATION_ID }
            })
        } finally {
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }

    @Test
    fun a_restart_without_extras_re_attaches_and_keeps_the_state() {
        val runtime = rt.runtime
        runtime.controller.setBypass(true)
        ctx.startForegroundService(Intent(ctx, EqService::class.java))
        try {
            assertTrue("service did not attach", poll(5000) { runtime.isAttached })
            // A second start with no action (what a redelivered start looks like) changes nothing.
            ctx.startForegroundService(Intent(ctx, EqService::class.java))
            Thread.sleep(500)
            assertTrue(runtime.isAttached)
            assertTrue("bypass state kept", runtime.controller.snapshot().bypass)
        } finally {
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }
}
