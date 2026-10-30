package com.dylenq.eq.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.service.SessionIntent
import com.dylenq.eq.testsupport.EqComposeRule
import com.dylenq.eq.testsupport.FakeEffectFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** PEQ-07 through the real MainActivity (EqRoot), with the fake effect factory. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SettingsTest {
    private val rejecting = FakeEffectFactory().also { it.failSession(0, IllegalStateException("x")) }

    @get:Rule val rule = EqComposeRule()
    private val compose: ComposeTestRule get() = rule.compose

    private fun openSettings() {
        compose.onNodeWithTag("overflow").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-item").performClick()
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun existsText(t: String, sub: Boolean = false) =
        compose.onAllNodes(hasText(t, substring = sub)).fetchSemanticsNodes().isNotEmpty()

    @Test fun overflowSettingsOpensSettingsAndHidesTheMainScreen() {
        rule.launch(); compose.waitForIdle()
        assertTrue(exists("eq-column") || exists("two-pane"))
        openSettings()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        assertTrue(exists("settings-list"))
        assertFalse("main screen not shown", exists("eq-column") || exists("two-pane"))
        compose.onNodeWithTag("settings-back").assertIsDisplayed()
    }

    @Test fun backArrowAndSystemBackReturnToTheUnchangedMainScreen() {
        rule.runtime.controller.edit { it.setMode(EqMode.PARAMETRIC) }
        rule.launch(); compose.waitForIdle()
        val before = rule.runtime.controller.snapshot()
        openSettings()
        compose.onNodeWithTag("settings-back").performClick(); compose.waitForIdle()
        assertFalse(exists("settings-list"))
        assertTrue(exists("eq-column") || exists("two-pane"))
        assertEquals(EqMode.PARAMETRIC, rule.runtime.controller.snapshot().mode)

        openSettings()
        Espresso.pressBack(); compose.waitForIdle()
        assertFalse(exists("settings-list"))
        assertTrue(exists("eq-column") || exists("two-pane"))
        assertEquals(before.mode, rule.runtime.controller.snapshot().mode)
        assertEquals(before.bypass, rule.runtime.controller.snapshot().bypass)
    }

    @Test fun settingsSurvivesRecreationAndSoDoesTheClosedState() {
        val sc = rule.launch(); compose.waitForIdle()
        openSettings()
        sc.recreate(); compose.waitForIdle()
        assertTrue("Settings still open after recreation", exists("settings-list"))
        compose.onNodeWithTag("settings-back").performClick(); compose.waitForIdle()
        sc.recreate(); compose.waitForIdle()
        assertFalse(exists("settings-list"))
        assertTrue(exists("eq-column") || exists("two-pane"))
    }

    @Test fun globalModeAcceptedKeepsTheSwitchOnAndDrivesTheRuntime() {
        rule.launch(); compose.waitForIdle(); openSettings()
        compose.onNodeWithTag("global-mode").assertIsOff()
        compose.onNodeWithTag("global-mode").performClick()
        compose.waitUntil(5000) { rule.runtime.status.value.globalMode }
        compose.waitForIdle()
        compose.onNodeWithTag("global-mode").assertIsOn()
        assertTrue(rule.runtimeRule.settings.globalMode)
        assertFalse(exists("banner-notice"))
        compose.onNodeWithTag("global-mode").performClick()
        compose.waitUntil(5000) { !rule.runtime.status.value.globalMode }
        compose.onNodeWithTag("global-mode").assertIsOff()
        assertFalse(rule.runtimeRule.settings.globalMode)
    }

    @Test fun startOnBootPersistsToTheSettingsStore() {
        rule.launch(); compose.waitForIdle(); openSettings()
        compose.onNodeWithTag("start-on-boot").performScrollTo().assertIsOff()
        assertFalse("defaults to off", rule.runtimeRule.settings.startOnBoot)
        compose.onNodeWithTag("start-on-boot").performClick(); compose.waitForIdle()
        assertTrue(rule.runtimeRule.settings.startOnBoot)
        compose.onNodeWithTag("start-on-boot").assertIsOn()
        compose.onNodeWithTag("start-on-boot").performClick(); compose.waitForIdle()
        assertFalse(rule.runtimeRule.settings.startOnBoot)
    }

    @Test fun supportingTextIsTheHonestCopyVerbatim() {
        rule.launch(); compose.waitForIdle(); openSettings()
        compose.onNodeWithText(GLOBAL_MODE_SUPPORTING).assertExists()
        compose.onNodeWithText(START_ON_BOOT_SUPPORTING).performScrollTo().assertExists()
        assertTrue(existsText("Global mode (legacy)"))
        assertTrue(existsText("Start when the phone turns on"))
        assertTrue(GLOBAL_MODE_SUPPORTING.contains("deprecated Android feature and may not work on some phones"))
        assertTrue(GLOBAL_MODE_SUPPORTING.contains("replaces the per-app effects"))
    }

    @Test fun emptySessionsShowTheEmptyBlock() {
        rule.launch(); compose.waitForIdle(); openSettings()
        compose.onNodeWithTag("sessions-empty").performScrollTo().assertIsDisplayed()
        assertTrue(existsText("No audio sessions detected"))
        assertTrue(existsText("Play something in another app", sub = true))
        assertFalse(exists("sessions-count"))
    }

    @Test fun aSessionRowShowsPackageSessionIdAndStateWithNoLink() {
        rule.launch(); compose.waitForIdle(); openSettings()
        rule.runtime.post(SessionIntent.Event.Open(1234, "com.example.player"))
        compose.waitUntil(5000) { exists("session-row-1234") }
        compose.onNodeWithTag("session-row-1234").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("com.example.player").assertExists()
        compose.onNodeWithText("Session 1234 · Equalizing").assertExists()
        compose.onNodeWithTag("sessions-count").assertExists()
        compose.onNodeWithText("1 session").assertExists()
        assertFalse(exists("sessions-empty"))
        // Attacker-influenced text: no click action, no link semantics anywhere on the row.
        val row = compose.onNodeWithTag("session-row-1234").fetchSemanticsNode()
        assertFalse(row.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        assertTrue(compose.onAllNodes(hasTestTag("session-row-1234") and hasClickAction()).fetchSemanticsNodes().isEmpty())
        assertFalse(row.config.contains(SemanticsProperties.Role))
    }

    @Test fun aboutRowHasNameVersionGplAndNoUrl() {
        rule.launch(); compose.waitForIdle(); openSettings()
        compose.onNodeWithTag("about-row").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("EQ for Android").assertExists()
        val core = rule.runtime.controller.coreVersion()
        compose.onNodeWithText("core $core", substring = true).assertExists()
        compose.onNodeWithText("GPL-3.0", substring = true).assertExists()
        compose.onNodeWithText("Version ", substring = true).assertExists()
        assertFalse("no repository URL", existsText("http", sub = true))
        val about = compose.onNodeWithTag("about-row").fetchSemanticsNode()
        assertFalse(about.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
    }

    @Test fun noFrameDurationOrLatencyControlIsOffered() {
        rule.launch(); compose.waitForIdle(); openSettings()
        assertFalse(existsText("frame", sub = true))
        assertFalse(existsText("latency", sub = true))
    }

    @Test fun mainScreenStateIsKeptAcrossTheSettingsRoundTrip() {
        rule.runtime.controller.edit { it.setMode(EqMode.PARAMETRIC) }
        rule.launch(); compose.waitForIdle()
        rule.runtime.controller.edit { it.setPreamp(-3f) }
        val holder = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as com.dylenq.eq.EqApp).uiHolder()
        holder.selectBand(2)
        compose.waitForIdle()
        openSettings()
        compose.onNodeWithTag("settings-back").performClick(); compose.waitForIdle()
        assertEquals(-3f, rule.runtime.controller.snapshot().preampDb, 0.001f)
        assertEquals(EqMode.PARAMETRIC, rule.runtime.controller.snapshot().mode)
        assertEquals(2, holder.ui.value.selectedBand)
        assertTrue(exists("eq-column") || exists("two-pane"))
    }

    @Test fun engineBandsLineShowsTheMeasuredCount() {
        rule.runtimeRule.settings.recordEngineProbe(512, android.os.Build.FINGERPRINT)
        rule.launch(); compose.waitForIdle()
        openSettings()
        compose.onNodeWithTag("engine-bands").performScrollTo().assertIsDisplayed()
        assertTrue(existsText("Engine bands: 512 (measured)"))
    }

    @Test fun engineBandsLineWithoutMeasurementSaysNotMeasured() {
        rule.launch(); compose.waitForIdle()
        openSettings()
        compose.onNodeWithTag("engine-bands").performScrollTo().assertIsDisplayed()
        assertTrue(existsText("Engine bands: not measured yet"))
    }
}

/** The rejection path needs a factory that refuses session 0 (global mode), so it has its own rule. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class SettingsRejectionTest {
    @get:Rule val rule = EqComposeRule { FakeEffectFactory().also { it.failSession(0, IllegalStateException("x")) } }
    private val compose: ComposeTestRule get() = rule.compose
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test fun aRejectedGlobalModeReturnsTheSwitchToOffAndExplainsItInsideSettings() {
        rule.launch(); compose.waitForIdle()
        compose.onNodeWithTag("overflow").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("settings-item").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("global-mode").performClick()
        compose.waitUntil(5000) { exists("banner-notice") }
        compose.waitForIdle()
        compose.onNodeWithTag("global-mode").assertIsOff()
        compose.onNodeWithText("Global mode turned off: this phone rejected it", substring = true).assertIsDisplayed()
        assertFalse(rule.runtime.status.value.globalMode)
        assertFalse(rule.runtimeRule.settings.globalMode)
        compose.onNodeWithTag("banner-dismiss").performClick()
        compose.waitUntil(5000) { !exists("banner-notice") }
        assertEquals(null, rule.runtime.status.value.notice)
    }
}
