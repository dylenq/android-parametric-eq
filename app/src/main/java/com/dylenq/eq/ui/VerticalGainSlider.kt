package com.dylenq.eq.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private val TrackWidth = 4.dp
private val ThumbInset = 14.dp // half the pressed thumb, so a thumb at either end stays inside the column
private val TouchSlotRadius = 24.dp // 48 dp slot at the thumb

/**
 * State of the value bubble shared by the sliders of one panel. A slider writes it, the panel (or a
 * test host) draws it with [ValueBubbleOverlay] where the scroller cannot clip it.
 */
@Stable
class BubbleState {
    var pressed by mutableStateOf(false)
    var gainDb by mutableFloatStateOf(0f)
    internal var sliderCoords: LayoutCoordinates? = null
}

/**
 * L-03 custom vertical gain slider: -12..+12 dB in 0.5 dB steps with a zero detent. One continuous
 * drag is one undo step (the callbacks bracket it, also when the gesture is cancelled).
 */
@Composable
fun VerticalGainSlider(
    index: Int,
    frequencyHz: Float,
    gainDb: () -> Float,
    pitch: androidx.compose.ui.unit.Dp,
    onDragStart: () -> Unit,
    onChange: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    bubble: BubbleState,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val reduced = rememberReducedMotion()
    val curStart = rememberUpdatedState(onDragStart)
    val curChange = rememberUpdatedState(onChange)
    val curEnd = rememberUpdatedState(onDragEnd)
    val curReset = rememberUpdatedState(onReset)
    val curGain = rememberUpdatedState(gainDb)

    var pressed by remember { mutableStateOf(false) }
    // While a finger is down this holds the value shown, so the thumb tracks it without waiting for a recomposition.
    var live by remember { mutableStateOf<Float?>(null) }
    val shown: () -> Float = { live ?: curGain.value() }

    val thumbRadius by animateDpAsState(
        if (pressed) 14.dp else 10.dp,
        if (reduced) snap() else tween(100),
        label = "thumb",
    )

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val inactive = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outline

    fun commitProgress(v: Float) {
        // Assistive adjustment is a complete drag: start, one change, end.
        val target = SliderMath.snap(v)
        if (target == curGain.value()) return
        curStart.value()
        curChange.value(target)
        curEnd.value()
    }

    val cd = FreqSpeech.format(frequencyHz)
    val state = GainSpeech.format(curGain.value())
    val current = curGain.value().coerceIn(SliderMath.MIN_DB, SliderMath.MAX_DB)

    Box(
        modifier
            .width(pitch)
            .testTag("band-$index")
            .onGloballyPositioned { bubble.sliderCoords = it }
            .semantics {
                contentDescription = cd
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(current, SliderMath.MIN_DB..SliderMath.MAX_DB, SliderMath.STEPS)
                setProgress { v -> commitProgress(v); true }
                customActions = listOf(
                    CustomAccessibilityAction("Increase by 0.5 decibel") {
                        commitProgress(curGain.value() + SliderMath.STEP_DB); true
                    },
                    CustomAccessibilityAction("Decrease by 0.5 decibel") {
                        commitProgress(curGain.value() - SliderMath.STEP_DB); true
                    },
                    CustomAccessibilityAction("Reset to zero") {
                        curReset.value(); true
                    },
                )
            }
            .focusable()
            // Outer: double-tap. A drag consumes the changes before this sees an "up", so it never fires after a drag.
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    haptics.reset()
                    curReset.value()
                })
            }
            // Inner: drag. Absolute position under the finger once vertical touch slop is exceeded.
            .pointerInput(Unit) {
                val inset = ThumbInset.toPx()
                val slot = TouchSlotRadius.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val h = size.height.toFloat()
                    fun fractionAt(y: Float): Float = 1f - ((y - inset) / (h - 2 * inset)).coerceIn(0f, 1f)
                    val thumbY = inset + (1f - SliderMath.fractionFromGain(shown())) * (h - 2 * inset)
                    if (abs(down.position.y - thumbY) <= slot) {
                        pressed = true
                        bubble.gainDb = shown()
                        bubble.pressed = true
                    }
                    var dragging = false
                    var last = shown()
                    try {
                        val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        if (slop != null) {
                            dragging = true
                            pressed = true
                            bubble.pressed = true
                            curStart.value()
                            haptics.dragStart()
                            fun move(y: Float) {
                                val g = SliderMath.gainFromFraction(fractionAt(y))
                                live = g
                                bubble.gainDb = g
                                if (g != last) {
                                    haptics.onChange(last, g)
                                    last = g
                                    curChange.value(g)
                                }
                            }
                            move(slop.position.y)
                            verticalDrag(slop.id) { change ->
                                change.consume()
                                move(change.position.y)
                            }
                        }
                    } finally {
                        if (dragging) {
                            curEnd.value()
                            haptics.dragEnd()
                        }
                        live = null
                        pressed = false
                        bubble.pressed = false
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val gain = shown()
            drawGainSlider(gain, thumbRadius.toPx(), pressed, inactive, primary, onPrimary, outline)
        }
    }
}

