package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
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
}
