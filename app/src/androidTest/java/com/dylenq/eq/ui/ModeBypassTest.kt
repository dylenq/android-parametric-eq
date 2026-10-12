package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Mode switch, Parametric placeholder, band-count slot and bypass (UI-SPEC checks 9 and 10). */
@RunWith(AndroidJUnit4::class)
class ModeBypassTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose
    private val ctrl get() = rule.runtime.controller

    /** Top bar, strip, curve, mode row and the mode content region, as the screen arranges them. */
    private fun hostScreen() = rule.host {
        val ui by holder.ui.collectAsStateWithLifecycle()
        Column(Modifier.padding(16.dp)) {
            EqTopBar({ ui }, holder::setBypass, onResetAll = {})
            StatusStrip({ ui }, onStart = {})
            CurveCard({ ui }, holder::applySuggestedPreamp)
            ModeRow(ui.mode, ui.graphicBandCount, holder::setMode, holder::setBandCount)
            if (ui.mode == EqMode.GRAPHIC) {
                GraphicSliderPanel(
                    state = { ui },
                    onDragStart = holder::onBandDragStart,
                    onChange = holder::onBandDragChange,
                    onDragEnd = holder::onBandDragEnd,
                    onReset = holder::resetBand,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
            } else {
                ParametricPlaceholder(ui.hasEnabledParametricBands)
            }
        }
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    private fun clearParametricBands() {
        while (ctrl.snapshot().bands.isNotEmpty()) ctrl.edit { it.removeBand(0u) }
    }

    @Test fun segmentedButtonsCallSetMode() {
        hostScreen()
        compose.onNodeWithTag("mode-parametric").performClick()
        compose.waitForIdle()
        assertEquals(EqMode.PARAMETRIC, ctrl.snapshot().mode)
        compose.onNodeWithTag("mode-graphic").performClick()
        compose.waitForIdle()
        assertEquals(EqMode.GRAPHIC, ctrl.snapshot().mode)
    }

    @Test fun parametricWithBandsShowsThePlaceholderAndKeepsTheCurve() {
        clearParametricBands()
        ctrl.edit { it.addBand() }
        hostScreen()
        compose.onNodeWithTag("mode-parametric").performClick()
        compose.waitForIdle()
        assertTrue(ctrl.snapshot().bands.any { it.enabled })
        assertEquals("Parametric editing isn't available yet", text("parametric-title"))
        assertEquals(
            "The curve above shows your current parametric bands. Switch back to Graphic to shape the sound.",
            text("parametric-body"),
        )
        compose.onNodeWithTag("curve").assertIsDisplayed()
    }

    @Test fun parametricWithoutBandsUsesTheEmptyCopy() {
        clearParametricBands()
        hostScreen()
        compose.onNodeWithTag("mode-parametric").performClick()
        compose.waitForIdle()
        assertEquals("Parametric editing isn't available yet", text("parametric-title"))
        assertEquals(
            "There are no parametric bands yet, so the curve is flat. Switch back to Graphic to shape it.",
            text("parametric-body"),
        )
    }

    @Test fun bandCountButtonHidesWithoutShiftingTheRow() {
        hostScreen()
        compose.waitForIdle()
        assertEquals(1, count("band-count-button"))
        val rowBefore = compose.onNodeWithTag("mode-row").getUnclippedBoundsInRoot()
        val segBefore = compose.onNodeWithTag("mode-segmented").getUnclippedBoundsInRoot()
        val slotBefore = compose.onNodeWithTag("band-count-slot").getUnclippedBoundsInRoot()

        compose.onNodeWithTag("mode-parametric").performClick()
        compose.waitForIdle()
        assertEquals("absent from semantics", 0, count("band-count-button"))
        assertEquals(rowBefore, compose.onNodeWithTag("mode-row").getUnclippedBoundsInRoot())
        assertEquals(segBefore, compose.onNodeWithTag("mode-segmented").getUnclippedBoundsInRoot())
        assertEquals("its space is kept", slotBefore, compose.onNodeWithTag("band-count-slot").getUnclippedBoundsInRoot())

        // A touch where the button was does not open its menu.
        compose.onNodeWithTag("band-count-slot").performClick()
        compose.waitForIdle()
        assertEquals(0, count("band-count-item-5"))
    }

    @Test fun switchingBackRestoresSlidersAndKeepsBothModesData() {
        clearParametricBands()
        ctrl.edit { it.addBand() }
        holder.onBandDragStart(2)
        holder.onBandDragChange(2, 4.5f)
        holder.onBandDragEnd()
        hostScreen()
        val gainsBefore = ctrl.snapshot().graphicGainsDb.toList()
        val bandsBefore = ctrl.snapshot().bands

        compose.onNodeWithTag("mode-parametric").performClick()
        compose.waitForIdle()
        assertEquals(0, count("band-2"))
        compose.onNodeWithTag("mode-graphic").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("band-2").assertIsDisplayed()
        assertEquals(gainsBefore, ctrl.snapshot().graphicGainsDb.toList())
        assertEquals(4.5f, ctrl.snapshot().graphicGainsDb[2], 0.01f)
        assertEquals("parametric bands untouched", bandsBefore, ctrl.snapshot().bands)
        assertEquals(1, count("band-count-button"))
    }

    @Test fun equalizerToggleFollowsBypassFromEitherSide() {
        hostScreen()
        val state = { compose.onNodeWithTag("equalizer-toggle").fetchSemanticsNode().config[SemanticsProperties.StateDescription] }
        compose.waitForIdle()
        assertEquals("On", state())
        assertEquals(0, count("bypassed_chip"))

        // The notification action goes through the controller; the toggle must follow it.
        val on = ctrl.toggleBypass()
        assertTrue(on)
        compose.waitUntil(8_000) { state() == "Off" }
        compose.waitUntil(8_000) { count("bypassed_chip") == 1 }
        compose.waitUntil(8_000) { count("status-text") == 1 && text("status-text") == "Bypassed. Audio plays unchanged." }

        // And the toggle drives the same source.
        compose.onNodeWithTag("equalizer-toggle").performClick()
        compose.waitForIdle()
        assertFalse(ctrl.snapshot().bypass)
        compose.waitUntil(8_000) { state() == "On" }
        compose.waitUntil(8_000) { count("bypassed_chip") == 0 }
    }

    @Test fun slidersStayEditableWhileBypassed() {
        hostScreen()
        holder.setBypass(true)
        compose.waitUntil(8_000) { count("bypassed_chip") == 1 }
        holder.onBandDragStart(0)
        holder.onBandDragChange(0, 3f)
        holder.onBandDragEnd()
        assertEquals(3f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
        assertTrue(ctrl.snapshot().bypass)
    }
}
