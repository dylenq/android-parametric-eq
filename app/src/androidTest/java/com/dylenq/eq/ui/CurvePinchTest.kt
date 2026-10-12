package com.dylenq.eq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pinch for Q (PEQ-02), the drag-to-pinch hand-over and cancellation safety. */
@RunWith(AndroidJUnit4::class)
class CurvePinchTest : CurveTestBase() {
    private val layer get() = rule.compose.onNodeWithTag("curve-interaction")

    /** A vertical two-finger pinch around [c]: half-distance [from] dp to [to] dp. */
    private fun pinchAt(c: Offset, from: Float, to: Float) {
        layer.performTouchInput {
            pinch(
                Offset(c.x, c.y - dp(from)), Offset(c.x, c.y - dp(to)),
                Offset(c.x, c.y + dp(from)), Offset(c.x, c.y + dp(to)),
                durationMillis = 200,
            )
        }
        rule.compose.waitForIdle()
    }

    private fun tapAt(p: Offset) {
        layer.performTouchInput { down(p); up() }
        rule.compose.waitForIdle()
    }

    private fun q(i: Int) = controller.snapshot().bands[i].q

    @Test fun pinchingOutDoublesQAndLeavesTheNodeAloneAsOneUndoStep() {
        hostCard()
        tapAt(node(3))
        val before = controller.snapshot().bands[3]
        pinchAt(node(3), from = 25f, to = 50f)
        val after = controller.snapshot().bands[3]
        assertEquals(2.0f, after.q, 0.06f)
        assertEquals(before.freq, after.freq, 0f)
        assertEquals(before.gainDb, after.gainDb, 0f)
        assertEquals(3, holder.ui.value.selectedBand)
        assertFalse(holder.ui.value.gestureOpen)
        assertEquals("one undo step", 1, undoDepth())
        assertEquals(before, controller.snapshot().bands[3])
        assertEquals(1, pinchCompleted.get())
    }

    @Test fun pinchingInLowersQ() {
        hostCard()
        tapAt(node(3))
        pinchAt(node(3), from = 50f, to = 25f)
        assertEquals(0.5f, q(3), 0.04f)
        assertEquals(1, undoDepth())
    }

    @Test fun qClampsAtEighteenAndPointOneAndReversingRespondsAtOnce() {
        controller.edit { c -> c.setBand(3u, controller.snapshot().bands[3].copy(q = 10f)) }
        hostCard()
        tapAt(node(3))
        val c = node(3)
        layer.performTouchInput {
            down(0, Offset(c.x, c.y - dp(20f)))
            down(1, Offset(c.x, c.y + dp(20f)))
            // ratio 4: unclamped 40, clamped to 18
            moveTo(0, Offset(c.x, c.y - dp(80f)))
            moveTo(1, Offset(c.x, c.y + dp(80f)))
        }
        rule.compose.waitForIdle()
        assertEquals(18f, q(3), 0f)
        layer.performTouchInput {
            // back to ratio 1.2: Q is 12 straight away (no dead zone at the limit)
            moveTo(0, Offset(c.x, c.y - dp(24f)))
            moveTo(1, Offset(c.x, c.y + dp(24f)))
        }
        rule.compose.waitForIdle()
        assertEquals(12f, q(3), 0.1f)
        layer.performTouchInput {
            // way in: ratio 0.01 clamps at 0.1
            moveTo(0, Offset(c.x, c.y - dp(0.2f)))
            moveTo(1, Offset(c.x, c.y + dp(0.2f)))
        }
        rule.compose.waitForIdle()
        assertEquals(0.1f, q(3), 0f)
        layer.performTouchInput { up(0); up(1) }
        rule.compose.waitForIdle()
        assertEquals("setup edit + one undo step for the whole pinch", 2, undoDepth())
    }

    @Test fun theLimitRejectHapticFiresOncePerGesture() {
        controller.edit { c -> c.setBand(3u, controller.snapshot().bands[3].copy(q = 10f)) }
        hostCard()
        tapAt(node(3))
        haptics.types.clear()
        pinchAt(node(3), from = 20f, to = 70f)
        assertEquals(18f, q(3), 0f)
        assertEquals(1, haptics.types.count { it == HapticFeedbackType.Reject })
    }

    @Test fun theTargetIsTheBandNearestTheCentroidThenTheSelectedOne() {
        hostCard()
        tapAt(node(2))
        assertEquals(2, holder.ui.value.selectedBand)
        // Centred on band 4 while band 2 is selected: band 4 changes and becomes selected.
        pinchAt(node(4), from = 25f, to = 50f)
        assertEquals(2.0f, q(4), 0.06f)
        assertEquals(1.0f, q(2), 0f)
        assertEquals(4, holder.ui.value.selectedBand)
        // Far from every node: the selected band (4) changes.
        val far = farSpot()
        pinchAt(far, from = 15f, to = 30f)
        assertEquals(4.0f, q(4), 0.12f)
        assertEquals(4, holder.ui.value.selectedBand)
    }

