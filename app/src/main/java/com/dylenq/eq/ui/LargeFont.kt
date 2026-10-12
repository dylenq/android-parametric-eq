package com.dylenq.eq.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle

/** Dense chart and slider labels grow with the system font only up to this scale (UI-SPEC deviation, see the 04-06 summary). */
const val DENSE_LABEL_MAX_SCALE = 1.3f

/**
 * Caps a style's effective size at [DENSE_LABEL_MAX_SCALE] times its nominal size. Used for the labels that
 * sit in fixed-pitch places (frequency and gain labels, axis labels, legend, chips, caption): at 200% they
 * would overlap their neighbours, while the same information is available at full size in the readout bar,
 * the semantics tree and the other texts, which do scale to 200%.
 */
@Composable
fun TextStyle.capScale(max: Float = DENSE_LABEL_MAX_SCALE): TextStyle {
    val fs = LocalDensity.current.fontScale
    if (fs <= max) return this
    val k = max / fs
    return copy(fontSize = fontSize * k, lineHeight = lineHeight * k)
}

/** Label row height in the slider panel: 20 dp, 28 dp at font scale >= 1.3. */
fun sliderLabelRowDp(fontScale: Float) = if (fontScale >= 1.3f) 28f else 20f

/**
 * Minimum height of the graphic slider panel (UI-SPEC: 232 -> measured 216 dp at 100%): card padding,
 * readout bar, label rows (two at large font: the gain labels join the frequency labels), the track
 * minimum and the label gap. [compact] is the landscape form (121 dp tracks, no vertical padding).
 */
fun graphicPanelMinDp(fontScale: Float, compact: Boolean): Float {
    val fs = fontScale.coerceIn(1f, 2f)
    val readout = maxOf(24f, 20f * fs + 4f)
    val rows = if (fontScale >= 1.3f) 2 else 1
    val pad = if (compact) 0f else 8f
    val track = if (compact) 121f else 160f
    return pad + readout + track + rows * sliderLabelRowDp(fontScale) + 4f
}
