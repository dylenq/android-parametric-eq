package com.dylenq.eq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ParametricMathTest {
    private val plot = Rect(36f, 8f, 392f, 280f)
    private val win = AxisScale.PARAMETRIC_Y_HALF_SPAN_DB

    // ---- inverse axes ----

    @Test fun hzAtInvertsXFraction() {
        for (hz in listOf(20f, 100f, 1000f, 20000f)) {
            val x = plot.left + AxisScale.xFraction(hz) * plot.width
            assertEquals(hz, ParametricMath.hzAt(x, plot), hz * 1e-3f)
        }
    }

    @Test fun hzAtClampsOutsideThePlot() {
        assertEquals(20f, ParametricMath.hzAt(-500f, plot), 1e-3f)
        assertEquals(20000f, ParametricMath.hzAt(5000f, plot), 1f)
    }

    @Test fun dbAtInvertsYFraction() {
        for (db in listOf(-24f, 0f, 12f, 24f)) {
            val y = plot.top + AxisScale.yFraction(db, win) * plot.height
            assertEquals(db, ParametricMath.dbAt(y, plot, win), 1e-3f)
        }
    }

    @Test fun dbAtClampsToTheCoreLimit() {
        assertEquals(24f, ParametricMath.dbAt(-1000f, plot, win), 0f)
        assertEquals(-24f, ParametricMath.dbAt(5000f, plot, win), 0f)
    }

    // ---- snapping ----

    @Test fun snapHzToThreeSignificantDigits() {
        assertEquals(1230f, ParametricMath.snapHz(1234.5f), 0f)
        assertEquals(20000f, ParametricMath.snapHz(19990f), 0f)
        assertEquals(20f, ParametricMath.snapHz(20f), 0f)
        assertEquals(100f, ParametricMath.snapHz(99.96f), 0f)
        assertEquals(31.5f, ParametricMath.snapHz(31.46f), 1e-4f)
        assertEquals(12300f, ParametricMath.snapHz(12345f), 0f)
    }

    @Test fun snapHzStaysInRange() {
        assertEquals(20f, ParametricMath.snapHz(3f), 0f)
        assertEquals(20000f, ParametricMath.snapHz(50000f), 0f)
    }

    @Test fun snapDbToTenthsWithZeroDetent() {
        assertEquals(3.5f, ParametricMath.snapDb(3.54f), 1e-4f)
        assertEquals(0f, ParametricMath.snapDb(-0.2f), 0f)
        assertEquals(0f, ParametricMath.snapDb(0.25f), 0f)
        assertEquals(0.3f, ParametricMath.snapDb(0.26f), 1e-4f)
        assertEquals(24f, ParametricMath.snapDb(24.7f), 0f)
        assertEquals(-24f, ParametricMath.snapDb(-30f), 0f)
        val v = ParametricMath.snapDb(7.349f)
        assertTrue(abs(v * 10f - Math.round(v * 10f)) < 1e-3f)
    }

    // ---- node position ----

    @Test fun nodePositionFollowsFrequencyAndGain() {
        val p = ParametricMath.nodePosition(1000f, 6f, hasGain = true, plot, win)
        assertEquals(plot.left + AxisScale.xFraction(1000f) * plot.width, p.x, 1e-3f)
        assertEquals(plot.top + AxisScale.yFraction(6f, win) * plot.height, p.y, 1e-3f)
    }

    @Test fun passFilterNodesSitOnZeroDb() {
        val p = ParametricMath.nodePosition(80f, 9f, hasGain = false, plot, win)
        assertEquals(plot.top + 0.5f * plot.height, p.y, 1e-3f)
    }

    // ---- hit test ----

    private val r = 24f

    @Test fun pickNodeNearestWins() {
        val nodes = listOf(Offset(100f, 100f), Offset(130f, 100f))
        assertEquals(1, ParametricMath.pickNode(nodes, null, Offset(121f, 100f), r))
        assertEquals(0, ParametricMath.pickNode(nodes, null, Offset(105f, 100f), r))
    }

    @Test fun pickNodeOutsideRadiusIsNull() {
        assertNull(ParametricMath.pickNode(listOf(Offset(100f, 100f)), null, Offset(160f, 100f), r))
    }

    @Test fun pickNodeEmptyListIsNull() {
        assertNull(ParametricMath.pickNode(emptyList(), null, Offset(1f, 1f), r))
    }

    @Test fun pickNodeTiePrefersSelected() {
        val nodes = listOf(Offset(100f, 100f), Offset(110f, 100f))
        assertEquals(0, ParametricMath.pickNode(nodes, 0, Offset(105f, 100f), r))
        assertEquals(1, ParametricMath.pickNode(nodes, 1, Offset(105f, 100f), r))
    }

    @Test fun pickNodeTieWithoutSelectionPrefersHigherIndex() {
        val nodes = listOf(Offset(100f, 100f), Offset(110f, 100f))
        assertEquals(1, ParametricMath.pickNode(nodes, null, Offset(105f, 100f), r))
        assertEquals(1, ParametricMath.pickNode(nodes, 7, Offset(105f, 100f), r))
    }

    @Test fun pickNodeNearTieWithinFourPx() {
        // distances 5 and 8.5: within the 4 px band, so the selected one wins
        val nodes = listOf(Offset(100f, 100f), Offset(113.5f, 100f))
        assertEquals(1, ParametricMath.pickNode(nodes, 1, Offset(105f, 100f), r))
        // distances 5 and 14: clearly nearer wins even though the other is selected
        val far = listOf(Offset(100f, 100f), Offset(119f, 100f))
        assertEquals(0, ParametricMath.pickNode(far, 1, Offset(105f, 100f), r))
    }

    // ---- tap cycling ----

    @Test fun tapCycleAdvancesThroughAStack() {
        val nodes = listOf(Offset(100f, 100f), Offset(104f, 100f), Offset(102f, 104f))
        val t = Offset(102f, 101f)
        assertEquals(1, ParametricMath.tapCycleTarget(nodes, 0, t, r))
        assertEquals(2, ParametricMath.tapCycleTarget(nodes, 1, t, r))
        assertEquals(0, ParametricMath.tapCycleTarget(nodes, 2, t, r))
    }

    @Test fun tapCycleSingleCandidateReturnsIt() {
        val nodes = listOf(Offset(100f, 100f), Offset(300f, 100f))
        assertEquals(0, ParametricMath.tapCycleTarget(nodes, 0, Offset(101f, 100f), r))
        assertEquals(0, ParametricMath.tapCycleTarget(nodes, 1, Offset(101f, 100f), r))
    }

    @Test fun tapCycleSelectedNotInStackUsesPickRule() {
        val nodes = listOf(Offset(100f, 100f), Offset(120f, 100f), Offset(300f, 100f))
        assertEquals(1, ParametricMath.tapCycleTarget(nodes, 2, Offset(118f, 100f), r))
    }

    @Test fun tapCycleNothingNearIsNull() {
        assertNull(ParametricMath.tapCycleTarget(listOf(Offset(100f, 100f)), null, Offset(300f, 300f), r))
    }

    // ---- pinch ----

    @Test fun pinchQScalesWithDistanceRatio() {
        assertEquals(2.8f, ParametricMath.qAfterPinch(1.4f, 100f, 200f), 1e-4f)
        assertEquals(0.7f, ParametricMath.qAfterPinch(1.4f, 100f, 50f), 1e-4f)
    }

    @Test fun pinchQClampsAndRounds() {
        assertEquals(18f, ParametricMath.qAfterPinch(10f, 100f, 400f), 0f)
        assertEquals(0.1f, ParametricMath.qAfterPinch(0.2f, 100f, 10f), 1e-6f)
        assertEquals(1.43f, ParametricMath.qAfterPinch(1f, 100f, 143.3f), 1e-4f)
    }

    @Test fun pinchQDegenerateStartReturnsQ0() {
        assertEquals(1.4f, ParametricMath.qAfterPinch(1.4f, 0f, 100f), 0f)
        assertEquals(1.4f, ParametricMath.qAfterPinch(1.4f, -3f, 100f), 0f)
    }

    @Test fun pinchExponentIsNamedAndOne() {
        assertEquals(1.0f, ParametricMath.PINCH_EXPONENT, 0f)
    }

    @Test fun pinchTargetNearestToCentroidElseSelectedElseNull() {
        val nodes = listOf(Offset(100f, 100f), Offset(300f, 100f))
        val rad = 72f
        assertEquals(1, ParametricMath.pinchTarget(nodes, Offset(280f, 110f), 0, rad))
        assertEquals(0, ParametricMath.pinchTarget(nodes, Offset(200f, 300f), 0, rad))
        assertNull(ParametricMath.pinchTarget(nodes, Offset(200f, 300f), null, rad))
        assertNull(ParametricMath.pinchTarget(emptyList(), Offset(0f, 0f), null, rad))
    }

    // ---- free frequency for Add band ----

    @Test fun nextFreeFrequencyWalksTheSequence() {
        assertEquals(1000f, ParametricMath.nextFreeFrequency(emptyList()), 0f)
        assertEquals(250f, ParametricMath.nextFreeFrequency(listOf(1000f to true)), 0f)
        assertEquals(4000f, ParametricMath.nextFreeFrequency(listOf(1000f to true, 250f to true)), 0f)
    }

    @Test fun nextFreeFrequencyIgnoresDisabledBands() {
        assertEquals(1000f, ParametricMath.nextFreeFrequency(listOf(1000f to false)), 0f)
    }

    @Test fun nextFreeFrequencyUsesAThirdOctaveExclusion() {
        // 1200 Hz is within 1/3 octave of 1000, so 1000 is taken; 250 is free
        assertEquals(250f, ParametricMath.nextFreeFrequency(listOf(1200f to true)), 0f)
        // 1300 Hz is 0.38 octave away: 1000 is still free
        assertEquals(1000f, ParametricMath.nextFreeFrequency(listOf(1300f to true)), 0f)
    }

    @Test fun nextFreeFrequencyFallsBackToOneKhz() {
        val all = listOf(1000f, 250f, 4000f, 100f, 10000f, 60f, 2000f, 8000f).map { it to true }
        assertEquals(1000f, ParametricMath.nextFreeFrequency(all), 0f)
    }

    // ---- double tap to add ----

    @Test fun doubleTapAddsOnEmptySpace() {
        val nodes = listOf(Offset(300f, 300f))
        assertTrue(ParametricMath.isDoubleTapAdd(Offset(100f, 100f), Offset(110f, 105f), 200, 300, nodes, 1f))
    }

    @Test fun doubleTapTooSlowOrTooFarOrOnNodeDoesNotAdd() {
        val nodes = listOf(Offset(300f, 300f))
        assertFalse(ParametricMath.isDoubleTapAdd(Offset(100f, 100f), Offset(110f, 105f), 400, 300, nodes, 1f))
        assertFalse(ParametricMath.isDoubleTapAdd(Offset(100f, 100f), Offset(200f, 100f), 200, 300, nodes, 1f))
        assertFalse(ParametricMath.isDoubleTapAdd(Offset(295f, 300f), Offset(300f, 310f), 200, 300, nodes, 1f))
    }

    @Test fun doubleTapDistancesScaleWithDensity() {
        val nodes = emptyList<Offset>()
        // 60 px apart: too far at density 1 (48 dp), fine at density 2 (96 px)
        assertFalse(ParametricMath.isDoubleTapAdd(Offset(0f, 0f), Offset(60f, 0f), 100, 300, nodes, 1f))
        assertTrue(ParametricMath.isDoubleTapAdd(Offset(0f, 0f), Offset(60f, 0f), 100, 300, nodes, 2f))
    }

    // ---- constants ----

    @Test fun constantsMirrorTheCore() {
        assertEquals(20f, ParametricMath.MIN_HZ, 0f)
        assertEquals(20000f, ParametricMath.MAX_HZ, 0f)
        assertEquals(0.1f, ParametricMath.MIN_Q, 0f)
        assertEquals(18f, ParametricMath.MAX_Q, 0f)
        assertEquals(24f, ParametricMath.MAX_GAIN_DB, 0f)
        assertEquals(16, ParametricMath.MAX_BANDS)
        assertEquals(24f, ParametricMath.HIT_RADIUS_DP, 0f)
        assertEquals(72f, ParametricMath.PINCH_CENTROID_RADIUS_DP, 0f)
        assertEquals(48f, ParametricMath.DOUBLE_TAP_MAX_DISTANCE_DP, 0f)
        assertEquals(28f, ParametricMath.NODE_DOUBLE_TAP_EXCLUSION_DP, 0f)
    }

    @Test fun outputsAreFinite() {
        assertTrue(ParametricMath.hzAt(Float.NaN, plot).isFinite())
        assertTrue(ParametricMath.dbAt(Float.NaN, plot, win).isFinite())
        assertTrue(ParametricMath.qAfterPinch(1f, Float.NaN, 1f).isFinite())
    }
}
