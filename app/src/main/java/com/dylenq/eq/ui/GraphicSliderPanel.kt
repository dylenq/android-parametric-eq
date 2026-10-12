package com.dylenq.eq.ui

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val LabelGap = 4.dp
private val ScaleWidth = 32.dp
private val ScrollEndPadding = 8.dp
private val FadeWidth = 24.dp
private val MinTrack = 160.dp
private val MinTrackCompact = 121.dp
private val ThumbInsetDp = 14.dp

private const val HINT = "Drag a slider. Double-tap to reset it."
private const val HINT_SHORT = "Drag a slider"

/** The per-slider gain label; without the unit at large font, where the column is only 48 dp wide. */
internal fun gainLabel(db: Float, fontScale: Float): String =
    GainFormat.format(db).let { if (fontScale >= 1.3f) it.removeSuffix(" dB") else it }
private val Tnum = TextStyle(fontFeatureSettings = "tnum")

/**
 * UI-SPEC "Graphic Mode: Sliders": readout bar, pinned dB scale and a horizontally scrolling row of
 * [VerticalGainSlider]s. [state] is read lazily so a drag redraws sliders without recomposing the panel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GraphicSliderPanel(
    state: () -> EqUiState,
    onDragStart: (Int) -> Unit,
    onChange: (Int, Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Landscape form: tracks may shrink to 120 dp and the card has no vertical padding (168 dp minimum). */
    compact: Boolean = false,
    /** Tight portrait window (360 x 800): tracks may shrink to 130 dp. */
    tight: Boolean = false,
    /** Horizontal scroll of the slider row; hoisted by the screen so it survives Graphic -> Parametric -> Graphic. */
    scroll: ScrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) },
) {
    val minTrack = if (compact) MinTrackCompact else if (tight) TIGHT_TRACK_DP.dp else MinTrack
    val large = LocalDensity.current.fontScale >= 1.3f
    val labelRow = sliderLabelRowDp(LocalDensity.current.fontScale).dp
    val labelRows = if (large) 2 else 1
    val bandCount by remember { derivedStateOf { state().graphicBandCount } }
    val bypass by remember { derivedStateOf { state().bypass } }
    val reduced = rememberReducedMotion()
    val fontScale = LocalDensity.current.fontScale
    var seenCount by rememberSaveable { mutableIntStateOf(bandCount) }
    LaunchedEffect(bandCount) {
        if (bandCount != seenCount) {
            seenCount = bandCount
            scroll.scrollTo(0)
        }
    }
    val bubble = remember { BubbleState() }
    val cardColor = MaterialTheme.colorScheme.surfaceContainerLow

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = if (compact) 0.dp else 4.dp).alpha(if (bypass) 0.6f else 1f)) {
                ReadoutBar(state)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().heightIn(min = minTrack + labelRow * labelRows + LabelGap)) {
                    val rowWidth = maxWidth - ScaleWidth
                    val showLabels = SliderMath.showsGainLabel(SliderMath.columnPitch(rowWidth.value, bandCount, fontScale))
                    Row(Modifier.fillMaxSize()) {
                        DbScaleColumn(topReserve = if (showLabels) labelRow else 0.dp, labelRow = labelRow)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            Crossfade(
                                targetState = bandCount,
                                animationSpec = if (reduced) snap() else tween(200),
                                label = "bands",
                            ) { count ->
                                SliderRow(count, rowWidth.value, fontScale, labelRow, scroll, state, bubble, onDragStart, onChange, onDragEnd, onReset)
                            }
                            ScrollEdgeFade(scroll, cardColor, reduced)
                        }
                    }
                }
            }
            ValueBubbleOverlay(bubble)
            // Keep the back gesture off the first and last slider.
            if (Build.VERSION.SDK_INT >= 29) {
                for (a in listOf(Alignment.CenterStart, Alignment.CenterEnd)) {
                    Box(Modifier.align(a).width(FadeWidth).fillMaxHeight().heightIn(max = 200.dp).systemGestureExclusion())
                }
            }
        }
    }
}

