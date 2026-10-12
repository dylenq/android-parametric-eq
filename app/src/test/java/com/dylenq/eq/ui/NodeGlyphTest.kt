package com.dylenq.eq.ui

import com.dylenq.eq.core.FilterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeGlyphTest {
    private fun spec(k: FilterKind, selected: Boolean = false, enabled: Boolean = true, dragging: Boolean = false) =
        NodeGlyphs.spec(k, NodeVisualState(selected, enabled, dragging))

    @Test fun kindsMapToShapeAndTailPerTheSpec() {
        val expected = mapOf(
            FilterKind.PEAK to (NodeShape.CIRCLE to NodeTail.NONE),
            FilterKind.LOW_SHELF to (NodeShape.CIRCLE to NodeTail.LEFT),
            FilterKind.HIGH_SHELF to (NodeShape.CIRCLE to NodeTail.RIGHT),
            FilterKind.HIGH_PASS to (NodeShape.DIAMOND to NodeTail.LEFT),
            FilterKind.LOW_PASS to (NodeShape.DIAMOND to NodeTail.RIGHT),
        )
        for ((k, e) in expected) {
            val s = spec(k)
            assertEquals("$k shape", e.first, s.shape)
            assertEquals("$k tail", e.second, s.tail)
        }
    }

    @Test fun normalGlyphIsFilledWithoutHaloOrRing() {
        for (k in FilterKind.entries) {
            val s = spec(k)
            assertTrue(s.filled)
            assertFalse(s.halo)
            assertFalse(s.ring)
            assertEquals(if (s.shape == NodeShape.DIAMOND) 20f else 16f, s.diameterDp, 0f)
        }
    }

    @Test fun selectedAddsA32DpHaloAtTwentyFourPercentAndARing() {
        val s = spec(FilterKind.PEAK, selected = true)
        assertTrue(s.halo)
        assertTrue(s.ring)
        assertEquals(32f, s.haloDp, 0f)
        assertEquals(0.24f, s.haloAlpha, 0f)
        assertEquals(16f, s.diameterDp, 0f)
    }

    @Test fun disabledIsHollow() {
        for (k in FilterKind.entries) assertFalse(spec(k, enabled = false).filled)
    }

    @Test fun selectedDisabledKeepsHaloAndRingButStaysHollow() {
        val s = spec(FilterKind.LOW_SHELF, selected = true, enabled = false)
        assertFalse(s.filled)
        assertTrue(s.halo)
        assertTrue(s.ring)
    }

    @Test fun draggingGrowsTo20DpWith40DpHaloAtThirtyTwoPercent() {
        val s = spec(FilterKind.HIGH_SHELF, dragging = true)
        assertEquals(20f, s.diameterDp, 0f)
        assertEquals(16f, s.restDiameterDp, 0f)
        assertEquals(40f, s.haloDp, 0f)
        assertEquals(0.32f, s.haloAlpha, 0f)
        assertTrue(s.halo)
    }

    @Test fun draggingSelectedGrowsFromTheSelectedHalo() {
        val s = spec(FilterKind.PEAK, selected = true, dragging = true)
        assertEquals(40f, s.haloDp, 0f)
        assertEquals(32f, s.restHaloDp, 0f)
        assertEquals(0.24f, s.restHaloAlpha, 0f)
    }

    @Test fun draggingPassKindGrowsTheDiamond() {
        val s = spec(FilterKind.LOW_PASS, dragging = true)
        assertTrue(s.diameterDp > s.restDiameterDp)
        assertEquals(20f, s.restDiameterDp, 0f)
    }

    @Test fun tailIs14By3Dp() {
        val s = spec(FilterKind.LOW_SHELF)
        assertEquals(14f, s.tailLengthDp, 0f)
        assertEquals(3f, s.tailThicknessDp, 0f)
    }

    @Test fun numberLabelsHideAboveFontScale1Point3() {
        assertTrue(NodeGlyphs.labelVisible(1.0f))
        assertTrue(NodeGlyphs.labelVisible(1.3f))
        assertFalse(NodeGlyphs.labelVisible(1.31f))
        assertFalse(NodeGlyphs.labelVisible(2.0f))
    }

    @Test fun numberLabelFlipsBelowWithin20DpOfThePlotTop() {
        assertTrue(NodeGlyphs.labelBelow(nodeYDp = 8f, plotTopDp = 8f))
        assertTrue(NodeGlyphs.labelBelow(nodeYDp = 27.9f, plotTopDp = 8f))
        assertFalse(NodeGlyphs.labelBelow(nodeYDp = 28f, plotTopDp = 8f))
        assertFalse(NodeGlyphs.labelBelow(nodeYDp = 100f, plotTopDp = 8f))
    }
}
