package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.service.SessionIntent
import com.dylenq.eq.testsupport.EqComposeRule
import com.dylenq.eq.testsupport.FakeEffectFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * StatusStrip and BannerSlot against the real runtime (UI-SPEC checks 11 and 12). The rule's
 * GrantPermissionRule auto-grants POST_NOTIFICATIONS, so the permission input is a test-owned state
 * here: the banner flow is exercised through that input and the pure selector, not through the real
 * system dialog (see the plan summary for what this does and does not cover).
 */
@RunWith(AndroidJUnit4::class)
class StatusBannerTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose
    private val fake get() = rule.runtimeRule.factoryAs<FakeEffectFactory>()

    private val granted = mutableStateOf(false)
    private val sdk = mutableIntStateOf(36)
    private var starts = 0
    private var allows = 0
    private var settingsOpens = 0

    private fun host() = rule.host {
        val ui by holder.ui.collectAsStateWithLifecycle()
        Column(Modifier.padding(16.dp)) {
            StatusStrip({ ui }, onStart = { starts++ })
            BannerSlot(
                notice = ui.notice,
                permissionGranted = granted.value,
                prefs = rule.uiPrefs,
                onDismissNotice = holder::dismissNotice,
                onAllow = { allows++ },
                onOpenNotificationSettings = { settingsOpens++ },
                sdkInt = sdk.intValue,
            )
        }
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun awaitStatus(expected: String) {
        compose.waitUntil(8_000) { count("status-text") == 1 && text("status-text") == expected }
        assertEquals(expected, text("status-text"))
    }

    private fun awaitServiceRunning() {
        // MainActivity starts the real EqService, which attaches the runtime.
        compose.waitUntil(8_000) { rule.runtime.status.value.serviceRunning }
    }

    private fun open(session: Int) = rule.runtime.post(SessionIntent.Event.Open(session, "com.example.player$session"))

    // ---- status strip ------------------------------------------------------------------

    @Test fun statusStripShowsEachStateOfTheContract() {
        host()
        awaitServiceRunning()
        rule.runtime.detach()
        awaitStatus("EQ is not running")
        compose.onNodeWithTag("status-start").assertIsDisplayed()

        rule.runtime.attach()
        awaitStatus("Waiting for audio. Play something in another app.")
        assertEquals("Some apps, such as browsers, may not be detected.", text("status-hint"))
        assertEquals(0, count("status-start"))

        open(1)
        awaitStatus("Equalizing 1 app")
        assertEquals(0, count("status-hint"))
        open(2)
        open(3)
        awaitStatus("Equalizing 3 apps")

        rule.runtime.setGlobalMode(true)
        awaitStatus("Equalizing all audio")
        rule.runtime.setGlobalMode(false)
        awaitStatus("Equalizing 3 apps")

        holder.setBypass(true)
        awaitStatus("Bypassed. Audio plays unchanged.")
        holder.setBypass(false)
        awaitStatus("Equalizing 3 apps")
    }

    @Test fun startButtonCallsTheStartCallback() {
        host()
        awaitServiceRunning()
        rule.runtime.detach()
        awaitStatus("EQ is not running")
        compose.onNodeWithTag("status-start").performClick()
        assertEquals(1, starts)
    }

    @Test fun statusTextIsAPoliteLiveRegion() {
        host()
        awaitServiceRunning()
        compose.waitForIdle()
        val node = compose.onNodeWithTag("status-text").onParent().fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config[SemanticsProperties.LiveRegion])
    }

    // ---- banners -----------------------------------------------------------------------

    @Test fun aRuntimeNoticeIsADismissibleBannerShownVerbatim() {
        granted.value = true
        host()
        awaitServiceRunning()
        fake.failSession(0, RuntimeException("Error: -3"))
        rule.runtime.setGlobalMode(true)
        compose.waitUntil(8_000) { count("banner-notice") == 1 }
        val shown = text("banner-text")
        val rt = rule.runtime.status.value.notice!!
        assertTrue(rt, rt.startsWith("Global mode turned off: this phone rejected it ("))
        assertEquals(rt, shown)
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithTag("banner-dismiss").performClick()
        compose.waitUntil(5_000) { count("banner-notice") == 0 }
        assertNull(rule.runtime.status.value.notice)
    }

    @Test fun rationaleShowsFirstWithTheExactCopyAndDoesNotAskUntilAllow() {
        host()
        compose.waitUntil(5_000) { count("banner-rationale") == 1 }
        assertEquals("Show a notification?", text("banner-title"))
        assertEquals(
            "EQ runs in the background while other apps play. Android needs a notification so it stays running, " +
                "and it lets you switch the EQ off from there.",
            text("banner-text"),
        )
        compose.onNodeWithText("Not now").assertIsDisplayed()
        compose.onNodeWithText("Allow").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals("showing the rationale never opens the permission request", 0, allows)
        assertFalse(rule.uiPrefs.rationaleAnswered)
    }

    @Test fun allowCallsTheLauncherOnceAndAMissingPermissionLeadsToTheOffBanner() {
        host()
        compose.waitUntil(5_000) { count("banner-rationale") == 1 }
        compose.onNodeWithTag("rationale-allow").performClick()
        compose.waitForIdle()
        assertEquals(1, allows)
        assertTrue(rule.uiPrefs.rationaleAnswered)
        // The system said no (granted stays false): the off hint follows.
        compose.waitUntil(5_000) { count("banner-notifications-off") == 1 }
        // The permission arrives later (settings): the banner disappears.
        granted.value = true
        compose.waitUntil(5_000) { count("banner-notifications-off") == 0 }
        assertEquals(1, allows)
    }

    @Test fun notNowLeadsToTheOffBannerWithoutAskingAndItsActionsWork() {
        host()
        compose.waitUntil(5_000) { count("banner-rationale") == 1 }
        compose.onNodeWithTag("rationale-not-now").performClick()
        compose.waitUntil(5_000) { count("banner-notifications-off") == 1 }
        assertEquals(0, allows)
        assertEquals("Notifications are off. EQ still works, but you can't switch it off from the notification.", text("banner-text"))
        compose.onNodeWithText("Open settings").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").assertIsDisplayed()
        compose.onNodeWithTag("off-open-settings").performClick()
        assertEquals(1, settingsOpens)
        compose.onNodeWithTag("off-dismiss").performClick()
        compose.waitUntil(5_000) { count("banner-notifications-off") == 0 }
        assertTrue("dismissed for this launch only", rule.uiPrefs.offHintDismissed)
    }

    @Test fun anAnsweredRationaleGoesStraightToTheOffHintOnTheNextLaunch() {
        rule.uiPrefs.rationaleAnswered = true
        host()
        compose.waitUntil(5_000) { count("banner-notifications-off") == 1 }
        assertEquals(0, count("banner-rationale"))
    }

    @Test fun noPermissionBannerBelowApi33OrWhenGranted() {
        sdk.intValue = 30
        host()
        compose.waitForIdle()
        assertEquals(0, count("banner-rationale") + count("banner-notifications-off"))
        sdk.intValue = 36
        compose.waitUntil(5_000) { count("banner-rationale") == 1 }
        granted.value = true
        compose.waitUntil(5_000) { count("banner-rationale") == 0 }
    }

    @Test fun aNoticeOutranksTheRationale() {
        host()
        awaitServiceRunning()
        compose.waitUntil(5_000) { count("banner-rationale") == 1 }
        fake.failSession(0, RuntimeException("Error: -3"))
        rule.runtime.setGlobalMode(true)
        compose.waitUntil(8_000) { count("banner-notice") == 1 }
        assertEquals(0, count("banner-rationale"))
        compose.onNodeWithTag("banner-dismiss").performClick()
        compose.waitUntil(5_000) { count("banner-rationale") == 1 }
    }
}
