package com.dylenq.eq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

/** Landscape, or at least this wide: two panes (UI-SPEC "Window classes"). */
const val TWO_PANE_MIN_WIDTH_DP = 840

/** UI-SPEC "Horizontal screen margin": 12 dp below 360 dp, 24 dp from 600 dp, else 16 dp. */
fun screenMarginDp(widthDp: Int): Int = when {
    widthDp < 360 -> 12
    widthDp >= 600 -> 24
    else -> 16
}

/** True when the mode row stacks: a narrow window (320-359 dp) or font scale 1.3 and up. */
fun modeRowStacked(screenWidthDp: Int, fontScale: Float): Boolean = screenWidthDp < 360 || fontScale >= 1.3f

/** UI-SPEC: `clamp(30% of window height, 168, 260)` dp. */
fun defaultPlotHeight(screenHeightDp: Int): Float = (screenHeightDp * 0.30f).coerceIn(168f, 260f)

/** The window facts the layout depends on; pure so the decisions are unit tested. */
@Immutable
data class WindowSpec(val widthDp: Int, val heightDp: Int, val fontScale: Float = 1f) {
    /** Landscape (wider than tall) or >= 840 dp wide. */
    val twoPane: Boolean get() = widthDp > heightDp || widthDp >= TWO_PANE_MIN_WIDTH_DP
    val largeFont: Boolean get() = fontScale >= 1.3f
    val marginDp: Int get() = screenMarginDp(widthDp)
    val stackedModeRow: Boolean get() = modeRowStacked(widthDp, fontScale)

    /** Single column content is capped at 640 dp and centred (600-839 dp portrait). */
    val maxContentWidthDp: Int get() = if (twoPane) Int.MAX_VALUE else 640
    val topBarHeightDp: Int get() = if (twoPane) 56 else 64

    companion object {
        @Composable
        fun current(): WindowSpec {
            val c = LocalConfiguration.current
            return WindowSpec(c.screenWidthDp, c.screenHeightDp, LocalDensity.current.fontScale)
        }
    }
}

/** Heights (dp) the portrait column decided on. */
data class ColumnPlan(val plotDp: Float, val contentDp: Float, val totalDp: Float, val scrolls: Boolean)

const val PLOT_MIN_DP = 168f
const val CONTENT_MIN_DP = 216f
const val COLUMN_GAP_DP = 12f

/**
 * Splits the column height: the plot keeps [desiredPlotDp] when the other regions and the slider panel's
 * minimum still fit [availDp]; otherwise it shrinks down to 168 dp, and below that the column scrolls.
 * [fixedDp] is the sum of the mode row, preamp card and the curve card chrome (header, footer, padding).
 */
fun planColumn(availDp: Float, fixedDp: Float, desiredPlotDp: Float, gapDp: Float = COLUMN_GAP_DP): ColumnPlan {
    val room = availDp - fixedDp - 3 * gapDp
    val plot = desiredPlotDp.coerceAtMost(room - CONTENT_MIN_DP).coerceAtLeast(PLOT_MIN_DP)
    val content = maxOf(CONTENT_MIN_DP, room - plot)
    val total = fixedDp + 3 * gapDp + plot + content
    return ColumnPlan(plot, content, total, total > availDp + 0.5f)
}
