package com.dylenq.eq.ui

import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.core.EqMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The selected band's own response overlay (plan 05-05): data pipeline and drawing. */
@RunWith(AndroidJUnit4::class)
class ParametricOverlayTest : CurveTestBase() {
    private fun setBand(index: Int, f: (com.dylenq.eq.core.Band) -> com.dylenq.eq.core.Band) {
        val b = controller.snapshot().bands[index]
        controller.edit { it.setBand(index.toUInt(), f(b)) }
    }

    private fun awaitOverlay(pred: (FloatArray?) -> Boolean): CurvePoints = runBlocking {
        withTimeout(10_000) { holder.ui.first { it.curve?.let { c -> pred(c.selectedBandDb) } == true }.curve!! }
    }

    @Test fun selectedEnabledBandHasAnOverlayOfItsOwnResponse() {
        setBand(3, { it.copy(gainDb = 9f) })
        hostCard()
        holder.selectBand(3)
        val c = awaitOverlay { it != null }
        val o = c.selectedBandDb!!
        assertEquals(256, o.size)
        assertEquals(9f, o.max(), 0.5f)
        val nearest1k = c.freqsHz.indices.minByOrNull { kotlin.math.abs(c.freqsHz[it] - 1000f) }!!
        assertTrue("peak sits at the band frequency", kotlin.math.abs(o.indices.maxByOrNull { o[it] }!! - nearest1k) <= 3)
    }

    @Test fun deselectedOrDisabledBandHasNoOverlay() {
        setBand(3, { it.copy(gainDb = 9f) })
        hostCard()
        holder.selectBand(3)
        awaitOverlay { it != null }
        holder.selectBand(null)
        awaitOverlay { it == null }
        setBand(3, { it.copy(enabled = false) })
        holder.refreshCurve()
        holder.selectBand(3)
        awaitOverlay { it == null }
        assertNull(holder.ui.value.curve!!.selectedBandDb)
    }

    @Test fun graphicModeHasNoOverlay() {
        hostCard()
        holder.selectBand(3)
        awaitOverlay { it != null }
        controller.edit { it.setMode(EqMode.GRAPHIC) }
        holder.refreshCurve()
        awaitOverlay { it == null }
    }

    @Test fun editingTheSelectedBandUpdatesTheOverlay() {
        setBand(3, { it.copy(gainDb = 3f) })
        hostCard()
        holder.selectBand(3)
        awaitOverlay { it != null && it.max() in 2.5f..3.5f }
        holder.parametricGestureStart(3)
        holder.parametricNodeChange(3, 1000f, 9f)
        holder.parametricGestureEnd()
        awaitOverlay { it != null && it.max() > 8.5f }
    }

    @Test fun overlayIsDrawn() {
        setBand(3, { it.copy(gainDb = 9f) })
        hostCard()
        awaitCurve { true }
        rule.compose.waitForIdle()
        fun capture(): IntArray {
            val img = rule.compose.onNodeWithTag("curve").captureToImage()
            return IntArray(img.width * img.height).also { img.readPixels(it) }
        }
        holder.selectBand(null)
        awaitOverlay { it == null }
        rule.compose.waitForIdle()
        val without = capture()
        holder.selectBand(3)
        awaitOverlay { it != null }
        rule.compose.waitForIdle()
        // The "curve" node is the canvas only (nodes live on the interaction layer), so any pixel difference
        // between the two captures comes from the overlay.
        val with = capture()
        assertNotNull(holder.ui.value.curve!!.selectedBandDb)
        assertTrue("overlay changes pixels", !with.contentEquals(without))
    }

    // ---- readout chip and haptics (task 3) ----

    @Test fun readoutChipShowsWhileDraggingAndGoesAfterRelease() {
        hostCard()
        val start = node(3)
        rule.compose.onNodeWithTag("curve-interaction").performTouchInput {
            down(start)
            moveBy(androidx.compose.ui.geometry.Offset(0f, -dp(30f)))
            moveBy(androidx.compose.ui.geometry.Offset(0f, -dp(10f)))
        }
        rule.compose.waitForIdle()
        rule.compose.onNodeWithTag("node-readout", useUnmergedTree = true).assertExists()
        val shown = holder.ui.value.bands[3]
        // TalkBack must not see it.
        val sem = rule.compose.onNodeWithTag("node-readout", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("chip has no semantics of its own", sem.config.none { it.key.name == "Text" })
        assertEquals(3, holder.ui.value.draggingBand)
        assertTrue(shown.gainDb > 0f)
        rule.compose.onNodeWithTag("curve-interaction").performTouchInput { up() }
        rule.compose.waitForIdle()
        rule.compose.mainClock.advanceTimeBy(400)
        rule.compose.onNodeWithTag("node-readout", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun dragFiresStartTickAndEndHaptics() {
        hostCard()
        val start = node(3)
        rule.compose.onNodeWithTag("curve-interaction").performTouchInput {
            down(start)
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, -dp(12f))) }
            up()
        }
        rule.compose.waitForIdle()
        val t = haptics.types.toList()
        assertEquals(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureThresholdActivate, t.first())
        assertEquals(androidx.compose.ui.hapticfeedback.HapticFeedbackType.GestureEnd, t.last())
        assertTrue("at least one tick on the way: $t", t.size >= 3)
    }

}
