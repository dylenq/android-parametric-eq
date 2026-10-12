package com.dylenq.eq.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.advanceEventTime
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.testsupport.EqComposeRule
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave 0 probe (phase 05, L-05): proves long-press, two-pointer pinch and cancel() can be injected
 * with the Compose test clock into a hand-written awaitEachGesture state machine in this project.
 * Kept as a cheap regression guard of the injection recipes recorded in 05-PROBE-RESULT.md.
 */
@RunWith(AndroidJUnit4::class)
class LongPressClockProbeTest {
    @get:Rule val rule = EqComposeRule()

    private val longPress = AtomicInteger()
    private val tap = AtomicInteger()
    private val drag = AtomicInteger()
    private val cancelled = AtomicInteger()
    private val ended = AtomicInteger()
    @Volatile private var zoom = 1f
    @Volatile private var longTimeout = 0L

    private fun hostProbe() {
        rule.host {
            val vc = LocalViewConfiguration.current
            longTimeout = vc.longPressTimeoutMillis
            Box(
                Modifier
                    .size(300.dp)
                    .testTag("probe")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            var completed = false
                            try {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var multi = false
                                var isLong = false
                                var isDrag = false
                                var isUp = false
                                val r = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                    while (true) {
                                        val e = awaitPointerEvent()
                                        if (e.changes.size >= 2 && e.changes.count { it.pressed } >= 2) {
                                            multi = true
                                            return@withTimeoutOrNull
                                        }
                                        val c = e.changes.firstOrNull { it.id == down.id } ?: continue
                                        if (!c.pressed) { isUp = true; return@withTimeoutOrNull }
                                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                            isDrag = true
                                            return@withTimeoutOrNull
                                        }
                                    }
                                }
                                if (r == null) isLong = true
                                when {
                                    multi -> {
                                        // Zoom reader: keep going until all pointers are up.
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            zoom *= e.calculateZoom()
                                            e.changes.forEach { it.consume() }
                                            if (e.changes.none { it.pressed }) break
                                        }
                                    }
                                    isLong -> {
                                        longPress.incrementAndGet()
                                        // Drain until release.
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            if (e.changes.none { it.pressed }) break
                                        }
                                    }
                                    isDrag -> {
                                        drag.incrementAndGet()
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            if (e.changes.none { it.pressed }) break
                                        }
                                    }
                                    isUp -> tap.incrementAndGet()
                                }
                                completed = true
                                ended.incrementAndGet()
                            } finally {
                                if (!completed) cancelled.incrementAndGet()
                            }
                        }
                    },
            )
        }
    }

    private fun waitForCancelled(expected: Int) {
        // cancel() cancels pointer input delivery; give the coroutine a moment to unwind.
        rule.compose.waitUntil(3_000) { cancelled.get() >= expected }
    }

    @Test fun case1_longClick_reaches_long_press_only() {
        hostProbe()
        rule.compose.onNodeWithTag("probe").performTouchInput { longClick() }
        rule.compose.waitForIdle()
        assertEquals("LONG_PRESS", 1, longPress.get())
        assertEquals("TAP", 0, tap.get())
    }

    @Test fun case2_explicit_down_advance_up_reaches_long_press() {
        hostProbe()
        rule.compose.onNodeWithTag("probe").performTouchInput {
            down(0, center)
            advanceEventTime(longTimeout + 100)
            up(0)
        }
        rule.compose.waitForIdle()
        assertEquals("LONG_PRESS", 1, longPress.get())
        assertEquals("TAP", 0, tap.get())
    }

    @Test fun case3_short_press_is_tap() {
        hostProbe()
        rule.compose.onNodeWithTag("probe").performTouchInput {
            down(0, center)
            advanceEventTime(50)
            up(0)
        }
        rule.compose.waitForIdle()
        assertEquals("TAP", 1, tap.get())
        assertEquals("LONG_PRESS", 0, longPress.get())
    }

    @Test fun case4_drag_before_timeout_is_drag() {
        hostProbe()
        rule.compose.onNodeWithTag("probe").performTouchInput {
            down(0, center)
            advanceEventTime(20)
            moveBy(0, Offset(0f, 80f))
            advanceEventTime(20)
            moveBy(0, Offset(0f, 40f))
            up(0)
        }
        rule.compose.waitForIdle()
        assertEquals("DRAG", 1, drag.get())
        assertEquals("LONG_PRESS", 0, longPress.get())
    }

    @Test fun case5_pinch_accumulates_zoom_and_cancels_long_press() {
        hostProbe()
        rule.compose.onNodeWithTag("probe").performTouchInput {
            val c = center
            pinch(
                start0 = c + Offset(-50f, 0f), end0 = c + Offset(-100f, 0f),
                start1 = c + Offset(50f, 0f), end1 = c + Offset(100f, 0f),
                durationMillis = 200,
            )
        }
        rule.compose.waitForIdle()
        assertTrue("zoom=$zoom", zoom in 1.7f..2.3f)
        assertEquals("LONG_PRESS", 0, longPress.get())
    }

    @Test fun case6_explicit_multi_pointer_sequence() {
        hostProbe()
        rule.compose.onNodeWithTag("probe").performTouchInput {
            val c = center
            down(0, c + Offset(-50f, 0f))
            advanceEventTime(20)
            down(1, c + Offset(50f, 0f))
            advanceEventTime(20)
            moveTo(1, c + Offset(100f, 0f))
            advanceEventTime(20)
            moveTo(0, c + Offset(-100f, 0f))
            advanceEventTime(20)
            up(1)
            up(0)
        }
        rule.compose.waitForIdle()
        assertTrue("zoom=$zoom", zoom in 1.7f..2.3f)
        assertEquals("LONG_PRESS", 0, longPress.get())
    }

    private fun counters() = "tap=${tap.get()} drag=${drag.get()} long=${longPress.get()} ended=${ended.get()} cancelled=${cancelled.get()} zoom=$zoom"

    /** cancel() mid-gesture: reports what the hand-written machine observes (see 05-PROBE-RESULT.md). */
    @Test fun case7_cancel_mid_gesture_observation() {
        hostProbe()
        rule.compose.onNodeWithTag("probe").performTouchInput {
            down(0, center)
            advanceEventTime(20)
            moveBy(0, Offset(0f, 5f))
            cancel()
        }
        rule.compose.waitForIdle()
        println("PROBE case7 counters: " + counters())
        // The gesture must have ended one way or the other and must NOT be a long-press.
        assertEquals("LONG_PRESS", 0, longPress.get())
        assertEquals("gesture ended exactly once: " + counters(), 1, ended.get() + cancelled.get())
    }
}
