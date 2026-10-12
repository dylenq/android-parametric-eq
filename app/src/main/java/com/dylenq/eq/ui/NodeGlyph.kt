package com.dylenq.eq.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.dylenq.eq.core.FilterKind

enum class NodeShape { CIRCLE, DIAMOND }

enum class NodeTail { NONE, LEFT, RIGHT }

/** The visual state of one node (UI-SPEC "Node appearance", by state). */
data class NodeVisualState(
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val dragging: Boolean = false,
)

/**
 * Pure description of a node glyph (no canvas): shape and tail come from the filter kind, the rest from
 * the state. Sizes are dp. [diameterDp] is the extent across the glyph (a diamond is 20 across at rest);
 * the `rest*` values are what a dragging glyph grows from (UI-SPEC: grows over 100 ms).
 */
data class NodeGlyphSpec(
    val shape: NodeShape,
    val tail: NodeTail,
    val filled: Boolean,
    val halo: Boolean,
    val haloDp: Float,
    val haloAlpha: Float,
    val restHaloDp: Float,
    val restHaloAlpha: Float,
    val ring: Boolean,
    val diameterDp: Float,
    val restDiameterDp: Float,
    val tailLengthDp: Float = NodeGlyphs.TAIL_LENGTH_DP,
    val tailThicknessDp: Float = NodeGlyphs.TAIL_THICKNESS_DP,
)

object NodeGlyphs {
    const val TAIL_LENGTH_DP = 14f
    const val TAIL_THICKNESS_DP = 3f
    const val CIRCLE_DP = 16f
    const val CIRCLE_DRAG_DP = 20f
    const val DIAMOND_DP = 20f
    const val DIAMOND_DRAG_DP = 25f
    const val SELECTED_HALO_DP = 32f
    const val SELECTED_HALO_ALPHA = 0.24f
    const val DRAG_HALO_DP = 40f
    const val DRAG_HALO_ALPHA = 0.32f
    const val OUTER_RING_DP = 2f
    const val SELECTED_RING_GAP_DP = 4f
    const val SELECTED_RING_DP = 2f
    const val DISABLED_STROKE_DP = 2f
    const val GUIDE_ALPHA = 0.4f

    /** Number labels are not drawn above this font scale (the band list carries the numbers). */
    const val LABEL_MAX_FONT_SCALE = 1.3f
    const val LABEL_GAP_DP = 6f
    const val LABEL_FLIP_WITHIN_DP = 20f

    fun shape(kind: FilterKind): NodeShape =
        if (kind == FilterKind.HIGH_PASS || kind == FilterKind.LOW_PASS) NodeShape.DIAMOND else NodeShape.CIRCLE

    fun tail(kind: FilterKind): NodeTail = when (kind) {
        FilterKind.PEAK -> NodeTail.NONE
        FilterKind.LOW_SHELF, FilterKind.HIGH_PASS -> NodeTail.LEFT
        FilterKind.HIGH_SHELF, FilterKind.LOW_PASS -> NodeTail.RIGHT
    }

    fun spec(kind: FilterKind, state: NodeVisualState = NodeVisualState()): NodeGlyphSpec {
        val shape = shape(kind)
        val rest = if (shape == NodeShape.DIAMOND) DIAMOND_DP else CIRCLE_DP
        val drag = if (shape == NodeShape.DIAMOND) DIAMOND_DRAG_DP else CIRCLE_DRAG_DP
        val restHalo = if (state.selected) SELECTED_HALO_DP else 0f
        val restHaloAlpha = if (state.selected) SELECTED_HALO_ALPHA else 0f
        return NodeGlyphSpec(
            shape = shape,
            tail = tail(kind),
            filled = state.enabled,
            halo = state.selected || state.dragging,
            haloDp = if (state.dragging) DRAG_HALO_DP else restHalo,
            haloAlpha = if (state.dragging) DRAG_HALO_ALPHA else restHaloAlpha,
            restHaloDp = restHalo,
            restHaloAlpha = restHaloAlpha,
            ring = state.selected,
            diameterDp = if (state.dragging) drag else rest,
            restDiameterDp = rest,
        )
    }

