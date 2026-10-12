package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisScaleTest {
    @Test fun xFractionEndsAndMiddle() {
        assertEquals(0f, AxisScale.xFraction(20f), 1e-6f)
        assertEquals(1f, AxisScale.xFraction(20000f), 1e-6f)
        assertEquals(0.5f, AxisScale.xFraction(632.4555f), 1e-4f)
    }

    @Test fun xFractionClamps() {
        assertEquals(0f, AxisScale.xFraction(5f), 0f)
        assertEquals(1f, AxisScale.xFraction(40000f), 0f)
    }

    @Test fun windowGrowsOnlyAboveEleven() {
        assertEquals(12, AxisScale.yWindow(12, 11.0f, false))
        assertEquals(24, AxisScale.yWindow(12, 11.01f, false))
        assertEquals(24, AxisScale.yWindow(12, 30f, false))
    }

    @Test fun windowShrinksOnlyBelowNine() {
        assertEquals(24, AxisScale.yWindow(24, 9.0f, false))
        assertEquals(12, AxisScale.yWindow(24, 8.99f, false))
        assertEquals(12, AxisScale.yWindow(24, 0f, false))
    }

    @Test fun hysteresisBandKeepsCurrent() {
        for (m in listOf(9.5f, 10f, 10.9f)) {
            assertEquals(12, AxisScale.yWindow(12, m, false))
            assertEquals(24, AxisScale.yWindow(24, m, false))
        }
    }

    @Test fun windowFrozenWhileDragging() {
        assertEquals(12, AxisScale.yWindow(12, 20f, true))
        assertEquals(24, AxisScale.yWindow(24, 0f, true))
    }

    @Test fun gridLinesPerWindow() {
        assertEquals(listOf(-12, -6, 0, 6, 12), AxisScale.gridLines(12))
        assertEquals(listOf(-24, -12, 0, 12, 24), AxisScale.gridLines(24))
    }

    @Test fun dbLabelsThinOutOnShortPlots() {
        assertEquals(listOf(-12, -6, 0, 6, 12), AxisScale.dbLabels(12, 220f))
        assertEquals(listOf(-12, 0, 12), AxisScale.dbLabels(12, 199f))
        assertEquals(listOf(-24, -12, 0, 12, 24), AxisScale.dbLabels(24, 150f))
    }

    @Test fun yFractionMapsWindowEdges() {
        assertEquals(1f, AxisScale.yFraction(-12f, 12), 1e-6f)
        assertEquals(0f, AxisScale.yFraction(12f, 12), 1e-6f)
        assertEquals(0.5f, AxisScale.yFraction(0f, 12), 1e-6f)
        assertEquals(0.25f, AxisScale.yFraction(12f, 24), 1e-6f)
    }

    @Test fun frequencyLabelSets() {
        assertEquals(10, AxisScale.frequencyLabels(1.0f).size)
        assertEquals(listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f), AxisScale.frequencyLabels(1.3f))
        assertEquals(listOf(100f, 1000f, 10000f), AxisScale.frequencyLabels(1.31f))
        assertTrue(AxisScale.frequencyLabels(2.0f).size == 3)
    }

    private val allHz = AxisScale.frequencyLabels(1.0f)

    private fun placement(plotWidth: Float, labelWidth: Float, gap: Float = 4f) =
        AxisScale.frequencyLabelXs(allHz, allHz.map { labelWidth }, 0f, plotWidth, gap)

    @Test fun tenKiloDropsWhenItWouldTouchTwentyKiloAtNarrowWidths() {
        // About 270 dp of plot at 360 dp wide: "10k" ends 2 dp short of "20k".
        val xs = placement(270f, 17f)
        assertNull("10k dropped", xs[8])
        assertEquals(0f, xs[0]!!, 0f)
        assertEquals(270f - 17f, xs[9]!!, 1e-3f)
    }

    @Test fun allTenLabelsStayWhenThereIsRoom() {
        // About 320 dp of plot at 411 dp wide, and a landscape-sized plot.
        assertTrue(placement(320f, 17f).all { it != null })
        assertTrue(placement(500f, 17f).all { it != null })
    }

    @Test fun noTwoKeptLabelsEverOverlapAndTheEndsAlwaysStay() {
        var w = 120f
        while (w <= 900f) {
            for (lw in listOf(14f, 17f, 22f, 30f)) {
                val xs = placement(w, lw)
                assertNotNull("20 Hz kept at $w/$lw", xs.first())
                assertNotNull("20 kHz kept at $w/$lw", xs.last())
                val kept = xs.mapIndexedNotNull { i, x -> x?.let { i to it } }
                for (a in kept.indices) for (b in a + 1 until kept.size) {
                    val (_, xa) = kept[a]
                    val (_, xb) = kept[b]
                    // The two end labels are always kept; if they collide with each other the plot is too
                    // narrow to draw anything sensible, which is outside the supported range.
                    if (w < 2 * lw + 8f) continue
                    assertTrue("overlap at plot $w label $lw: $xa vs $xb", xb >= xa + lw + 4f - 1e-3f || xa >= xb + lw + 4f - 1e-3f)
                }
            }
            w += 7f
        }
    }
}