private fun DrawScope.thumbCenterY(h: Float, gain: Float): Float {
    val inset = ThumbInset.toPx()
    return inset + (1f - SliderMath.fractionFromGain(gain)) * (h - 2 * inset)
}

private fun DrawScope.drawGainSlider(
    gain: Float,
    thumbRadiusPx: Float,
    pressed: Boolean,
    inactive: androidx.compose.ui.graphics.Color,
    primary: androidx.compose.ui.graphics.Color,
    onPrimary: androidx.compose.ui.graphics.Color,
    outline: androidx.compose.ui.graphics.Color,
) {
    val inset = ThumbInset.toPx()
    val tw = TrackWidth.toPx()
    val cx = size.width / 2f
    val top = inset
    val bottom = size.height - inset
    val centre = (top + bottom) / 2f
    val corner = CornerRadius(tw / 2f)
    drawRoundRect(inactive, Offset(cx - tw / 2f, top), Size(tw, bottom - top), corner)
    val ty = thumbCenterY(size.height, gain)
    val a = minOf(ty, centre)
    val b = maxOf(ty, centre)
    if (b - a > 0.5f) drawRoundRect(primary, Offset(cx - tw / 2f, a), Size(tw, b - a), corner)
    // 0 dB tick across the track: 12 dp wide, 1 dp tall.
    val tickW = 12.dp.toPx()
    drawRect(outline, Offset(cx - tickW / 2f, centre - 0.5.dp.toPx()), Size(tickW, 1.dp.toPx()))
    drawCircle(primary, thumbRadiusPx, Offset(cx, ty))
    if (pressed) drawCircle(onPrimary, thumbRadiusPx - 2.dp.toPx(), Offset(cx, ty), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
}

/**
 * Draws the value bubble of [state] 56 dp above the thumb, clamped inside this overlay's bounds
 * (place it over the slider panel). Fades in over 100 ms and out over 150 ms (instant when reduced).
 */
@Composable
fun ValueBubbleOverlay(state: BubbleState, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val alpha by animateFloatAsState(
        if (state.pressed) 1f else 0f,
        if (reduced) snap() else tween(if (state.pressed) 100 else 150),
        label = "bubble",
    )
    var overlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(modifier.fillMaxSize().onGloballyPositioned { overlayCoords = it }) {
        if (state.pressed || alpha > 0f) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .testTag("value-bubble")
                    .graphicsLayer { this.alpha = alpha }
                    .onGloballyPositioned { size = it.size }
                    .offset {
                        val o = overlayCoords
                        val s = state.sliderCoords
                        if (o == null || s == null || !s.isAttached || !o.isAttached) {
                            IntOffset(0, -10000)
                        } else {
                            val inset = with(density) { ThumbInset.toPx() }
                            val ty = inset + (1f - SliderMath.fractionFromGain(state.gainDb)) * (s.size.height - 2 * inset)
                            val centre = o.localPositionOf(s, Offset(s.size.width / 2f, ty))
                            val rise = with(density) { 56.dp.toPx() }
                            val x = (centre.x - size.width / 2f).coerceIn(0f, (o.size.width - size.width).toFloat().coerceAtLeast(0f))
                            val y = (centre.y - rise - size.height / 2f).coerceIn(0f, (o.size.height - size.height).toFloat().coerceAtLeast(0f))
                            IntOffset(x.roundToInt(), y.roundToInt())
                        }
                    },
            ) {
                Text(
                    GainFormat.format(state.gainDb),
                    style = MaterialTheme.typography.labelLarge.merge(TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium)),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