    /** Whether the band-number labels are drawn at this system font scale. */
    fun labelVisible(fontScale: Float): Boolean = fontScale <= LABEL_MAX_FONT_SCALE

    /** True when the label flips below the node: the node centre is within 20 dp of the plot top. */
    fun labelBelow(nodeYDp: Float, plotTopDp: Float): Boolean = nodeYDp - plotTopDp < LABEL_FLIP_WITHIN_DP
}

/** Theme colours a glyph is drawn with; passed in so the function owns no colour constants. */
class NodeGlyphColors(
    val primary: Color,
    val outline: Color,
    /** The plot colour: separates the glyph from the curve and fills hollow glyphs. */
    val plot: Color,
)

/**
 * Draws one node glyph centred at [center]. [dpPx] is pixels per dp (so callers other than the plot, such
 * as the band-list marker, can scale it) and [tailLengthDp] lets the marker use an 8 dp tail.
 * [growth] 0..1 interpolates a dragging glyph from its rest size to its dragging size.
 */
fun DrawScope.drawNodeGlyph(
    center: Offset,
    spec: NodeGlyphSpec,
    colors: NodeGlyphColors,
    dpPx: Float = density,
    tailLengthDp: Float = spec.tailLengthDp,
    growth: Float = 1f,
) {
    fun lerp(a: Float, b: Float) = a + (b - a) * growth.coerceIn(0f, 1f)
    val accent = if (spec.filled) colors.primary else colors.outline
    val across = lerp(spec.restDiameterDp, spec.diameterDp) * dpPx
    val half = across / 2f
    val haloColor = if (spec.filled) colors.primary else colors.outline

    val haloDp = lerp(spec.restHaloDp, spec.haloDp)
    val haloAlpha = lerp(spec.restHaloAlpha, spec.haloAlpha)
    if (spec.halo && haloDp > 0f && haloAlpha > 0f) {
        drawCircle(haloColor.copy(alpha = haloAlpha), haloDp * dpPx / 2f, center)
    }
    if (spec.ring) {
        drawCircle(
            haloColor,
            half + (NodeGlyphs.SELECTED_RING_GAP_DP + NodeGlyphs.SELECTED_RING_DP / 2f) * dpPx,
            center,
            style = Stroke(NodeGlyphs.SELECTED_RING_DP * dpPx),
        )
    }

    // The tail first, so the glyph and its separator ring sit on top and the tail "touches" the edge.
    if (spec.tail != NodeTail.NONE) {
        val len = tailLengthDp * dpPx
        val thick = spec.tailThicknessDp * dpPx
        val left = if (spec.tail == NodeTail.LEFT) center.x - half - len else center.x + half
        drawRoundRect(
            accent,
            Offset(left, center.y - thick / 2f),
            Size(len, thick),
            CornerRadius(thick / 2f),
        )
    }

    val outer = NodeGlyphs.OUTER_RING_DP * dpPx
    when (spec.shape) {
        NodeShape.CIRCLE -> {
            drawCircle(colors.plot, half + outer, center)
            if (spec.filled) {
                drawCircle(colors.primary, half, center)
            } else {
                drawCircle(colors.plot, half, center)
                drawCircle(colors.outline, half - NodeGlyphs.DISABLED_STROKE_DP * dpPx / 2f, center, style = Stroke(NodeGlyphs.DISABLED_STROKE_DP * dpPx))
            }
        }
        NodeShape.DIAMOND -> {
            drawPath(diamond(center, half + outer), colors.plot)
            if (spec.filled) {
                drawPath(diamond(center, half), colors.primary)
            } else {
                drawPath(diamond(center, half), colors.plot)
                drawPath(diamond(center, half - NodeGlyphs.DISABLED_STROKE_DP * dpPx / 2f), colors.outline, style = Stroke(NodeGlyphs.DISABLED_STROKE_DP * dpPx))
            }
        }
    }
}

private fun diamond(c: Offset, r: Float): Path = Path().apply {
    moveTo(c.x, c.y - r)
    lineTo(c.x + r, c.y)
    lineTo(c.x, c.y + r)
    lineTo(c.x - r, c.y)
    close()
}
