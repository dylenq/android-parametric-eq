package com.dylenq.eq.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tracer for phase 05: a Parametric node is drawn on the real curve and dragged end to end
 * (finger -> layer -> holder -> controller -> core -> recomputed curve), as one undo step.
 */
@RunWith(AndroidJUnit4::class)
class ParametricTracerTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val controller get() = rule.runtime.controller

    /** Whether the parent saw each first-down as already consumed by the interaction layer. */
    private val downsConsumed = CopyOnWriteArrayList<Boolean>()
    private var density = 1f

    @Before fun parametricMode() {
        controller.edit { it.setMode(EqMode.PARAMETRIC) }
    }

    private fun hostCard() = rule.host {
        val ui by holder.ui.collectAsStateWithLifecycle()
        density = LocalDensity.current.density
        val actions = androidx.compose.runtime.remember {
            ParametricActions(holder::selectBand, holder::parametricGestureStart, holder::parametricNodeChange, holder::parametricGestureEnd)
        }
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val d = awaitFirstDown(requireUnconsumed = false)
                    // Children run first in the Main pass, so the layer's consume is visible here.
                    downsConsumed.add(d.isConsumed)
                    do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                }
            },
        ) {
            CurveCard({ ui }, onApplySuggested = {}, parametric = actions)
        }
    }

    private fun plotOf(): androidx.compose.ui.geometry.Rect {
        val size = rule.compose.onNodeWithTag("curve-interaction").fetchSemanticsNode().size
        return PlotGeometry.plotRect(size.width.toFloat(), size.height.toFloat(), density)
    }

    private fun node(index: Int): Offset {
        val b = controller.snapshot().bands[index]
        return ParametricMath.nodePosition(b.freq, b.gainDb, true, plotOf(), AxisScale.PARAMETRIC_Y_HALF_SPAN_DB)
    }

    private fun awaitCurve(pred: (CurvePoints) -> Boolean): CurvePoints = runBlocking {
        withTimeout(10_000) { holder.ui.first { it.curve?.let(pred) == true }.curve!! }
    }

    private fun undoDepth(): Int {
        var n = 0
        while (controller.snapshot().canUndo && n < 50) {
            holder.undo()
            n++
        }
        return n
    }

    private fun dragFrom(start: Offset, total: Offset, steps: Int = 6) {
        rule.compose.onNodeWithTag("curve-interaction").performTouchInput {
            down(start)
            repeat(steps) { moveBy(total / steps.toFloat()) }
            up()
        }
        rule.compose.waitForIdle()
    }

    @Test fun nodeIsDrawnOnTheCurveAndDraggedEndToEndAsOneUndoStep() {
        hostCard()
        val before = controller.snapshot().bands[3]
        assertEquals(1000f, before.freq, 0.01f)
        assertEquals(0f, before.gainDb, 0f)
        val flatMax = awaitCurve { true }.achievedDb.max()
        val plot = plotOf()
        val start = node(3)
        // The node sits on the 0 dB line at 1 kHz: x from the log mapping, y at the plot centre.
        assertEquals(plot.top + plot.height / 2f, start.y, 1f)
        assertEquals(plot.left + AxisScale.xFraction(1000f) * plot.width, start.x, 1f)

        val d = Offset(60f * density, -50f * density)
        dragFrom(start, d)

        val after = controller.snapshot().bands[3]
        val target = start + d
        val expectedFreq = ParametricMath.snapHz(ParametricMath.hzAt(target.x, plot))
        val expectedGain = ParametricMath.snapDb(ParametricMath.dbAt(target.y, plot, AxisScale.PARAMETRIC_Y_HALF_SPAN_DB))
        assertTrue("freq rose: ${after.freq}", after.freq > 1000f)
        assertTrue("gain rose: ${after.gainDb}", after.gainDb > 0.5f)
        assertEquals(expectedFreq, after.freq, expectedFreq * 0.03f)
        assertEquals(expectedGain, after.gainDb, 1.0f)
        assertEquals(3, holder.ui.value.selectedBand)
        assertFalse(holder.ui.value.gestureOpen)

        val curve = awaitCurve { it.achievedDb.max() > flatMax + 0.5f }
        assertTrue(curve.achievedDb.max() > flatMax + 0.5f)

        assertEquals("the whole drag is one undo step", 1, undoDepth())
        val restored = controller.snapshot().bands[3]
        assertEquals(before, restored)
    }

    @Test fun aDragThatReturnsToItsStartLeavesNoUndoStep() {
        hostCard()
        assertFalse(controller.snapshot().canUndo)
        val start = node(3)
        rule.compose.onNodeWithTag("curve-interaction").performTouchInput {
            down(start)
            repeat(4) { moveBy(Offset(15f * density, -12f * density)) }
            repeat(4) { moveBy(Offset(-15f * density, 12f * density)) }
            up()
        }
        rule.compose.waitForIdle()
        assertEquals(1000f, controller.snapshot().bands[3].freq, 0.01f)
        assertEquals(0f, controller.snapshot().bands[3].gainDb, 0f)
        assertFalse("a drag that ends where it began adds no undo step", controller.snapshot().canUndo)
    }

    @Test fun aTouchFarFromEveryNodeIsNotConsumedAndChangesNothing() {
        hostCard()
        val plot = plotOf()
        val bandsBefore = controller.snapshot().bands
        // Top-right of the plot, well over 24 dp from every default node (all sit on the 0 dB line).
        val empty = Offset(plot.right - 20f * density, plot.top + 20f * density)
        downsConsumed.clear()
        dragFrom(empty, Offset(-40f * density, 30f * density), steps = 4)
        assertEquals(bandsBefore, controller.snapshot().bands)
        assertFalse(holder.ui.value.canUndo)
        assertEquals(null, holder.ui.value.selectedBand)
        assertEquals(listOf(false), downsConsumed.toList())

        // Control: a down on a node IS consumed.
        downsConsumed.clear()
        dragFrom(node(3), Offset(0f, -30f * density), steps = 3)
        assertEquals(listOf(true), downsConsumed.toList())
    }
}
