package com.dylenq.eq.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.service.OutputRoute
import com.dylenq.eq.service.RouteInfo
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Settings "Output devices" section through the real MainActivity, with a fake output route. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class DevicesSettingsTest {
    private class FakeRoute(@Volatile var info: RouteInfo) : OutputRoute {
        override fun current() = info
    }

    private val buds = RouteInfo("bluetooth:Buds", "Bluetooth: Buds")
    private val route = FakeRoute(RouteInfo.SPEAKER)

    @get:Rule val rule = EqComposeRule(route)
    private val compose: ComposeTestRule get() = rule.compose
    private val ctl get() = rule.runtime.controller

    private fun openSettings() {
        compose.onNodeWithTag("overflow").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings-item").performClick()
        compose.waitForIdle()
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun existsText(t: String) = compose.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty()

    private fun makePreset(name: String, gain: Float): String {
        ctl.edit {
            it.setMode(EqMode.PARAMETRIC)
            it.setBand(0u, Band(FilterKind.PEAK, 250f, gain, 1f, true))
        }
        val key = ctl.savePreset(name)
        ctl.resetBands()
        return key
    }

    private fun showDevices() {
        rule.runtime.onRouteChanged()
        compose.waitUntil(5000) { rule.runtime.status.value.devices.any { it.key == "speaker" } }
        route.info = buds
        rule.runtime.onRouteChanged()
        compose.waitUntil(5000) { rule.runtime.status.value.devices.any { it.current && it.key == buds.key } }
    }

    @Test fun twoSeenDevicesAreListedTheCurrentOneMarkedAndTheNoteIsShown() {
        rule.launch(); compose.waitForIdle()
        showDevices()
        openSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("device-row-speaker"))
        compose.waitUntil(5000) { exists("device-row-speaker") && exists("device-row-${buds.key}") }
        assertTrue(exists("devices-count"))
        assertTrue(exists("device-current-${buds.key}"))
        assertTrue(!exists("device-current-speaker"))
        assertTrue(existsText("Bluetooth: Buds"))
        assertTrue(existsText("Phone speaker"))
        assertTrue(existsText(DevicesCopy.NOTE))
        // The current device sorts first.
        val top = { tag: String -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("device-row-${buds.key}") < top("device-row-speaker"))
    }

    @Test fun thePickerListsNoneFirstChoosesAPresetAndClearsIt() {
        val key = makePreset("Warm", 9f)
        rule.launch(); compose.waitForIdle()
        showDevices()
        openSettings()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("device-preset-speaker"))
        assertTrue(existsText(DevicesCopy.NO_PRESET))
        compose.onNodeWithTag("device-preset-speaker").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("device-preset-speaker-none").assertExists()
        compose.onNodeWithTag("device-preset-speaker-$key").performClick()
        compose.waitUntil(5000) { rule.runtime.status.value.devices.first { it.key == "speaker" }.presetKey == key }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("device-preset-speaker") and hasText("Warm")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("device-preset-speaker").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("device-preset-speaker-none").performClick()
        compose.waitUntil(5000) { rule.runtime.status.value.devices.first { it.key == "speaker" }.presetKey == null }
        assertNull(rule.runtime.status.value.devices.first { it.key == "speaker" }.presetKey)
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("device-preset-speaker") and hasText(DevicesCopy.NO_PRESET)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, rule.runtime.status.value.devices.size)
    }
}
