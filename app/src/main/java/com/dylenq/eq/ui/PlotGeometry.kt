package com.dylenq.eq.ui

import androidx.compose.ui.geometry.Rect

/**
 * The plot rectangle inside a canvas, in one pure function shared by [CurveCanvas] (drawing) and
 * the interaction layer (hit testing), so the two cannot drift. Insets are in dp.
 */
object PlotGeometry {
    const val INSET_LEFT_DP = 36f
    const val INSET_TOP_DP = 8f
    const val INSET_RIGHT_DP = 8f
    const val INSET_BOTTOM_DP = 20f

    /** The interaction layer extends this far past the plot right edge (UI-SPEC). */
    const val LAYER_EXTRA_RIGHT_DP = 16f

    /** ...and this far past the other edges, so a node on the edge keeps its full 48 dp target. */
    const val LAYER_EXTRA_OTHER_DP = 24f

    fun plotRect(widthPx: Float, heightPx: Float, density: Float): Rect = Rect(
        INSET_LEFT_DP * density,
        INSET_TOP_DP * density,
        widthPx - INSET_RIGHT_DP * density,
        heightPx - INSET_BOTTOM_DP * density,
    )

    fun layerBounds(widthPx: Float, heightPx: Float, density: Float): Rect {
        val p = plotRect(widthPx, heightPx, density)
        return Rect(
            p.left - LAYER_EXTRA_OTHER_DP * density,
            p.top - LAYER_EXTRA_OTHER_DP * density,
            p.right + LAYER_EXTRA_RIGHT_DP * density,
            p.bottom + LAYER_EXTRA_OTHER_DP * density,
        )
    }
}
