package com.dylenq.eq.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.up
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Replaces MainActivity's content with [content] (themed), so a test owns the whole screen. */
internal fun EqComposeRule.host(content: @androidx.compose.runtime.Composable () -> Unit) {
    val scenario = launch()
    scenario.onActivity { act -> act.setContent { MaterialTheme { content() } } }
    compose.waitForIdle()
}

@RunWith(AndroidJUnit4::class)
class BandSliderTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose: ComposeTestRule get() = rule.compose

    private fun gain0(): Float = rule.runtime.controller.snapshot().graphicGainsDb[0]

    /** Undoes until nothing is left and returns how many steps that took. */
    private fun undoDepth(): Int {
        var n = 0
        while (rule.runtime.controller.snapshot().canUndo && n < 50) {
            holder.undo()
            n++
        }
        return n
    }

    private fun hostOneSlider() = rule.host {
        val ui by holder.ui.collectAsStateWithLifecycle()
        val bubble = androidx.compose.runtime.remember { BubbleState() }
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            VerticalGainSlider(
                index = 0,
                frequencyHz = 1000f,
                gainDb = { ui.graphicGainsDb[0] },
                pitch = 48.dp,
                onDragStart = { holder.onBandDragStart(0) },
                onChange = { holder.onBandDragChange(0, it) },
                onDragEnd = { holder.onBandDragEnd() },
                onReset = { holder.resetBand(0) },
                bubble = bubble,
                modifier = Modifier.height(240.dp),
            )
            ValueBubbleOverlay(bubble)
        }
    }

    private fun setProgress(v: Float) {
        compose.onNodeWithTag("band-0").performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        compose.waitForIdle()
    }

    private fun action(label: String) {
        val node = compose.onNodeWithTag("band-0").fetchSemanticsNode()
        val a = node.config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { a.action() }
        compose.waitForIdle()
    }

    @Test fun semanticsDescribeASlider() {
        hostOneSlider()
        val cfg = compose.onNodeWithTag("band-0").fetchSemanticsNode().config
        // Compose 1.10 has no Role.Slider; the range info is what marks it as a slider for TalkBack.
        assertEquals(listOf("1 kilohertz"), cfg[SemanticsProperties.ContentDescription])
        assertEquals("0 decibels", cfg[SemanticsProperties.StateDescription])
        val r = cfg[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(-12f..12f, r.range)
        assertEquals(47, r.steps)
        val labels = cfg[SemanticsActions.CustomActions].map { it.label }
        assertEquals(listOf("Increase by 0.5 decibel", "Decrease by 0.5 decibel", "Reset to zero"), labels)
    }

    @Test fun setProgressAppliesToCoreAsOneUndoStep() {
        hostOneSlider()
        setProgress(6f)
        assertEquals(6f, gain0(), 0.01f)
        assertEquals("plus 6 decibels", compose.onNodeWithTag("band-0").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertEquals(1, undoDepth())
    }

    @Test fun swipeUpRaisesGainAsExactlyOneUndoStep() {
        hostOneSlider()
        compose.onNodeWithTag("band-0").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("gain ${gain0()}", gain0() > 6f)
        assertEquals("a continuous drag is one undo step", 1, undoDepth())
        assertEquals(0f, gain0(), 0f)
    }

    @Test fun cancelledGestureClosesTheEditGroup() {
        hostOneSlider()
        compose.onNodeWithTag("band-0").performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -120f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, -40f))
            cancel()
        }
        compose.waitForIdle()
        val afterCancel = gain0()
        assertTrue("the partial drag stays applied: $afterCancel", afterCancel > 0f)
        // A separate edit must be its own undo step, not merged into a group left open by the cancel.
        setProgress(-4f)
        assertEquals(-4f, gain0(), 0.01f)
        assertEquals(2, undoDepth())
    }

    @Test fun doubleTapResetsInOneUndoStepAndDragDoesNot() {
        hostOneSlider()
        setProgress(6f)
        compose.onNodeWithTag("band-0").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(0f, gain0(), 0f)
        // set + reset, nothing from a stray drag
        assertEquals(2, undoDepth())
        // a drag must not look like a double-tap: swipe twice in a row, the second leaves a non-zero gain
        compose.onNodeWithTag("band-0").performTouchInput { swipeUp() }
        compose.onNodeWithTag("band-0").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(gain0() > 6f)
    }

    @Test fun customActionsAdjustAndReset() {
        hostOneSlider()
        action("Increase by 0.5 decibel")
        assertEquals(0.5f, gain0(), 0.001f)
        action("Increase by 0.5 decibel")
        assertEquals(1.0f, gain0(), 0.001f)
        action("Decrease by 0.5 decibel")
        assertEquals(0.5f, gain0(), 0.001f)
        action("Reset to zero")
        assertEquals(0f, gain0(), 0f)
        assertEquals(4, undoDepth())
    }

    @Test fun valueBubbleShowsWhilePressedAndFadesAfterRelease() {
        hostOneSlider()
        compose.onNodeWithTag("value-bubble").assertDoesNotExistCompat()
        compose.onNodeWithTag("band-0").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("value-bubble").assertExists()
        assertTrue(compose.onAllNodesWithTagText("0 dB"))
        compose.onNodeWithTag("band-0").performTouchInput {
            moveBy(androidx.compose.ui.geometry.Offset(0f, -30f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, -30f))
        }
        compose.waitForIdle()
        val g = gain0()
        assertTrue(g > 0f)
        assertTrue(compose.onAllNodesWithTagText(GainFormat.format(g)))
        compose.onNodeWithTag("band-0").performTouchInput { up() }
        compose.waitUntil(2_000) {
            compose.onAllNodesWithTagCount("value-bubble") == 0
        }
        assertNotNull(g)
    }

    private fun ComposeTestRule.onAllNodesWithTagText(text: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasTestTag("value-bubble").and(hasTextInside(text))).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun hasTextInside(text: String) =
        androidx.compose.ui.test.hasAnyDescendant(hasText(text))

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = this.assertDoesNotExist()
}
