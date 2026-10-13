package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLabelCollisionTest {
    private fun item(x: Float, y: Float = 100f) = LabelItem(x, y, glyphHalf = 12f, labelW = 10f, labelH = 14f, below = false, gap = 6f)

    @Test fun farApartNodesAllShowTheirLabels() {
        val v = NodeLabelCollision.visible(listOf(item(0f), item(100f), item(200f)), selected = null)
        assertTrue(v.all { it })
    }

    @Test fun aLabelOverAnotherNodesGlyphIsHidden() {
        // Node 1 sits directly above node 0's label position (label rect is above the glyph, y 68..82).
        val a = item(100f, 100f)
        val b = item(100f, 75f)
        val v = NodeLabelCollision.visible(listOf(a, b), selected = null)
        assertFalse("a's label would sit on b's glyph", v[0])
    }

    @Test fun overlappingLabelsKeepTheFirstByIndex() {
        val v = NodeLabelCollision.visible(listOf(item(100f, 100f), item(104f, 100f)), selected = null)
        // both labels at the same height and overlapping glyphs of each other: neither can show over the other's glyph
        assertFalse(v[0] && v[1])
    }

    @Test fun twoLabelsThatOnlyCollideWithEachOtherKeepTheLowerIndex() {
        // Glyphs far enough apart in y that neither label touches a glyph, but labels overlap each other.
        val a = LabelItem(100f, 100f, 4f, 20f, 14f, below = false, gap = 2f)
        val b = LabelItem(110f, 100f, 4f, 20f, 14f, below = false, gap = 2f)
        val v = NodeLabelCollision.visible(listOf(a, b), selected = null)
        assertTrue(v[0]); assertFalse(v[1])
    }

    @Test fun selectedLabelAlwaysShowsAndWinsOverlaps() {
        val a = LabelItem(100f, 100f, 4f, 20f, 14f, below = false, gap = 2f)
        val b = LabelItem(110f, 100f, 4f, 20f, 14f, below = false, gap = 2f)
        val v = NodeLabelCollision.visible(listOf(a, b), selected = 1)
        assertTrue(v[1]); assertFalse(v[0])
        // even when its own label sits on another glyph
        val c = item(100f, 100f); val d = item(100f, 75f)
        assertTrue(NodeLabelCollision.visible(listOf(c, d), selected = 0)[0])
    }

    @Test fun emptyAndSingle() {
        assertEquals(0, NodeLabelCollision.visible(emptyList(), null).size)
        assertTrue(NodeLabelCollision.visible(listOf(item(5f)), null)[0])
    }
}