    /** The plot point farthest from every node (grid search), required to be outside the 72 dp pinch radius. */
    private fun farSpot(): Offset {
        val plot = plotOf()
        val n = controller.snapshot().bands.size
        val nodes = (0 until n).map { node(it) }
        var best = Offset.Zero
        var bestD = -1f
        val step = dp(4f)
        // Keep 30 dp from the plot edges so the whole two-finger gesture stays inside the layer.
        var y = plot.top + dp(30f)
        while (y <= plot.bottom - dp(30f)) {
            var x = plot.left + dp(30f)
            while (x <= plot.right - dp(30f)) {
                val d = nodes.minOf { (it - Offset(x, y)).getDistance() }
                if (d > bestD) { bestD = d; best = Offset(x, y) }
                x += step
            }
            y += step
        }
        assertTrue("test setup: farthest spot is ${bestD / density} dp from the nearest node", bestD > dp(78f))
        return best
    }

    @Test fun withNoSelectionAndNoNodeNearbyAPinchDoesNothing() {
        hostCard()
        val bandsBefore = controller.snapshot().bands
        pinchAt(farSpot(), from = 15f, to = 30f)
        assertEquals(bandsBefore, controller.snapshot().bands)
        assertFalse("no edit opened", controller.snapshot().canUndo)
        assertFalse(holder.ui.value.gestureOpen)
        assertEquals(0, pinchCompleted.get())
        assertEquals(null, holder.ui.value.selectedBand)
    }

    @Test fun aDragThatBecomesAPinchIsOneUndoStepAndTheSurvivorDoesNotTeleportTheNode() {
        hostCard()
        val before = controller.snapshot().bands[3]
        val start = node(3)
        layer.performTouchInput {
            down(0, start)
            moveTo(0, start + Offset(dp(10f), 0f))
            moveTo(0, start + Offset(dp(30f), -dp(8f)))
        }
        rule.compose.waitForIdle()
        val dragged = controller.snapshot().bands[3]
        assertTrue("dragged: ${dragged.freq}", dragged.freq > before.freq)
        val f0 = start + Offset(dp(30f), -dp(8f))
        layer.performTouchInput {
            // Second finger 20 dp below: the pair is centred next to the (moved) node.
            down(1, f0 + Offset(0f, dp(20f)))
            moveTo(1, f0 + Offset(0f, dp(40f)))
            up(1)
        }
        rule.compose.waitForIdle()
        val pinched = controller.snapshot().bands[3]
        assertEquals("pinch froze the node", dragged.freq, pinched.freq, 0f)
        assertEquals(dragged.gainDb, pinched.gainDb, 0f)
        assertEquals(2.0f, pinched.q, 0.15f)
        layer.performTouchInput {
            moveTo(0, f0 + Offset(dp(100f), -dp(40f)))
            moveBy(Offset(dp(20f), dp(20f)))
            up(0)
        }
        rule.compose.waitForIdle()
        assertEquals("the surviving finger did not move the node", pinched, controller.snapshot().bands[3])
        assertFalse(holder.ui.value.gestureOpen)
        assertEquals("drag + pinch = one undo step", 1, undoDepth())
        assertEquals(before, controller.snapshot().bands[3])
    }

    @Test fun afterACancelTheEditIsEndedAndTheNextDragIsItsOwnStep() {
        hostCard()
        val start = node(3)
        // Note: an injected cancel() reaches the machine as a release (see 05-PROBE-RESULT.md), so this
        // asserts the observable outcome (edit ended, history consistent), not a cancel-specific branch.
        layer.performTouchInput {
            down(start)
            moveBy(Offset(dp(30f), -dp(20f)))
            moveBy(Offset(dp(10f), -dp(5f)))
            cancel()
        }
        rule.compose.waitForIdle()
        assertFalse(holder.ui.value.gestureOpen)
        assertTrue(controller.snapshot().canUndo)
        val afterFirst = controller.snapshot().bands[3]
        layer.performTouchInput {
            down(node(3))
            moveBy(Offset(-dp(40f), dp(20f)))
            moveBy(Offset(-dp(10f), dp(5f)))
            up()
        }
        rule.compose.waitForIdle()
        assertTrue(controller.snapshot().bands[3] != afterFirst)
        assertEquals("two gestures, two undo steps", 2, undoDepth())

        // Same for a cancel in the middle of a pinch.
        val c = node(4)
        layer.performTouchInput {
            down(0, Offset(c.x, c.y - dp(25f)))
            down(1, Offset(c.x, c.y + dp(25f)))
            moveTo(0, Offset(c.x, c.y - dp(50f)))
            moveTo(1, Offset(c.x, c.y + dp(50f)))
            cancel()
        }
        rule.compose.waitForIdle()
        assertFalse(holder.ui.value.gestureOpen)
        assertTrue(controller.snapshot().canUndo)
        assertEquals(1, undoDepth())
    }
}
