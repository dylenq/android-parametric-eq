package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Preamp, suggested preamp, limiter, Equalizer toggle and Reset all (UI-SPEC checks 6, 7, 8, 9). */
@RunWith(AndroidJUnit4::class)
class PreampLimiterTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose
    private val ctrl get() = rule.runtime.controller

    private fun hostCard(compact: Boolean = false) = rule.host {
        val ui by holder.ui.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val resetAll = rememberResetAll(holder, snackbar)
        EqErrorSnackbars(holder, snackbar)
        Scaffold(
            topBar = { EqTopBar({ ui }, holder::setBypass, resetAll) },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { pad ->
            Column(Modifier.padding(pad).padding(16.dp)) {
                PreampCard(
                    state = { ui },
                    onPreamp = holder::setPreamp,
                    onDragStart = holder::preampDragStart,
                    onDragEnd = holder::preampDragEnd,
                    onReset = holder::resetPreamp,
                    onApplySuggested = holder::applySuggestedPreamp,
                    onLimiter = holder::setLimiter,
                    compact = compact,
                )
            }
        }
    }

    private fun preamp() = ctrl.snapshot().preampDb

    private fun undoDepth(): Int {
        var n = 0
        while (ctrl.snapshot().canUndo && n < 50) {
            holder.undo()
            n++
        }
        return n
    }

    private fun awaitUi(pred: (EqUiState) -> Boolean): EqUiState =
        runBlocking { withTimeout(10_000) { holder.ui.first { it.curve != null && pred(it) } } }

    private fun setProgress(v: Float) {
        compose.onNodeWithTag("preamp-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        compose.waitForIdle()
    }

    @Test fun setProgressIsClampedToTheMinusTwentyFourToSixRange() {
        hostCard()
        setProgress(-30f)
        assertEquals(-24f, preamp(), 0.01f)
        setProgress(10f)
        assertEquals(6f, preamp(), 0.01f)
        setProgress(-5.2f)
        assertEquals("snaps to the 0.5 dB grid", -5f, preamp(), 0.01f)
        val cfg = compose.onNodeWithTag("preamp-slider").fetchSemanticsNode().config
        val r = cfg[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(-24f..6f, r.range)
        assertEquals(59, r.steps)
        assertEquals(listOf("Preamp"), cfg[SemanticsProperties.ContentDescription])
        assertEquals("minus 5 decibels", cfg[SemanticsProperties.StateDescription])
        assertEquals("−5.0 dB", compose.onNodeWithTag("preamp-pill").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text })
    }

    @Test fun setProgressIsOneClosedUndoStep() {
        hostCard()
        setProgress(-6f)
        setProgress(-3f)
        assertEquals("two separate semantic edits are two undo steps", 2, undoDepth())
        assertEquals(0f, preamp(), 0f)
    }

    @Test fun swipingTheSliderIsOneUndoStep() {
        hostCard()
        // A drag starting within ~15 dp of the left edge does not engage the M3 slider (measured: starts at
        // 2 and 20 px changed nothing, 40 px and more worked), so the swipe starts at the middle.
        compose.onNodeWithTag("preamp-slider").performTouchInput {
            swipe(Offset(width * 0.5f, centerY), Offset(width - 2f, centerY), 400)
        }
        compose.waitForIdle()
        assertTrue("reached the top end: ${preamp()}", preamp() > 5f && preamp() <= 6f)
        assertEquals("a continuous drag is one undo step", 1, undoDepth())
        assertEquals(0f, preamp(), 0f)
    }

    @Test fun aCancelledDragClosesItsUndoGroup() {
        hostCard()
        compose.onNodeWithTag("preamp-slider").performTouchInput {
            down(Offset(width * 0.5f, centerY))
            moveBy(Offset(80f, 0f))
            moveBy(Offset(80f, 0f))
            cancel()
        }
        compose.waitForIdle()
        val partial = preamp()
        assertTrue("the partial drag stays applied: $partial", partial != 0f)
        setProgress(-4f)
        assertEquals("the cancelled drag and the later edit are separate steps", 2, undoDepth())
    }

    @Test fun doubleTappingThePillResetsInOneUndoStep() {
        hostCard()
        setProgress(-8f)
        compose.onNodeWithTag("preamp-pill").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(0f, preamp(), 0f)
        assertEquals("one reset step plus the setup step", 2, undoDepth())
        assertEquals(0f, preamp(), 0f)
    }

    @Test fun doubleTappingTheSliderThumbResetsToZero() {
        hostCard()
        // -9 dB is the exact middle of -24..6: a double-tap at the centre lands on the thumb, so the
        // slider's own tap handling changes nothing and the reset is the only extra step.
        setProgress(-9f)
        compose.onNodeWithTag("preamp-slider").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(0f, preamp(), 0f)
        assertEquals("setup step plus one reset step", 2, undoDepth())
    }

    @Test fun suggestedChipAppearsForABoostAndClearsTheErrorWhenApplied() {
        hostCard()
        holder.onBandDragStart(5)
        holder.onBandDragChange(5, 12f)
        holder.onBandDragEnd()
        val s = awaitUi { it.suggestedPreampDb < 0f && it.headroomAfterPreampDb < -0.05f }
        compose.waitForIdle()
        compose.onNodeWithTag("over-by").assertIsDisplayed()
        val over = compose.onNodeWithTag("over-by").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(over, over.startsWith("Over by ") && over.endsWith(" dB. Lower the preamp or apply the suggestion."))
        compose.onNodeWithTag("apply-suggested").assertIsDisplayed()
        compose.onNodeWithTag("apply-suggested").performClick()
        compose.waitForIdle()
        assertEquals(s.suggestedPreampDb, preamp(), 0.01f)
        awaitUi { it.headroomAfterPreampDb >= -0.05f }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTagCount("over-by"))
        assertEquals("the chip clears after applying", 0, compose.onAllNodesWithTagCount("apply-suggested"))
        compose.onNodeWithTag("headroom").assertIsDisplayed()
    }

    @Test fun limiterSwitchTogglesTheCoreAndShowsTheExactDescription() {
        hostCard()
        compose.onNodeWithText("Eases sustained loud sections. It can't catch short peaks.").assertIsDisplayed()
        assertTrue(ctrl.snapshot().limiterEnabled)
        compose.onNodeWithTag("limiter-row").performClick()
        compose.waitForIdle()
        assertFalse(ctrl.snapshot().limiterEnabled)
        compose.onNodeWithTag("limiter-row").performClick()
        compose.waitForIdle()
        assertTrue(ctrl.snapshot().limiterEnabled)
    }

    @Test fun resetAllZeroesEverythingAndSnackbarUndoRestoresIt() {
        hostCard()
        holder.onBandDragStart(3)
        holder.onBandDragChange(3, 4f)
        holder.onBandDragEnd()
        holder.setPreamp(-3f)
        compose.waitForIdle()
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("reset-all").performClick()
        compose.waitForIdle()
        assertEquals(0f, preamp(), 0f)
        assertTrue(ctrl.snapshot().graphicGainsDb.all { it == 0f })
        compose.onNodeWithText("Sliders and preamp reset").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(-3f, preamp(), 0.01f)
        assertEquals(4f, ctrl.snapshot().graphicGainsDb[3], 0.01f)
    }

    @Test fun resetAllIsDisabledWhenEverythingIsZeroAndTheSnackbarTimesOut() {
        hostCard()
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("reset-all").assertIsNotEnabled()
        // The menu stays open; once the preamp is non-zero the same item becomes enabled.
        holder.setPreamp(-2f)
        compose.waitForIdle()
        compose.onNodeWithTag("reset-all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Sliders and preamp reset").assertIsDisplayed()
        compose.waitUntil(9_000) { compose.onAllNodes(hasText("Sliders and preamp reset")).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun equalizerToggleFlipsBypassAndReadsOff() {
        hostCard()
        val toggle = { compose.onNodeWithTag("equalizer-toggle").fetchSemanticsNode().config }
        assertEquals("On", toggle()[SemanticsProperties.StateDescription])
        assertEquals(listOf("Equalizer"), toggle()[SemanticsProperties.ContentDescription])
        compose.onNodeWithTag("equalizer-toggle").performClick()
        compose.waitForIdle()
        assertTrue(ctrl.snapshot().bypass)
        awaitUi { it.bypass }
        compose.waitForIdle()
        assertEquals("Off", toggle()[SemanticsProperties.StateDescription])
        compose.onNodeWithTag("equalizer-toggle").performClick()
        compose.waitForIdle()
        assertFalse(ctrl.snapshot().bypass)
    }

    @Test fun bypassedHidesHeadroomOverByAndSuggestionAndShowsNotAppliedInstead() {
        ctrl.edit { it.setGraphicGain(3u, 12f) }
        hostCard()
        awaitUi { it.suggestedPreampDb < 0f && it.headroomAfterPreampDb < -0.05f }
        compose.waitForIdle()
        compose.onNodeWithTag("over-by").assertIsDisplayed()
        compose.onNodeWithTag("apply-suggested").assertIsDisplayed()

        holder.setBypass(true)
        awaitUi { it.bypass }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTagCount("over-by"))
        assertEquals(0, compose.onAllNodesWithTagCount("apply-suggested"))
        assertEquals(0, compose.onAllNodesWithTagCount("headroom"))
        compose.onNodeWithTag("headroom-bypassed").assertIsDisplayed()
        compose.onNodeWithText(BYPASSED_HEADROOM_TEXT).assertIsDisplayed()
        // The limiter stays editable while bypassed.
        compose.onNodeWithTag("limiter-row").assertIsDisplayed()

        holder.setBypass(false)
        awaitUi { !it.bypass }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTagCount("headroom-bypassed"))
        compose.onNodeWithTag("over-by").assertIsDisplayed()
    }

    @Test fun compactFormOmitsTheHeadroomText() {
        hostCard(compact = true)
        assertEquals(0, compose.onAllNodesWithTagCount("headroom"))
        compose.onNodeWithTag("limiter-row").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
