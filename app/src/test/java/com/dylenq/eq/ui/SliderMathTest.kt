package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderMathTest {
    @Test fun endsMapToRangeEnds() {
        assertEquals(-12f, SliderMath.gainFromFraction(0f), 0f)
        assertEquals(12f, SliderMath.gainFromFraction(1f), 0f)
        assertEquals(0f, SliderMath.gainFromFraction(0.5f), 0f)
    }

    @Test fun clampsOutOfRangeFractions() {
        assertEquals(-12f, SliderMath.gainFromFraction(-3f), 0f)
        assertEquals(12f, SliderMath.gainFromFraction(7f), 0f)
    }

    @Test fun snapsToHalfDecibelSteps() {
        assertEquals(3.5f, SliderMath.snap(3.6f), 0f)
        assertEquals(3.5f, SliderMath.snap(3.3f), 0f)
        assertEquals(-2.0f, SliderMath.snap(-2.2f), 0f)
        assertEquals(0.5f, SliderMath.snap(0.3f), 0f)
    }

    @Test fun zeroDetentWithinQuarterDb() {
        assertEquals(0f, SliderMath.snap(0.24f), 0f)
        assertEquals(0f, SliderMath.snap(-0.24f), 0f)
        assertEquals(0f, SliderMath.snap(0.25f), 0f)
        assertEquals(0f, SliderMath.snap(-0.25f), 0f)
        assertEquals(0.5f, SliderMath.snap(0.26f), 0f)
        assertEquals(-0.5f, SliderMath.snap(-0.26f), 0f)
        // never a negative zero
        assertEquals(0, java.lang.Float.floatToIntBits(SliderMath.snap(-0.1f)))
    }

    @Test fun fractionFromGainPinsBeyondRange() {
        assertEquals(1f, SliderMath.fractionFromGain(24f), 0f)
        assertEquals(0f, SliderMath.fractionFromGain(-24f), 0f)
        assertEquals(0.5f, SliderMath.fractionFromGain(0f), 0f)
        assertEquals(0.75f, SliderMath.fractionFromGain(6f), 1e-6f)
    }

    @Test fun roundTripIsStableOnTheStepGrid() {
        var g = -12f
        while (g <= 12f) {
            assertEquals(g, SliderMath.gainFromFraction(SliderMath.fractionFromGain(g)), 0f)
            g += 0.5f
        }
    }

    @Test fun stepsCountIs47() {
        assertEquals(47, SliderMath.STEPS)
    }

    @Test fun wholeDbCrossings() {
        assertEquals(0, SliderMath.wholeDbCrossings(1.2f, 1.9f))
        assertEquals(1, SliderMath.wholeDbCrossings(1.5f, 2.0f))
        assertEquals(3, SliderMath.wholeDbCrossings(0.5f, 3.5f))
        assertEquals(3, SliderMath.wholeDbCrossings(3.5f, 0.5f))
        assertEquals(1, SliderMath.wholeDbCrossings(-0.5f, 0.5f))
    }

    @Test fun enteredZero() {
        assertTrue(SliderMath.enteredZero(0.5f, 0f))
        assertFalse(SliderMath.enteredZero(0f, 0f))
        assertFalse(SliderMath.enteredZero(0.5f, 1f))
    }

    // The sliders row at 360 dp: 360 - 2*16 screen margins - 2*8 card padding - 32 dB scale column = 280.
    private val row360 = 280f

    @Test fun tenBandsFitAt360() {
        val p = SliderMath.columnPitch(row360, 10, 1f)
        assertEquals(28f, p, 1e-4f)
        assertTrue(SliderMath.fitsWithoutScroll(p, 10, row360))
    }

    @Test fun fiveBandsFitWithGainLabels() {
        val p = SliderMath.columnPitch(row360, 5, 1f)
        assertEquals(56f, p, 1e-4f)
        assertTrue(SliderMath.showsGainLabel(p))
    }

    @Test fun fifteenAndThirtyOneScroll() {
        for (n in listOf(15, 31)) {
            val p = SliderMath.columnPitch(row360, n, 1f)
            assertEquals(32f, p, 0f)
            assertFalse(SliderMath.fitsWithoutScroll(p, n, row360))
            assertFalse(SliderMath.showsGainLabel(p))
        }
    }

    @Test fun largeFontPitchIs48() {
        assertEquals(48f, SliderMath.columnPitch(row360, 10, 1.3f), 0f)
        assertEquals(48f, SliderMath.columnPitch(row360, 31, 2f), 0f)
        assertTrue(SliderMath.showsGainLabel(48f))
        assertFalse(SliderMath.fitsWithoutScroll(48f, 10, row360))
    }

    @Test fun wideScreensFifteenBandsFit() {
        // >= 840 dp two-pane: a row of 600 dp holds 15 bands at 40 dp.
        val p = SliderMath.columnPitch(600f, 15, 1f)
        assertEquals(40f, p, 1e-4f)
        assertTrue(SliderMath.fitsWithoutScroll(p, 15, 600f))
    }
}
