package com.dylenq.eq.ui

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The plot inside a scrolling page: only node gestures and pinches are taken from the page. */
@RunWith(AndroidJUnit4::class)
class CurveScrollConflictTest : CurveTestBase() {
    private val layer get() = rule.compose.onNodeWithTag("curve-interaction")

    private lateinit var scroll: androidx.compose.foundation.ScrollState
    private var view: android.view.View? = null

    private fun hostScrolling() = rule.host {
        density = LocalDensity.current.density
        view = LocalView.current
        scroll = rememberScrollState()
        CompositionLocalProvider(LocalHapticFeedback provides haptics) {
            // The viewport (500 dp) is shorter than the content; the card sits at the top of the content.
            Box(Modifier.height(500.dp).fillMaxWidth()) {
                Column(Modifier.verticalScroll(scroll).testTag("scroll-host")) {
                    Spacer(Modifier.height(20.dp))
                    Card()
                    Spacer(Modifier.height(1200.dp))
                }
            }
        }
    }

    @Test fun aSwipeStartingOnEmptyPlotScrollsThePageAndChangesNoBand() {
        hostScrolling()
        val bandsBefore = controller.snapshot().bands
        val plot = plotOf()
        val start = Offset(plot.left + plot.width * 0.62f, plot.top + plot.height * 0.15f)
        assertEquals(0, scroll.value)
        layer.performTouchInput {
            down(start)
            repeat(8) { moveBy(Offset(0f, -dp(15f))) }
            up()
        }
        rule.compose.waitForIdle()
        assertTrue("the page scrolled: ${scroll.value}", scroll.value > 0)
        assertEquals(bandsBefore, controller.snapshot().bands)
        assertFalse(controller.snapshot().canUndo)
    }

    @Test fun aSwipeStartingOnANodeMovesTheNodeAndDoesNotScroll() {
        hostScrolling()
        val before = controller.snapshot().bands[3]
        val start = node(3)
        layer.performTouchInput {
            down(start)
            repeat(6) { moveBy(Offset(0f, -dp(8f))) }
            up()
        }
        rule.compose.waitForIdle()
        assertEquals("the page did not scroll", 0, scroll.value)
        assertTrue("gain rose: ${controller.snapshot().bands[3].gainDb}", controller.snapshot().bands[3].gainDb > before.gainDb + 0.5f)
    }

    @Test fun aTwoFingerPinchNeverScrollsThePage() {
        hostScrolling()
        tapNode3()
        val c = node(3)
        // The pinch drifts upward as it spreads, which a page scroll would otherwise grab.
        layer.performTouchInput {
            pinch(
                Offset(c.x, c.y - dp(25f)), Offset(c.x, c.y - dp(60f)),
                Offset(c.x, c.y + dp(25f)), Offset(c.x, c.y + dp(30f)),
                durationMillis = 200,
            )
        }
        rule.compose.waitForIdle()
        assertEquals(0, scroll.value)
        assertTrue("Q changed: ${controller.snapshot().bands[3].q}", controller.snapshot().bands[3].q > 1.3f)

        // And a pinch with no target at all (nothing selected, far from nodes) is consumed too.
        layer.performTouchInput { down(Offset(1f, 1f)); up() } // deselect
        rule.compose.waitForIdle()
        val plot = plotOf()
        val far = Offset(plot.left + dp(30f), plot.top + dp(30f))
        layer.performTouchInput {
            pinch(
                Offset(far.x, far.y), Offset(far.x, far.y + dp(10f)),
                Offset(far.x + dp(20f), far.y), Offset(far.x + dp(20f), far.y - dp(10f)),
                durationMillis = 200,
            )
        }
        rule.compose.waitForIdle()
        assertEquals(0, scroll.value)
    }

    private fun tapNode3() {
        val p = node(3)
        layer.performTouchInput { down(p); up() }
        rule.compose.waitForIdle()
    }

    @Test fun theLayerExcludesItsBoundsFromSystemGestures() {
        rule.host {
            view = LocalView.current
            density = LocalDensity.current.density
            Box(Modifier.fillMaxWidth()) { Card() }
        }
        rule.compose.waitForIdle()
        val b = layer.fetchSemanticsNode().boundsInRoot
        var rects: List<AndroidRect> = emptyList()
        rule.compose.runOnUiThread { rects = view!!.systemGestureExclusionRects.toList() }
        assertTrue("an exclusion rect covers the layer: $rects vs $b", rects.any {
            it.left <= b.left + 1 && it.right >= b.right - 1 && it.top <= b.top + 1 && it.bottom >= b.bottom - 1
        })
    }
}
