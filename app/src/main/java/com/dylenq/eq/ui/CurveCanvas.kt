package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.ln

private const val MIN_HZ = 20f
private const val MAX_HZ = 20_000f
private const val RANGE_DB = 12f

/** Draws the Actual curve over a 20 Hz to 20 kHz log axis and a +-12 dB window. */
@Composable
fun CurveCanvas(curve: () -> CurvePoints?, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val zero = MaterialTheme.colorScheme.outline
    Spacer(
        modifier
            .testTag("curve")
            .drawWithCache {
                val path = Path()
                val logSpan = ln(MAX_HZ / MIN_HZ)
                onDrawBehind {
                    val w = size.width
                    val h = size.height
                    val midY = h / 2f
                    drawLine(zero, Offset(0f, midY), Offset(w, midY), strokeWidth = 1.dp.toPx())
                    val c = curve() ?: return@onDrawBehind
                    path.reset()
                    for (i in c.freqsHz.indices) {
                        val x = ln(c.freqsHz[i] / MIN_HZ) / logSpan * w
                        val db = c.achievedDb[i].coerceIn(-RANGE_DB, RANGE_DB)
                        val y = midY - db / RANGE_DB * midY
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path, line,
                        style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            },
    )
}
