package com.dylenq.eq.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.MainActivity
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.service.SessionIntent
import com.dylenq.eq.testsupport.EqComposeRule
import com.dylenq.eq.testsupport.FakeEffectFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Settings "Apps" section through the real MainActivity, with the fake effect factory. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AppsSettingsTest {
    @get:Rule val rule = EqComposeRule()
    private val compose: ComposeTestRule get() = rule.compose
    private val fake get() = rule.runtimeRule.factoryAs<FakeEffectFactory>()
    private val ctl get() = rule.runtime.controller

    private fun openSettings() {
        compose.onNodeWithTag("overflow").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-item").performClick()
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun existsText(t: String) = compose.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty()

    private fun open(session: Int, pkg: String) = rule.runtime.post(SessionIntent.Event.Open(session, pkg))

    private fun poll(ms: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun scrollToApp(pkg: String) {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("app-row-$pkg"))
    }

    private fun makePreset(name: String, gain: Float): String {
        ctl.edit {
            it.setMode(EqMode.PARAMETRIC)
            it.setBand(0u, Band(FilterKind.PEAK, 250f, gain, 1f, true))
        }
        val key = ctl.savePreset(name)
        ctl.resetBands()
        return key
    }

    @Test fun twoPackagesGiveTwoRowsThePlayingOneFirstAndAnUninstalledOneShowsItsPackage() {
        rule.launch(); compose.waitForIdle(); openSettings()
        open(1, "com.example.one")
        open(2, "com.example.two")
        compose.waitUntil(5000) { exists("app-row-com.example.one") && exists("app-row-com.example.two") }
        assertTrue("package text shown for an app that is not installed", existsText("com.example.one"))
        assertTrue(exists("apps-count"))
        // com.example.one stops playing; the one still playing moves to the top.
        rule.runtime.post(SessionIntent.Event.Close(1, "com.example.one"))
        compose.waitUntil(5000) {
            val a = rule.runtime.status.value.apps
            a.firstOrNull { it.pkg == "com.example.one" }?.playingNow == false && exists("app-row-com.example.two")
        }
        compose.waitForIdle()
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("app-row-com.example.two") < top("app-row-com.example.one"))
    }

    @Test fun theSwitchTurnsTheAppOffAndHasRoleStateAndATouchTarget() {
        rule.launch(); compose.waitForIdle(); openSettings()
        open(1, "com.a")
        compose.waitUntil(5000) { exists("app-switch-com.a") && fake.handle(1) != null }
        compose.onNodeWithTag("app-switch-com.a").assertIsOn()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("app-switch-com.a").performClick()
        assertTrue("effect released", poll { fake.callCount(1, "release") == 1 })
        compose.waitForIdle()
        compose.onNodeWithTag("app-switch-com.a").assertIsOff()
        assertFalse(rule.runtime.status.value.apps.first { it.pkg == "com.a" }.enabled)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("session-row-1"))
        assertTrue(existsText("Session 1 · Turned off for this app"))
    }

    @Test fun thePickerAssignsAPresetAndLeavesTheMainCurveAlone() {
        val key = makePreset("Warm", 9f)
        rule.launch(); compose.waitForIdle()
        val before = ctl.snapshot()
        openSettings()
        open(1, "com.a")
        compose.waitUntil(5000) { exists("app-preset-com.a") && fake.handle(1) != null }
        val liveCreate = fake.creates().last { it.first == 1 }.second
        assertTrue(existsText("Live EQ (default)"))
        compose.onNodeWithTag("app-preset-com.a").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app-preset-com.a-live").assertExists()
        compose.onNodeWithTag("app-preset-com.a-$key").performClick()
        assertTrue(poll { rule.runtime.status.value.apps.first { it.pkg == "com.a" }.presetKey == key })
        assertTrue(poll { fake.creates().last { it.first == 1 }.second != liveCreate })
        assertNotEquals(liveCreate.gainsDb, fake.creates().last { it.first == 1 }.second.gainsDb)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("session-row-1"))
        compose.waitUntil(5000) { existsText("Session 1 · Equalizing · Warm") }
        assertEquals("the live EQ is untouched", before.graphicGainsDb, ctl.snapshot().graphicGainsDb)
        assertEquals(before.bands, ctl.snapshot().bands)
        assertEquals(before.activePresetKey, ctl.snapshot().activePresetKey)
    }

    @Test fun deletingTheAssignedPresetReturnsTheRowToLiveEq() {
        val key = makePreset("Warm", 9f)
        rule.launch(); compose.waitForIdle(); openSettings()
        open(1, "com.a")
        compose.waitUntil(5000) { exists("app-preset-com.a") }
        rule.runtime.setAppPreset("com.a", key)
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("app-preset-com.a") and hasText("Warm")).fetchSemanticsNodes().isNotEmpty() }
        ctl.deletePreset(key)
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("app-preset-com.a") and hasText("Live EQ (default)")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(null, rule.runtime.status.value.apps.first { it.pkg == "com.a" }.presetKey)
    }

    @Test fun theEmptyStateExplainsWhatFillsTheList() {
        rule.launch(); compose.waitForIdle(); openSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("apps-empty"))
        assertTrue(existsText(AppsCopy.EMPTY_BODY))
        assertTrue(existsText(AppsCopy.FOLLOW_NOTE))
        assertFalse(exists("apps-global-note"))
    }

    @Test fun globalModeShowsTheIgnoreNote() {
        rule.launch(); compose.waitForIdle(); openSettings()
        rule.runtime.setGlobalMode(true)
        compose.waitUntil(5000) { exists("apps-global-note") }
        assertTrue(existsText(AppsCopy.GLOBAL_NOTE))
    }

    @Test fun aSpoofedNameIsPlainTextWithNoClickAction() {
        val spoof = "<b>x</b> http://evil"
        rule.launch(); compose.waitForIdle(); openSettings()
        open(9, spoof)
        compose.waitUntil(5000) { exists("app-row-$spoof") }
        scrollToApp(spoof)
        compose.waitForIdle()
        val row = compose.onNodeWithTag("app-row-$spoof").fetchSemanticsNode()
        assertFalse(row.config.contains(SemanticsActions.OnClick))
        assertTrue(compose.onAllNodes(hasText(spoof) and hasClickAction()).fetchSemanticsNodes().isEmpty())
        assertTrue(existsText(spoof))
    }

    private fun awaitOrientation(scenario: ActivityScenario<MainActivity>, orientation: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            var current = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity { current = it.resources.configuration.orientation }
            if (current == orientation) return
            Thread.sleep(100)
        }
        throw AssertionError("The activity never reached orientation $orientation")
    }

    @Test fun rotationKeepsTheSwitchAndThePreset() {
        val key = makePreset("Warm", 9f)
        val scenario = rule.launch(); compose.waitForIdle(); openSettings()
        open(1, "com.a")
        compose.waitUntil(5000) { exists("app-row-com.a") }
        rule.runtime.setAppPreset("com.a", key)
        rule.runtime.setAppEnabled("com.a", false)
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("app-preset-com.a") and hasText("Warm")).fetchSemanticsNodes().isNotEmpty() }
        try {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
            compose.waitForIdle()
            scrollToApp("com.a")
            compose.onNodeWithTag("app-switch-com.a").assertIsOff()
            assertTrue(compose.onAllNodes(hasTestTag("app-preset-com.a") and hasText("Warm")).fetchSemanticsNodes().isNotEmpty())
        } finally {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    @Test fun aFloodOfSpoofedOpensKeepsTheRuleAndTheListBounded() {
        val key = makePreset("Warm", 9f)
        rule.launch(); compose.waitForIdle()
        open(1, "com.mine")
        assertTrue(poll { rule.runtime.status.value.apps.any { it.pkg == "com.mine" } })
        assertEquals(null, rule.runtime.setAppPreset("com.mine", key))
        for (i in 0 until 100) open(1000 + i, "com.spoof.n$i")
        assertTrue(poll { rule.runtime.status.value.sessionCount > 0 })
        Thread.sleep(1500)
        val apps = rule.runtime.status.value.apps
        assertTrue("list bounded, was ${apps.size}", apps.size <= 64)
        assertEquals(key, apps.firstOrNull { it.pkg == "com.mine" }?.presetKey)
    }
}
