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
import com.dylenq.eq.core.EqMode
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

/** Header height (UI-SPEC: 40 dp); at large font the legend and the chip stack in two 40 dp rows. */
internal fun curveHeaderHeight(fontScale: Float) = if (fontScale >= 1.3f) 80.dp else 40.dp

/**
 * Footer height (UI-SPEC: 24 dp), reserved always so the card does not jump when the caption appears.
 * The caption wraps to two lines below about 400 dp of window width and at large font, so it is taller there.
 */
internal fun curveFooterHeight(fontScale: Float, widthDp: Int) =
    if (fontScale >= 1.3f) 48.dp else if (widthDp < 400) 40.dp else 24.dp

/** Everything in the card except the plot, at this font scale and window width (dp). */
internal fun curveChromeDp(fontScale: Float, widthDp: Int): Float =
    CurveCardTopPad.value + curveHeaderHeight(fontScale).value + curveFooterHeight(fontScale, widthDp).value

/** The holder callbacks the Parametric interaction layer drives. */
@Immutable
class ParametricActions(
    val onSelect: (Int) -> Unit,
    val onDragStart: (Int) -> Unit,
    val onNodeChange: (index: Int, freqHz: Float, gainDb: Float?) -> Unit,
    val onDragEnd: () -> Unit,
    val onAddBand: (freqHz: Float, gainDb: Float) -> Unit = { _, _ -> },
    val onQChange: (index: Int, q: Float) -> Unit = { _, _ -> },
    val onLongPress: (Int) -> Unit = {},
    val onPinchCompleted: () -> Unit = {},
    val onDeselect: () -> Unit = {},
    val onSetKind: (index: Int, kind: com.dylenq.eq.core.FilterKind) -> Unit = { _, _ -> },
    val onSetEnabled: (index: Int, on: Boolean) -> Unit = { _, _ -> },
    val onDeleteBand: (index: Int) -> Unit = {},
    val onNudgeFrequency: (index: Int, steps: Int) -> Unit = { _, _ -> },
    val onNudgeGain: (index: Int, deltaDb: Float) -> Unit = { _, _ -> },
    val onScaleQ: (index: Int, factor: Float) -> Unit = { _, _ -> },
    val onAddBandFromList: () -> Unit = {},
)

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
    /** Footer height; the screen passes the value its chrome estimate used. */
    footerHeight: androidx.compose.ui.unit.Dp? = null,
    /** Node editing callbacks; the interaction layer is hosted only in Parametric mode when set. */
    parametric: ParametricActions? = null,
    /** IME-open form: no header, footer, caption or frequency labels; the caller sizes the card. */
    compact: Boolean = false,
    /** Landscape Parametric: a shorter header (UI-SPEC 32 dp), ignored at large font. */
    headerHeightOverride: androidx.compose.ui.unit.Dp? = null,
    /** Landscape Parametric: no reserved footer while there is no caption. */
    dropEmptyFooter: Boolean = false,
) {
    val view by remember(state) { derivedStateOf { CurveView.from(state()) } }
    val reduced = rememberReducedMotion()

    // Y window: hysteresis decided here, animated below. Frozen while a slider is dragged.
    var window by remember { mutableIntStateOf(12) }
    val anim = remember { Animatable(12f) }
    // Parametric uses a FIXED window (L-01): no hysteresis, no dwell, no mid-drag change.
    val isParametric by remember(state) { derivedStateOf { state().mode == EqMode.PARAMETRIC } }
    LaunchedEffect(state) {
        snapshotFlow { val s = state(); Triple(plottedMagnitude(s), s.draggingBand != null, s.mode == EqMode.PARAMETRIC) }
            .collect { (m, dragging, para) -> if (!para) window = AxisScale.yWindow(window, m, dragging) }
    }
    LaunchedEffect(window, isParametric, reduced) {
        val target = if (isParametric) AxisScale.PARAMETRIC_Y_HALF_SPAN_DB else window.toFloat()
        if (reduced) anim.snapTo(target) else anim.animateTo(target, tween(300, easing = EmphasizedEasing))
    }

    var showDialog by rememberSaveable { mutableStateOf(false) }
    val plotHeight = plotHeightOverride ?: defaultPlotHeight(LocalConfiguration.current.screenHeightDp).dp
    val fontScale = LocalDensity.current.fontScale
    val footer = footerHeight ?: curveFooterHeight(fontScale, LocalConfiguration.current.screenWidthDp)
    val headerHeight = if (fontScale < 1.3f && headerHeightOverride != null) headerHeightOverride else curveHeaderHeight(fontScale)
    val footerShown = if (compact) 0.dp else if (dropEmptyFooter && view.caption == null) 0.dp else footer

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(Modifier.then(if (fillHeight) Modifier.fillMaxSize() else Modifier)) {
        Column(Modifier.then(if (fillHeight) Modifier.fillMaxSize() else Modifier).padding(start = 8.dp, end = 8.dp, top = CurveCardTopPad)) {
            // Header: legend and headroom chip (UI-SPEC: 40 dp).
            if (compact) {
                // IME open: the plot only.
            } else if (fontScale >= 1.3f) {
                Column(Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 8.dp)) {
                    if (!view.bypass && !view.curveError) {
                        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) { CurveLegend(showTarget = view.targetVisible) }
                        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterEnd) {
                            HeadroomChip(view.headroomText, view.headroomSpeech, view.over, onApplySuggested)
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(headerHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!view.bypass && !view.curveError) {
                        CurveLegend(showTarget = view.targetVisible)
                        Spacer(Modifier.weight(1f))
                        HeadroomChip(view.headroomText, view.headroomSpeech, view.over, onApplySuggested)
                    }
                }
            }

            Box(Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier.height(plotHeight))) {
                CurveCanvas(
                    state = state,
                    yWindow = { anim.value },
                    labelWindow = if (isParametric) AxisScale.PARAMETRIC_LABEL_WINDOW else window,
                    showFrequencyLabels = !compact,
                    modifier = Modifier
                        .matchParentSize()
                        .testTag("curve")
                        .semantics {
                            contentDescription = "Frequency response, 20 hertz to 20 kilohertz"
                            stateDescription = view.stateDescription
                        },
                )
                if (isParametric && parametric != null) {
                    CurveInteractionLayer(
                        bands = { state().bands },
                        selected = { state().selectedBand },
                        dragging = { state().draggingBand },
                        yWindow = { anim.value },
                        onSelect = parametric.onSelect,
                        onDragStart = parametric.onDragStart,
                        onNodeChange = parametric.onNodeChange,
                        onDragEnd = parametric.onDragEnd,
                        onAddBand = parametric.onAddBand,
                        onQChange = parametric.onQChange,
                        onLongPress = parametric.onLongPress,
                        onPinchCompleted = parametric.onPinchCompleted,
                        onDeselect = parametric.onDeselect,
                        onSetKind = parametric.onSetKind,
                        onSetEnabled = parametric.onSetEnabled,
                        onDeleteBand = parametric.onDeleteBand,
                        onNudgeFrequency = parametric.onNudgeFrequency,
                        onNudgeGain = parametric.onNudgeGain,
                        onScaleQ = parametric.onScaleQ,
                        onAddBandFromList = parametric.onAddBandFromList,
                        modifier = Modifier.matchParentSize(),
                    )
                    NodeReadoutChip(
                        bands = { state().bands },
                        dragging = { state().draggingBand },
                        yWindow = { anim.value },
                        modifier = Modifier.matchParentSize(),
                    )
                }
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
            Spacer(Modifier.fillMaxWidth().height(footerShown))
        }
        // The caption is drawn in the footer but its 48 dp touch target reaches up over the plot's
        // axis-label strip (the plot has no gestures in this phase).
        val caption = view.caption
        if (caption != null && !compact) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "About target and actual curves") { showDialog = true }
                    .padding(start = 16.dp, end = 16.dp, top = 48.dp - footer)
                    .testTag("caption"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    caption,
                    style = MaterialTheme.typography.labelSmall.capScale(),
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
    val style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp).capScale()
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
            Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp).capScale(), maxLines = 1, overflow = TextOverflow.Clip)
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
