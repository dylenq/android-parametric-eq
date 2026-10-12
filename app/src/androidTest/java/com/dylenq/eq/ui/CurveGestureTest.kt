package com.dylenq.eq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.FilterKind
import java.math.BigDecimal
import java.math.MathContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Single-finger half of the Parametric gesture machine (PEQ-01, PEQ-03, detection half of PEQ-04). */
@RunWith(AndroidJUnit4::class)
class CurveGestureTest : CurveTestBase() {
    private val layer get() = rule.compose.onNodeWithTag("curve-interaction")

    private fun tapAt(p: Offset) {
        layer.performTouchInput { down(p); up() }
        rule.compose.waitForIdle()
    }

    private fun drag(start: Offset, total: Offset, steps: Int = 6) {
        layer.performTouchInput {
            down(start)
            repeat(steps) { moveBy(total / steps.toFloat()) }
            up()
        }
        rule.compose.waitForIdle()
    }

    /** A point well away from every default node (they sit near the middle of the plot, y-wise). */
    private fun emptySpot(): Offset {
        val plot = plotOf()
        return Offset(plot.left + plot.width * 0.62f, plot.top + plot.height * 0.15f)
    }

    @Test fun tapOnANodeSelectsItWithoutOpeningAnEdit() {
        hostCard()
        assertNull(holder.ui.value.selectedBand)
        tapAt(node(3))
        assertEquals(3, holder.ui.value.selectedBand)
        assertFalse("a tap is not an edit", controller.snapshot().canUndo)
        assertFalse(holder.ui.value.gestureOpen)
        tapAt(node(3))
        assertEquals("tapping the selected node keeps it selected", 3, holder.ui.value.selectedBand)
        assertFalse(controller.snapshot().canUndo)
    }

    @Test fun tapOnAStackCyclesThroughTheCandidates() {
        // Band 4 moves onto band 3's frequency: two nodes at the same spot.
        controller.edit { c -> c.setBand(4u, controller.snapshot().bands[4].copy(freq = 1000f)) }
        hostCard()
        val spot = node(3)
        tapAt(spot)
        assertEquals("nothing selected: the top (higher index) wins", 4, holder.ui.value.selectedBand)
        tapAt(spot)
        assertEquals("selected one is in the stack: the next candidate", 3, holder.ui.value.selectedBand)
        tapAt(spot)
        assertEquals("wraps around", 4, holder.ui.value.selectedBand)
    }

    @Test fun aDragOnAStackMovesThePickRuleWinner() {
        controller.edit { c -> c.setBand(4u, controller.snapshot().bands[4].copy(freq = 1000f)) }
        hostCard()
        drag(node(3), Offset(50f * density, 0f), steps = 5)
        assertEquals("band 3 untouched", 1000f, controller.snapshot().bands[3].freq, 0.01f)
        assertTrue("band 4 moved: ${controller.snapshot().bands[4].freq}", controller.snapshot().bands[4].freq > 1100f)
        assertEquals(4, holder.ui.value.selectedBand)
    }

    @Test fun aTapOnEmptyPlotDeselectsAtOnce() {
        hostCard()
        tapAt(node(2))
        assertEquals(2, holder.ui.value.selectedBand)
        tapAt(emptySpot())
        // No double-tap wait: the selection is gone as soon as the gesture is processed.
        assertNull(holder.ui.value.selectedBand)
        assertFalse(controller.snapshot().canUndo)
        assertEquals(7, controller.snapshot().bands.size)
    }

    @Test fun doubleTapOnEmptyPlotAddsASelectedPeakAsOneUndoStep() {
        hostCard()
        val plot = plotOf()
        val spot = emptySpot()
        layer.performTouchInput { doubleClick(spot) }
        rule.compose.waitForIdle()
        val bands = controller.snapshot().bands
        assertEquals(8, bands.size)
        val added = bands.last()
        assertEquals(FilterKind.PEAK, added.kind)
        assertEquals(1.0f, added.q, 0f)
        assertTrue(added.enabled)
        assertEquals(ParametricMath.snapHz(ParametricMath.hzAt(spot.x, plot)), added.freq, 0.001f)
        assertEquals(
            ParametricMath.snapDb(ParametricMath.dbAt(spot.y, plot, AxisScale.PARAMETRIC_Y_HALF_SPAN_DB)),
            added.gainDb, 0.001f,
        )
        assertTrue("gain is above the 0 dB line", added.gainDb > 1f)
        assertEquals(7, holder.ui.value.selectedBand)
        assertEquals("exactly one undo step", 1, undoDepth())
        assertEquals("undo removes the band", 7, controller.snapshot().bands.size)
    }

    @Test fun aDoubleTapWithinTwentyEightDpOfANodeAddsNothing() {
        hostCard()
        // 26 dp above a node: outside the 24 dp hit radius (an empty tap) but inside the 28 dp exclusion.
        val near = node(3) + Offset(0f, -26f * density)
        layer.performTouchInput { doubleClick(near) }
        rule.compose.waitForIdle()
        assertEquals(7, controller.snapshot().bands.size)
        assertFalse(controller.snapshot().canUndo)
        // And a double-tap on the node itself is two taps on it.
        layer.performTouchInput { doubleClick(node(3)) }
        rule.compose.waitForIdle()
        assertEquals(7, controller.snapshot().bands.size)
        assertEquals(3, holder.ui.value.selectedBand)
        assertFalse(controller.snapshot().canUndo)
    }

