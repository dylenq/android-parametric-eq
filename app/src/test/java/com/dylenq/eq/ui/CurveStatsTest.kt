package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveStatsTest {
    private val f = floatArrayOf(20f, 100.2f, 1000f, 2000f, 20000f)

    @Test fun peakAndTrough() {
        val a = floatArrayOf(0f, -3f, 1f, 4.5f, 0f)
        val p = CurveStats.peak(f, a)
        assertEquals(4.5f, p.db, 0f); assertEquals(2000f, p.hz, 0f)
        val t = CurveStats.trough(f, a)
        assertEquals(-3f, t.db, 0f); assertEquals(100.2f, t.hz, 0f)
    }

    @Test fun maxDiffAndThresholds() {
        val ideal = floatArrayOf(0f, -5f, 0f, 0f, 0f)
        val ach = floatArrayOf(0f, -2.66f, 0f, 0f, 0f)
        val d = CurveStats.maxDiff(f, ideal, ach)
        assertEquals(2.34f, d.db, 1e-4f); assertEquals(100.2f, d.hz, 0f)
        assertTrue(CurveStats.targetVisible(d.db))
        assertTrue(CurveStats.captionVisible(d.db))
        assertFalse(CurveStats.targetVisible(0.49f))
        assertTrue(CurveStats.targetVisible(0.5f))
        assertFalse(CurveStats.captionVisible(0.99f))
        assertTrue(CurveStats.captionVisible(1.0f))
    }

    @Test fun captionText() {
        val ideal = floatArrayOf(0f, -5f, 0f, 0f, 0f)
        val ach = floatArrayOf(0f, -2.66f, 0f, 0f, 0f)
        assertEquals("Actual differs from target by up to 2.3 dB near 100 Hz.", CurveStats.caption(f, ideal, ach))
    }

    @Test fun captionUsesKhzForThousands() {
        val ideal = floatArrayOf(0f, 0f, 3f, 0f, 0f)
        val ach = floatArrayOf(0f, 0f, 0f, 0f, 0f)
        assertEquals("Actual differs from target by up to 3.0 dB near 1 kHz.", CurveStats.caption(f, ideal, ach))
    }

    @Test fun captionAbsentBelowOneDb() {
        val ideal = floatArrayOf(0f, 0.9f, 0f, 0f, 0f)
        assertNull(CurveStats.caption(f, ideal, FloatArray(5)))
    }

    @Test fun headroomChip() {
        assertEquals("Headroom 3.5 dB", CurveStats.headroomText(3.5f))
        assertEquals("Headroom 0.0 dB", CurveStats.headroomText(0f))
        assertEquals("Headroom 0.0 dB", CurveStats.headroomText(-0.03f))
        assertEquals("Over by 2.5 dB", CurveStats.headroomText(-2.5f))
        assertFalse(CurveStats.isOver(-0.05f))
        assertTrue(CurveStats.isOver(-0.0501f))
    }

    @Test fun clipSegmentsEmptyForFlatCurve() {
        assertTrue(CurveStats.clipSegments(FloatArray(8), 0f).isEmpty())
        assertTrue(CurveStats.clipSegments(floatArrayOf(-1f, -2f, 0f), 0f).isEmpty())
    }

    @Test fun clipSegmentsFindRuns() {
        val a = floatArrayOf(0f, 1f, 2f, 0f, 3f, 0f)
        // limit is -preamp = 0.5: indices 1,2 and 4 exceed it
        assertEquals(listOf(1..2, 4..4), CurveStats.clipSegments(a, -0.5f))
        // run reaching the end
        assertEquals(listOf(4..5), CurveStats.clipSegments(floatArrayOf(0f, 0f, 0f, 0f, 2f, 2f), -1f))
    }

    @Test fun stateDescription() {
        val a = floatArrayOf(0f, -3f, 1f, 4.5f, 0f)
        assertEquals(
            "Peak plus 4.5 decibels at 2 kilohertz. Lowest minus 3 decibels at 100 hertz.",
            CurveStats.stateDescription(f, a, a, bypass = false),
        )
    }

    @Test fun stateDescriptionIncludesDifferenceOnlyWhenShown() {
        val ideal = floatArrayOf(0f, -5f, 1f, 4.5f, 0f)
        val a = floatArrayOf(0f, -2.66f, 1f, 4.5f, 0f)
        assertEquals(
            "Peak plus 4.5 decibels at 2 kilohertz. Lowest minus 2.7 decibels at 100 hertz. " +
                "Actual differs from target by up to 2.3 decibels near 100 hertz.",
            CurveStats.stateDescription(f, ideal, a, bypass = false),
        )
    }

    @Test fun bypassedDescription() {
        assertEquals("Equalizer is off. Curve not applied.", CurveStats.stateDescription(f, FloatArray(5), FloatArray(5), bypass = true))
    }
}
