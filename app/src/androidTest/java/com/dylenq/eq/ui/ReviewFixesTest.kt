package com.dylenq.eq.ui

import android.util.Log
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fixes from the review of plan 04-05: layout fit without a banner, Reset-all Undo, slider scroll retention. */
@RunWith(AndroidJUnit4::class)
class ReviewFixesTest {
    @get:Rule val rule = EqComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = ctx.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose
    private val ctrl get() = rule.runtime.controller

    private fun scrollOffset(): Int {
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange).and(hasTestTag("slider-row")))
            .fetchSemanticsNode()
        return node.config[SemanticsProperties.HorizontalScrollAxisRange].value().toInt()
    }

    @Test fun noBannerEverythingAboveTheToneCardIsVisibleAndTheCardIsReachableByScrolling() {
        val cfg = ctx.resources.configuration
        assumeTrue("needs the 411 x 891 dp class of window", cfg.screenHeightDp >= 860 && cfg.screenWidthDp in 400..420)
        rule.launch()
        compose.waitForIdle()
        // Permission is granted by the rule and nothing is wrong: no banner is showing.
        compose.onNodeWithText("Show a notification?").assertDoesNotExist()
        val d = ctx.resources.displayMetrics.density
        val rootH = compose.onRoot().fetchSemanticsNode().size.height / d
        val lines = StringBuilder()
        var lastBottom = 0f
        for (tag in listOf("curve", "mode-row", "slider-row", "limiter-row")) {
            val n = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val top = n.positionInRoot.y / d
            val bottom = top + n.size.height / d
            lines.append("$tag=${top.toInt()}..${bottom.toInt()} ")
            assertTrue("$tag fully visible: bottom $bottom of $rootH", bottom <= rootH + 0.5f)
            assertTrue("$tag below the previous region", top >= lastBottom - 1f)
            lastBottom = bottom
        }
        val max = compose.onNodeWithTag("eq-column").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
        Log.i("EQ-LAYOUT", "no-banner ${cfg.screenWidthDp}x${cfg.screenHeightDp} dp: root=$rootH $lines scroll-max=$max")
        // Phase 8: the Tone & balance card sits under the Preamp card, so the column scrolls to reach it.
        compose.onNodeWithTag("tone-card").assertExists()
        assertTrue("the column scrolls to reach the tone card: $max", max > 0f)
        for (t in listOf("bass-slider", "treble-slider", "balance-slider")) {
            compose.onNodeWithTag(t).performScrollTo()
            compose.onNodeWithTag(t).assertIsDisplayed()
        }
    }

    @Test fun undoOnTheResetAllSnackbarNeverUndoesALaterEdit() {
        rule.launch()
        compose.waitForIdle()
        holder.onBandDragStart(0)
        holder.onBandDragChange(0, 5f)
        holder.onBandDragEnd()
        compose.waitForIdle()

        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("reset-all").performClick()
        compose.waitForIdle()
        assertEquals(0f, ctrl.snapshot().graphicGainsDb[0], 0f)
        compose.onNodeWithText("Undo").assertExists()

        // An unrelated edit while the snackbar is up dismisses it.
        holder.setPreamp(-3f)
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Undo")).fetchSemanticsNodes().isEmpty() }
        assertEquals(-3f, ctrl.snapshot().preampDb, 0f)
        assertEquals("the reset itself is untouched", 0f, ctrl.snapshot().graphicGainsDb[0], 0f)
    }

    @Test fun undoOnTheResetAllSnackbarStillWorksWhenNothingElseChanged() {
        rule.launch()
        compose.waitForIdle()
        holder.onBandDragStart(0)
        holder.onBandDragChange(0, 5f)
        holder.onBandDragEnd()
        holder.setPreamp(-2f)
        compose.waitForIdle()
        compose.onNodeWithTag("overflow").performClick()
        compose.onNodeWithTag("reset-all").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(5f, ctrl.snapshot().graphicGainsDb[0], 0.01f)
        assertEquals(-2f, ctrl.snapshot().preampDb, 0f)
    }

    @Test fun sliderScrollOffsetSurvivesGraphicParametricGraphic() {
        rule.launch()
        compose.waitForIdle()
        holder.setBandCount(31)
        compose.waitForIdle()
        compose.onNodeWithTag("band-30").performScrollTo()
        compose.waitForIdle()
        val before = scrollOffset()
        assertTrue("scrolled: $before", before > 0)

        compose.onNodeWithTag("mode-parametric").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("slider-row")).fetchSemanticsNodes().isEmpty() }
        assertEquals(EqMode.PARAMETRIC, ctrl.snapshot().mode)
        compose.onNodeWithTag("mode-graphic").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("slider-row")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("scroll offset kept across the mode round trip", before, scrollOffset())
    }
}