@Composable
private fun ReadoutBar(state: () -> EqUiState) {
    val s = state()
    val band = s.lastTouchedBand
    val hz = band?.let { s.graphicCentresHz.getOrNull(it) }
    val gain = band?.let { s.graphicGainsDb.getOrNull(it) }
    val fs = LocalDensity.current.fontScale
    val hint = if (fs >= 1.3f) HINT_SHORT else HINT
    val text = if (hz != null && gain != null) "${FreqFormat.withUnit(hz)}   ${GainFormat.format(gain)}" else hint
    val height = with(LocalDensity.current) { 20.sp.toDp() }.let { maxOf(24.dp, it + 4.dp) }
    Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium.merge(Tnum),
            color = if (hz != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("readout").semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun DbScaleColumn(topReserve: androidx.compose.ui.unit.Dp, labelRow: androidx.compose.ui.unit.Dp) {
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall.merge(Tnum).capScale()
    Column(Modifier.width(ScaleWidth).fillMaxHeight().clearAndSetSemantics {}) {
        Spacer(Modifier.height(topReserve))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val span = maxHeight - ThumbInsetDp * 2
            for ((text, frac) in listOf("+12" to 0f, "0" to 0.5f, "−12" to 1f)) {
                Text(
                    text,
                    style = style,
                    color = colour,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.TopEnd).offset(y = ThumbInsetDp + span * frac - 8.dp).padding(end = 4.dp),
                )
            }
        }
        Box(Modifier.height(labelRow + LabelGap), contentAlignment = Alignment.BottomEnd) {
            Text("Hz", style = style, color = colour, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SliderRow(
    count: Int,
    rowWidthDp: Float,
    fontScale: Float,
    labelRow: androidx.compose.ui.unit.Dp,
    scroll: ScrollState,
    state: () -> EqUiState,
    bubble: BubbleState,
    onDragStart: (Int) -> Unit,
    onChange: (Int, Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: (Int) -> Unit,
) {
    val pitchDp = SliderMath.columnPitch(rowWidthDp, count, fontScale)
    // Floor to a whole pixel so that count x pitch never exceeds the row because of per-column rounding.
    val density = LocalDensity.current
    val pitch = with(density) { pitchDp.dp.toPx().toInt().toDp() }
    val scrolls = !SliderMath.fitsWithoutScroll(pitchDp, count, rowWidthDp)
    val showGain = SliderMath.showsGainLabel(pitchDp)
    val scope = rememberCoroutineScope()
    val centres by remember { derivedStateOf { state().graphicCentresHz } }
    Row(
        Modifier.fillMaxSize().testTag("slider-row").horizontalScroll(scroll),
        horizontalArrangement = Arrangement.Start,
    ) {
        if (scrolls) Spacer(Modifier.width(ScrollEndPadding))
        for (i in 0 until count) {
            val hz = centres.getOrNull(i) ?: continue
            val requester = remember { BringIntoViewRequester() }
            Column(Modifier.width(pitch).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showGain) {
                    Text(
                        gainLabel(state().graphicGainsDb.getOrElse(i) { 0f }, fontScale),
                        style = MaterialTheme.typography.labelSmall.merge(Tnum).capScale(),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.height(labelRow).clearAndSetSemantics { testTag = "gain-label" },
                    )
                }
                VerticalGainSlider(
                    index = i,
                    frequencyHz = hz,
                    gainDb = { state().graphicGainsDb.getOrElse(i) { 0f } },
                    pitch = pitch,
                    onDragStart = { onDragStart(i) },
                    onChange = { onChange(i, it) },
                    onDragEnd = onDragEnd,
                    onReset = { onReset(i) },
                    bubble = bubble,
                    modifier = Modifier
                        .weight(1f)
                        .bringIntoViewRequester(requester)
                        .onFocusChanged { if (it.isFocused) scope.launch { requester.bringIntoView() } },
                )
                Spacer(Modifier.height(LabelGap))
                Text(
                    FreqFormat.format(hz),
                    style = MaterialTheme.typography.labelSmall.merge(Tnum).capScale(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.height(labelRow).fillMaxWidth().clearAndSetSemantics {},
                )
            }
        }
        if (scrolls) Spacer(Modifier.width(ScrollEndPadding))
    }
}

/** 24 dp gradients to the card colour on a side that has more content (150 ms; instant when reduced). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ScrollEdgeFade(scroll: ScrollState, card: Color, reduced: Boolean) {
    val spec = if (reduced) snap<Float>() else tween(150)
    val start by animateFloatAsState(if (scroll.canScrollBackward) 1f else 0f, spec, label = "fadeStart")
    val end by animateFloatAsState(if (scroll.canScrollForward) 1f else 0f, spec, label = "fadeEnd")
    if (start > 0f) {
        Box(
            Modifier.align(Alignment.CenterStart).width(FadeWidth).fillMaxHeight().alpha(start)
                .background(Brush.horizontalGradient(listOf(card, Color.Transparent))),
        )
    }
    if (end > 0f) {
        Box(
            Modifier.align(Alignment.CenterEnd).width(FadeWidth).fillMaxHeight().alpha(end)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, card))),
        )
    }
}
