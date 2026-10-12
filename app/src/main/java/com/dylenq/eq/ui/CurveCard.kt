package com.dylenq.eq.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dylenq.eq.R

/** Material emphasized easing, used for the Y-window rescale (UI-SPEC "Motion"). */
private val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal val CurveCardTopPad = 4.dp
internal val CurveHeaderHeight = 40.dp

/** Footer height (UI-SPEC: 24 dp): one labelSmall line; two lines when the font is large. */
internal fun curveFooterHeight(fontScale: Float) = if (fontScale >= 1.3f) 40.dp else 24.dp

/** Everything in the card except the plot, at this font scale (dp). */
internal fun curveChromeDp(fontScale: Float): Float =
    CurveCardTopPad.value + CurveHeaderHeight.value + curveFooterHeight(fontScale).value

/** What the card's chrome (legend, chip, caption, semantics) needs; equal values do not recompose. */
@Immutable
private data class CurveView(
    val bypass: Boolean,
    val curveError: Boolean,
    val targetVisible: Boolean,
    val caption: String?,
    val headroomText: String,
    val headroomSpeech: String,
    val over: Boolean,
    val stateDescription: String,
) {
    companion object {
        fun from(s: EqUiState): CurveView {
            val c = s.curve
            val diff = if (c != null) CurveStats.maxDiff(c.freqsHz, c.idealDb, c.achievedDb).db else 0f
            val over = CurveStats.isOver(s.headroomAfterPreampDb)
            val h = s.headroomAfterPreampDb
            val speech = if (over) {
                "Over by ${GainSpeech.magnitude(-h)}"
            } else {
                "Headroom ${GainSpeech.magnitude(h.coerceAtLeast(0f))}"
            }
            return CurveView(
                bypass = s.bypass,
                curveError = s.curveError,
                targetVisible = c != null && !s.curveError && CurveStats.targetVisible(diff),
                caption = if (c == null || s.bypass || s.curveError) null else CurveStats.caption(c.freqsHz, c.idealDb, c.achievedDb),
                headroomText = CurveStats.headroomText(h),
                headroomSpeech = speech,
                over = over,
                stateDescription = when {
                    s.curveError -> "Curve unavailable"
                    s.bypass -> "Equalizer is off. Curve not applied."
                    c == null -> "No curve yet"
                    else -> CurveStats.stateDescription(c.freqsHz, c.idealDb, c.achievedDb, bypass = false)
                },
            )
        }
    }
}

/**
 * The curve card (UI-SPEC "Curve View"): legend and headroom chip on top, the plot, then the
 * Target-vs-Actual caption. [state] is read through a lambda so slider drags only invalidate the
 * draw phase; the chrome recomposes only when its derived [CurveView] changes.
 */
@Composable
fun CurveCard(
    state: () -> EqUiState,
    onApplySuggested: () -> Unit,
    modifier: Modifier = Modifier,
    /** Plot height; null uses 30% of the window height clamped to 168-260 dp (UI-SPEC). */
    plotHeightOverride: androidx.compose.ui.unit.Dp? = null,
    /** The card has a bounded height and the plot takes what the header and footer leave. */
    fillHeight: Boolean = false,
) {
    val view by remember(state) { derivedStateOf { CurveView.from(state()) } }
    val reduced = rememberReducedMotion()

    // Y window: hysteresis decided here, animated below. Frozen while a slider is dragged.
    var window by remember { mutableIntStateOf(12) }
    val anim = remember { Animatable(12f) }
    LaunchedEffect(state) {
        snapshotFlow { val s = state(); plottedMagnitude(s) to (s.draggingBand != null) }
            .collect { (m, dragging) -> window = AxisScale.yWindow(window, m, dragging) }
    }
    LaunchedEffect(window, reduced) {
        if (reduced) anim.snapTo(window.toFloat()) else anim.animateTo(window.toFloat(), tween(300, easing = EmphasizedEasing))
    }

    var showDialog by rememberSaveable { mutableStateOf(false) }
    val plotHeight = plotHeightOverride ?: defaultPlotHeight(LocalConfiguration.current.screenHeightDp).dp
    val footerHeight = curveFooterHeight(LocalDensity.current.fontScale)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.then(if (fillHeight) Modifier.fillMaxSize() else Modifier)) {
        Column(Modifier.then(if (fillHeight) Modifier.fillMaxSize() else Modifier).padding(start = 8.dp, end = 8.dp, top = CurveCardTopPad)) {
            // Header: legend and headroom chip (UI-SPEC: 40 dp).
            Row(Modifier.fillMaxWidth().height(CurveHeaderHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!view.bypass && !view.curveError) {
                    CurveLegend(showTarget = view.targetVisible)
                    Spacer(Modifier.weight(1f))
                    HeadroomChip(view.headroomText, view.headroomSpeech, view.over, onApplySuggested)
                }
            }

            Box(Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier.height(plotHeight))) {
                CurveCanvas(
                    state = state,
                    yWindow = { anim.value },
                    labelWindow = window,
                    modifier = Modifier
                        .matchParentSize()
                        .testTag("curve")
                        .semantics {
                            contentDescription = "Frequency response, 20 hertz to 20 kilohertz"
                            stateDescription = view.stateDescription
                        },
                )
                if (view.bypass) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = PlotInsetLeft + 8.dp, top = PlotInsetTop + 8.dp)
                            .testTag("bypassed_chip"),
                    ) {
                        Text("Bypassed", style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                if (view.curveError) {
                    Box(
                        Modifier.matchParentSize().padding(PlotInsetLeft, PlotInsetTop, PlotInsetRight, PlotInsetBottom),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Curve unavailable", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Footer: reserved at one caption line, always, so the card never jumps when the caption appears.
            Spacer(Modifier.fillMaxWidth().height(footerHeight))
        }
        // The caption is drawn in the footer but its 48 dp touch target reaches up over the plot's
        // axis-label strip (the plot has no gestures in this phase).
        val caption = view.caption
        if (caption != null) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "About target and actual curves") { showDialog = true }
                    .padding(start = 16.dp, end = 16.dp, top = 48.dp - footerHeight)
                    .testTag("caption"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = "About target and actual curves",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Target and actual curves") },
            text = {
                Text("Target is the curve you set. Actual is what Android's equalizer can deliver. They differ most for narrow changes at low frequencies.")
            },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("Got it") } },
        )
    }
}

@Composable
private fun CurveLegend(showTarget: Boolean) {
    val cs = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTarget) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.width(16.dp).height(8.dp)) {
                    drawLine(cs.onSurfaceVariant, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                }
                Text("Target", style = style, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(Modifier.width(16.dp).height(8.dp)) {
                drawLine(cs.primary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5.dp.toPx(), StrokeCap.Round)
            }
            Text("Actual", style = style, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
}

@Composable
private fun HeadroomChip(text: String, speech: String, over: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val container = if (over) cs.errorContainer else cs.surfaceContainerHigh
    val content = if (over) cs.onErrorContainer else cs.onSurfaceVariant
    val inner: @Composable () -> Unit = {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (over) {
                Icon(painterResource(R.drawable.ic_warning), contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Clip)
        }
    }
    if (over) {
        // Tapping applies the suggested preamp. 48 dp touch height comes from the header row.
        Surface(
            onClick = onClick,
            color = container,
            contentColor = content,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .testTag("headroom_chip")
                .semantics { contentDescription = speech; liveRegion = LiveRegionMode.Polite },
            content = inner,
        )
    } else {
        Surface(
            color = container,
            contentColor = content,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .testTag("headroom_chip")
                .semantics { contentDescription = speech },
            content = inner,
        )
    }
}
