package com.dylenq.eq.ui

/** One node's geometry in pixels for label collision handling. Label rect is centred on [x]; [below] flips it under the node. */
class LabelItem(
    val x: Float,
    val y: Float,
    /** Half the glyph extent including its separator ring (and the selection ring when selected). */
    val glyphHalf: Float,
    val labelW: Float,
    val labelH: Float,
    val below: Boolean,
    val gap: Float,
    /** Half extent the label is anchored to (as drawn); defaults to [glyphHalf]. */
    val anchorHalf: Float = glyphHalf,
)

/**
 * Decides which node number labels are drawn (UI-SPEC intent: the plot never becomes a pile of overlapping numbers; the
 * band list still names every band). The selected node's label always shows and is placed first. Every other label shows
 * only if its rectangle touches no other node's glyph and no label already placed (index order). Pure.
 */
object NodeLabelCollision {
    private class R(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun hits(o: R) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    private fun glyph(i: LabelItem) = R(i.x - i.glyphHalf, i.y - i.glyphHalf, i.x + i.glyphHalf, i.y + i.glyphHalf)

    private fun label(i: LabelItem, pad: Float): R {
        val top = if (i.below) i.y + i.anchorHalf + i.gap else i.y - i.anchorHalf - i.gap - i.labelH
        return R(i.x - i.labelW / 2f - pad, top - pad, i.x + i.labelW / 2f + pad, top + i.labelH + pad)
    }

    fun visible(items: List<LabelItem>, selected: Int?, pad: Float = 1f): BooleanArray {
        val out = BooleanArray(items.size)
        val placed = ArrayList<R>()
        val order = items.indices.sortedBy { if (it == selected) 0 else 1 }
        for (i in order) {
            val rect = label(items[i], pad)
            if (i == selected) {
                out[i] = true
                placed += rect
                continue
            }
            val blocked = items.indices.any { j -> j != i && glyph(items[j]).hits(rect) } || placed.any { it.hits(rect) }
            if (!blocked) {
                out[i] = true
                placed += rect
            }
        }
        return out
    }
}
