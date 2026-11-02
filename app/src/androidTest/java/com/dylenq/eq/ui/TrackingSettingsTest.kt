package com.dylenq.eq.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.service.TrackingControl
import com.dylenq.eq.service.TrackingState
import com.dylenq.eq.shizuku.GrantResult
import com.dylenq.eq.shizuku.ShizukuGateway
import com.dylenq.eq.shizuku.ShizukuState
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** DET-02 to DET-04: the Advanced player tracking section, with a fake controller and a fake Shizuku gateway. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class TrackingSettingsTest {
    @get:Rule val rule = EqComposeRule()
    private val compose: ComposeTestRule get() = rule.compose
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private class FakeShizuku(
        val state: ShizukuState,
        val accept: Boolean = true,
        val result: GrantResult = GrantResult.Granted,
    ) : ShizukuGateway {
        override fun state() = state
        override suspend fun request() = accept
        override suspend fun grant() = result
    }

    /** Permission flag behind the fake controller; a recheck publishes the state the real controller would. */
    private class FakeControl(val rt: com.dylenq.eq.EqRuntime) : TrackingControl {
        @Volatile var granted = false
        @Volatile var on = false
        @Volatile var rechecks = 0
        override fun setEnabled(on: Boolean) { this.on = on; publish() }
        override fun recheckPermission() { rechecks++; publish() }
        private fun publish() = rt.setTrackingState(
            when {
                !on -> TrackingState.Off
                !granted -> TrackingState.NeedsPermission
                else -> TrackingState.Working(2)
            },
        )
    }

    private fun openSettings() {
        compose.onNodeWithTag("overflow").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-item").performClick()
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun launchSettings(gateway: ShizukuGateway = FakeShizuku(ShizukuState.Ready)) {
        (ctx.applicationContext as EqApp).uiHolder().shizuku = gateway
        rule.launch(); compose.waitForIdle(); openSettings()
    }

    private fun show(state: TrackingState) {
        rule.runtime.setTrackingState(state)
        compose.waitUntil(5000) { rule.runtime.status.value.tracking == state }
        compose.waitForIdle()
    }

    private fun status() = compose.onNodeWithTag("tracking-status").performScrollTo()

    @Test fun offShowsOnlyTheSwitchAndStatus() {
        launchSettings()
        compose.onNodeWithTag("player-tracking-row").performScrollTo().assertIsOff()
        status().assertTextEquals("Off")
        assertFalse(exists("tracking-adb-command")); assertFalse(exists("tracking-copy")); assertFalse(exists("tracking-shizuku"))
    }

    @Test fun needsPermissionShowsTheRealCommandAndCopyPutsItOnTheClipboard() {
        launchSettings()
        show(TrackingState.NeedsPermission)
        val want = "adb shell pm grant com.dylenq.eq android.permission.DUMP"
        compose.onNodeWithTag("tracking-adb-command").performScrollTo().assertTextEquals(want)
        compose.onNodeWithTag("tracking-shizuku").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tracking-copy").performScrollTo().performClick()
        compose.waitForIdle()
        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE).let { (it as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() }
        }
        assertEquals(want, clip)
    }

    @Test fun workingAndRebootAndUnsupportedText() {
        launchSettings()
        show(TrackingState.Working(2))
        assertTrue(TrackingCopy.statusLine(TrackingState.Working(2)).contains("2 apps"))
        status().assertTextEquals(TrackingCopy.statusLine(TrackingState.Working(2)))
        assertFalse(exists("tracking-adb-command"))
        show(TrackingState.NeedsReboot)
        status().assertTextEquals("Reboot the phone once.")
        show(TrackingState.Unsupported("no app ids in this Android version"))
        status().assertTextEquals("Not supported on this phone: no app ids in this Android version")
    }

    @Test fun switchPersistsAndTellsTheController() {
        launchSettings()
        val control = FakeControl(rule.runtime).also { rule.runtime.trackingControl = it }
        compose.onNodeWithTag("player-tracking-row").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("player-tracking-row").assertIsOn()
        assertTrue(rule.runtimeRule.settings.playerTracking)
        assertTrue(control.on)
        compose.waitUntil(5000) { rule.runtime.status.value.tracking == TrackingState.NeedsPermission }
        assertTrue(exists("tracking-adb-command"))
    }

    private fun shizukuMessageFor(gateway: ShizukuGateway): String {
        launchSettings(gateway)
        show(TrackingState.NeedsPermission)
        compose.onNodeWithTag("tracking-shizuku").performScrollTo().performClick()
        compose.waitUntil(5000) { exists("tracking-shizuku-message") }
        var text = ""
        compose.onNodeWithTag("tracking-shizuku-message").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tracking-shizuku-message").fetchSemanticsNode().config.forEach { (k, v) ->
            if (k.name == "Text") text = (v as List<*>).joinToString("") { it.toString() }
        }
        return text
    }

    @Test fun notInstalledShowsItsMessage() =
        assertEquals(TrackingCopy.shizukuMessage(GrantResult.NotInstalled), shizukuMessageFor(FakeShizuku(ShizukuState.NotInstalled)))

    @Test fun notRunningShowsItsMessage() =
        assertEquals(TrackingCopy.shizukuMessage(GrantResult.NotRunning), shizukuMessageFor(FakeShizuku(ShizukuState.NotRunning)))

    @Test fun refusedShowsItsMessage() =
        assertEquals(TrackingCopy.shizukuMessage(GrantResult.Refused), shizukuMessageFor(FakeShizuku(ShizukuState.NoPermission, accept = false)))

    @Test fun successShowsItsMessage() =
        assertEquals(TrackingCopy.shizukuMessage(GrantResult.Granted), shizukuMessageFor(FakeShizuku(ShizukuState.Ready)))

    @Test fun grantWhileShownMovesToWorkingWithinThePollAndWithoutRecreation() {
        launchSettings()
        val control = FakeControl(rule.runtime).also { it.on = true; rule.runtime.trackingControl = it }
        show(TrackingState.NeedsPermission)
        val before = control.rechecks
        control.granted = true
        compose.waitUntil(TRACKING_POLL_MS * 2 + 2000) { rule.runtime.status.value.tracking == TrackingState.Working(2) }
        compose.waitForIdle()
        status().assertTextEquals(TrackingCopy.statusLine(TrackingState.Working(2)))
        assertTrue("the screen asked for a recheck", control.rechecks > before)
        assertFalse(exists("tracking-adb-command"))
    }
}