    @Test fun atSixteenBandsADoubleTapAddsNothingSaysSoAndRejects() {
        repeat(9) { controller.edit { c -> c.addBand() } }
        // Pin undo state: the add steps above are history; what matters is nothing changes afterwards.
        hostCard()
        assertEquals(16, controller.snapshot().bands.size)
        val got = CopyOnWriteArrayList<UiMessage>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { holder.messages.collect { got.add(it) } }
        Thread.sleep(200)
        val depthBefore = controller.snapshot().canUndo
        val bandsBefore = controller.snapshot().bands
        haptics.types.clear()
        layer.performTouchInput { doubleClick(emptySpotIn(plotOf())) }
        rule.compose.waitForIdle()
        Thread.sleep(300)
        scope.cancel()
        assertEquals(16, controller.snapshot().bands.size)
        assertEquals(bandsBefore, controller.snapshot().bands)
        assertEquals(depthBefore, controller.snapshot().canUndo)
        assertEquals(listOf("All 16 bands are in use. Remove one to add another."), got.map { it.text })
        assertTrue("reject haptic fired", HapticFeedbackType.Reject in haptics.types)
        assertFalse("no confirm haptic", HapticFeedbackType.Confirm in haptics.types)
    }

    private fun emptySpotIn(plot: androidx.compose.ui.geometry.Rect) =
        Offset(plot.left + plot.width * 0.62f, plot.top + plot.height * 0.15f)

    @Test fun dragKeepsTheGrabOffsetAndSnapsFrequencyAndGain() {
        hostCard()
        val start = node(3)
        // Grab 12 dp to the side of the centre: the node must not jump to the finger.
        val grab = start + Offset(12f * density, 6f * density)
        drag(grab, Offset(40f * density, -37f * density), steps = 7)
        val plot = plotOf()
        val b = controller.snapshot().bands[3]
        val expectedTarget = start + Offset(40f * density, -37f * density)
        assertEquals(ParametricMath.snapHz(ParametricMath.hzAt(expectedTarget.x, plot)), b.freq, b.freq * 0.01f)
        assertEquals(BigDecimal(b.freq.toDouble()).round(MathContext(3)).toFloat(), b.freq, 0f)
        assertEquals(Math.round(b.gainDb * 10f) / 10f, b.gainDb, 1e-6f)
        assertTrue("gain moved up: ${b.gainDb}", b.gainDb > 1f)
        assertEquals(1, undoDepth())
    }

    @Test fun theZeroDetentHoldsTheGainAtZeroNearTheCentreLine() {
        hostCard()
        val start = node(3)
        // A tiny vertical move (about 0.1 dB worth) stays inside the 0.25 dB detent.
        val plot = plotOf()
        val dbPerPx = 2f * AxisScale.PARAMETRIC_Y_HALF_SPAN_DB / plot.height
        val dy = -(0.2f / dbPerPx)
        drag(start, Offset(30f * density, dy), steps = 5)
        val b = controller.snapshot().bands[3]
        assertEquals(0f, b.gainDb, 0f)
        assertTrue(b.freq > 1000f)
    }

    @Test fun aPassFilterNodeDragsHorizontallyOnly() {
        controller.edit { c -> c.setBand(1u, Band(FilterKind.HIGH_PASS, 200f, 0f, 0.707f, true)) }
        hostCard()
        val start = node(1)
        drag(start, Offset(50f * density, -60f * density), steps = 6)
        val b = controller.snapshot().bands[1]
        assertTrue("frequency moved: ${b.freq}", b.freq > 220f)
        assertEquals("gain untouched", 0f, b.gainDb, 0f)
        assertEquals(FilterKind.HIGH_PASS, b.kind)
    }

    @Test fun holdingANodeDetectsALongPressOnceWithoutAnEdit() {
        hostCard()
        val p = node(3)
        layer.performTouchInput { down(p) }
        rule.compose.mainClock.advanceTimeBy(longPressMs + 100)
        rule.compose.waitForIdle()
        assertEquals(listOf(3), longPresses.toList())
        assertEquals(3, holder.ui.value.selectedBand)
        layer.performTouchInput {
            moveBy(Offset(60f * density, -40f * density))
            advanceEventTime(300)
            moveBy(Offset(10f * density, 10f * density))
            up()
        }
        rule.compose.waitForIdle()
        assertEquals("fired exactly once", 1, longPresses.size)
        assertEquals("the finger is ignored after the long-press", 1000f, controller.snapshot().bands[3].freq, 0.01f)
        assertFalse(controller.snapshot().canUndo)
        assertFalse(holder.ui.value.gestureOpen)
    }

    @Test fun aQuickTapIsNotALongPress() {
        hostCard()
        tapAt(node(3))
        assertTrue(longPresses.isEmpty())
    }
}
