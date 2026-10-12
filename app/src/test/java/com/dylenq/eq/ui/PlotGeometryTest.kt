package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlotGeometryTest {
    @Test fun plotRectAtDensityOne() {
        val r = PlotGeometry.plotRect(400f, 300f, 1f)
        assertEquals(36f, r.left, 1e-4f)
        assertEquals(8f, r.top, 1e-4f)
        assertEquals(392f, r.right, 1e-4f)
        assertEquals(280f, r.bottom, 1e-4f)
    }

    @Test fun plotRectScalesWithDensity() {
        val d = 2.75f
        val r = PlotGeometry.plotRect(1100f, 800f, d)
        assertEquals(36f * d, r.left, 1e-3f)
        assertEquals(8f * d, r.top, 1e-3f)
        assertEquals(1100f - 8f * d, r.right, 1e-3f)
        assertEquals(800f - 20f * d, r.bottom, 1e-3f)
    }

    @Test fun layerBoundsExtendPastThePlot() {
        val d = 2f
        val plot = PlotGeometry.plotRect(800f, 600f, d)
        val layer = PlotGeometry.layerBounds(800f, 600f, d)
        assertEquals(plot.left - 24f * d, layer.left, 1e-3f)
        assertEquals(plot.top - 24f * d, layer.top, 1e-3f)
        assertEquals(plot.right + 16f * d, layer.right, 1e-3f)
        assertEquals(plot.bottom + 24f * d, layer.bottom, 1e-3f)
    }
}
