package com.dylenq.eq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.FilterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeReadoutTest {
    private fun band(kind: FilterKind, f: Float, g: Float, q: Float) = Band(kind, f, g, q, true)

    @Test fun peakReadoutHasNumberFrequencyGainAndQ() {
        assertEquals("3  1.25 kHz  +3.5 dB  Q 1.00", NodeReadout.text(2, band(FilterKind.PEAK, 1250f, 3.5f, 1f)))
    }

    @Test fun passKindsOmitGain() {
        assertEquals("3  1.25 kHz  Q 0.71", NodeReadout.text(2, band(FilterKind.HIGH_PASS, 1250f, 0f, 0.71f)))
        assertEquals("1  8 kHz  Q 0.71", NodeReadout.text(0, band(FilterKind.LOW_PASS, 8000f, 6f, 0.707f)))
    }

    @Test fun frequencyUnitsSwitchAtOneKilohertz() {
        assertEquals("1  63 Hz  +5.0 dB  Q 1.00", NodeReadout.text(0, band(FilterKind.LOW_SHELF, 63f, 5f, 1f)))
        assertEquals("1  31.5 Hz  +5.0 dB  Q 1.00", NodeReadout.text(0, band(FilterKind.PEAK, 31.5f, 5f, 1f)))
        assertEquals("1  1 kHz  +5.0 dB  Q 1.00", NodeReadout.text(0, band(FilterKind.PEAK, 1000f, 5f, 1f)))
        assertEquals("1  12.5 kHz  +5.0 dB  Q 1.00", NodeReadout.text(0, band(FilterKind.PEAK, 12500f, 5f, 1f)))
    }

    @Test fun gainUsesTheUnicodeMinusAndNoSignAtZero() {
        assertEquals("2  100 Hz  −2.0 dB  Q 4.00", NodeReadout.text(1, band(FilterKind.PEAK, 100f, -2f, 4f)))
        assertEquals("2  100 Hz  0 dB  Q 0.10", NodeReadout.text(1, band(FilterKind.PEAK, 100f, 0f, 0.1f)))
    }

    @Test fun qIsAlwaysTwoDecimals() {
        assertTrue(NodeReadout.text(0, band(FilterKind.PEAK, 100f, 1f, 18f)).endsWith("Q 18.00"))
        assertTrue(NodeReadout.text(0, band(FilterKind.PEAK, 100f, 1f, 1.005f)).matches(Regex(".*Q \\d+\\.\\d\\d")))
    }

    private val plot = Rect(36f, 8f, 340f, 200f)

    @Test fun chipSitsAboveTheNodeCentred() {
        val p = NodeReadout.position(Offset(200f, 150f), 100f, 24f, plot, 56f)
        assertEquals(150f, p.x, 0.01f)
        assertEquals(150f - 56f - 12f, p.y, 0.01f)
    }

    @Test fun chipGoesBelowWhenThereIsNoRoomAbove() {
        val p = NodeReadout.position(Offset(200f, 40f), 100f, 24f, plot, 56f)
        assertEquals(40f + 56f - 12f, p.y, 0.01f)
    }

    @Test fun chipIsClampedHorizontallyInsideThePlot() {
        assertEquals(plot.left, NodeReadout.position(Offset(36f, 150f), 100f, 24f, plot, 56f).x, 0.01f)
        assertEquals(plot.right - 100f, NodeReadout.position(Offset(340f, 150f), 100f, 24f, plot, 56f).x, 0.01f)
    }

    @Test fun chipIsClampedVerticallyWhenBothSidesLackRoom() {
        val tiny = Rect(0f, 0f, 300f, 60f)
        val p = NodeReadout.position(Offset(150f, 30f), 100f, 24f, tiny, 56f)
        assertTrue(p.y >= tiny.top && p.y + 24f <= tiny.bottom)
    }

    @Test fun gridCrossingsDetectTheGridFrequencies() {
        assertTrue(FreqGridCrossing.crossed(95f, 105f))
        assertTrue(FreqGridCrossing.crossed(105f, 95f))
        assertFalse(FreqGridCrossing.crossed(110f, 190f))
        assertFalse(FreqGridCrossing.crossed(100f, 100f))
        assertTrue(FreqGridCrossing.crossed(400f, 600f))
    }

    @Test fun tickLimiterAllowsOneTickPer40Ms() {
        var now = 0L
        val l = TickLimiter(40) { now }
        assertTrue(l.tryTick())
        now = 10; assertFalse(l.tryTick())
        now = 39; assertFalse(l.tryTick())
        now = 40; assertTrue(l.tryTick())
        now = 79; assertFalse(l.tryTick())
        now = 80; assertTrue(l.tryTick())
    }

    @Test fun tickLimiterIsSharedAcrossKinds() {
        var now = 1000L
        val l = TickLimiter(40) { now }
        val fired = listOf(l.tryTick(), l.tryTick()).count { it }
        assertEquals(1, fired)
    }
}
