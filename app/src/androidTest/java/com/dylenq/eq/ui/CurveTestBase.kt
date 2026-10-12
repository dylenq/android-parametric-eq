package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Rule

/** Records every haptic the layer fires. */
class FakeHaptics : HapticFeedback {
    val types = CopyOnWriteArrayList<HapticFeedbackType>()
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        types.add(hapticFeedbackType)
    }
}

/** Shared host and helpers of the Parametric curve gesture tests (CurveGesture, CurvePinch, CurveScrollConflict). */
abstract class CurveTestBase {
    @get:Rule val rule = EqComposeRule()

    protected val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    protected val holder get() = app.uiHolder()
    protected val controller get() = rule.runtime.controller

    protected var density = 1f
    protected var longPressMs = 0L
    protected val longPresses = CopyOnWriteArrayList<Int>()
    protected val pinchCompleted = AtomicInteger()
    protected val haptics = FakeHaptics()

    @Before fun parametricMode() {
        controller.edit { it.setMode(EqMode.PARAMETRIC) }
    }

    protected fun actions() = ParametricActions(
        onSelect = holder::selectBand,
        onDragStart = holder::parametricGestureStart,
        onNodeChange = holder::parametricNodeChange,
        onDragEnd = holder::parametricGestureEnd,
        onAddBand = holder::addBandAt,
        onQChange = holder::setBandQ,
        onLongPress = { longPresses.add(it) },
        onPinchCompleted = { pinchCompleted.incrementAndGet() },
        onDeselect = { holder.selectBand(null) },
    )

    /** The card with its interaction layer filling the window. */
    @androidx.compose.runtime.Composable
    protected fun Card() {
        val ui by holder.ui.collectAsStateWithLifecycle()
        val a = remember { actions() }
        CurveCard({ ui }, onApplySuggested = {}, parametric = a)
    }

    protected fun hostCard() = rule.host {
        density = LocalDensity.current.density
        longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
        CompositionLocalProvider(LocalHapticFeedback provides haptics) {
            Box(Modifier.fillMaxSize()) { Card() }
        }
    }

    protected fun plotOf(): Rect {
        val size = rule.compose.onNodeWithTag("curve-interaction").fetchSemanticsNode().size
        return PlotGeometry.plotRect(size.width.toFloat(), size.height.toFloat(), density)
    }

    protected fun node(index: Int): Offset {
        val b = controller.snapshot().bands[index]
        val hasGain = b.kind.hasGain()
        return ParametricMath.nodePosition(b.freq, b.gainDb, hasGain, plotOf(), AxisScale.PARAMETRIC_Y_HALF_SPAN_DB)
    }

    protected fun awaitCurve(pred: (CurvePoints) -> Boolean): CurvePoints = runBlocking {
        withTimeout(10_000) { holder.ui.first { it.curve?.let(pred) == true }.curve!! }
    }

    /** Undoes until nothing is left and counts the steps. */
    protected fun undoDepth(): Int {
        var n = 0
        while (controller.snapshot().canUndo && n < 50) {
            holder.undo()
            n++
        }
        return n
    }
}
