package com.dylenq.eq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** The callbacks that drive one control: a value change, the gesture bracket and the reset. */
class ToneActions<T>(
    val onValue: (T) -> Unit,
    val onDragStart: () -> Unit,
    val onDragEnd: () -> Unit,
    val onReset: () -> Unit,
)

/** Largest balance percent the slider offers; the core's value is percent / 100. */
private const val BALANCE_MAX = 100f

/**
 * UI-SPEC "Tone & balance": Bass, Treble and Balance sliders with value pills. State is read from
 * [state] only, so undo, redo and restore move the thumbs with no local state. [largeFont] is the stacked
 * two-line row form (font scale >= 1.3); [compact] the landscape padding.
 */
@Composable
fun ToneCard(
    state: () -> EqUiState,
    bass: ToneActions<Float>,
    treble: ToneActions<Float>,
    balance: ToneActions<Int>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    largeFont: Boolean = false,
) {
    val s = state()
    Card(
        modifier = modifier.fillMaxWidth().testTag("tone-card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp)) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = if (largeFont) 40.dp else 32.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Tone & balance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
            }
            ToneRow(
                label = "Bass", tag = "bass",
                value = s.bassDb,
                min = SliderMath.MIN_DB, max = SliderMath.MAX_DB, steps = SliderMath.STEPS,
                pillText = GainFormat.format(s.bassDb),
                spoken = GainSpeech.format(s.bassDb),
                resetLabel = "Reset to zero",
                prepare = { raw -> SliderMath.snap(raw).takeIf { abs(it - state().bassDb) > 1e-4f } },
                commit = { bass.onValue(it) },
                haptic = { h, old, new -> h.onChange(old, new) },
                current = { state().bassDb },
                onDragStart = bass.onDragStart, onDragEnd = bass.onDragEnd, onReset = bass.onReset,
                stacked = largeFont,
            )
            ToneRow(
                label = "Treble", tag = "treble",
                value = s.trebleDb,
                min = SliderMath.MIN_DB, max = SliderMath.MAX_DB, steps = SliderMath.STEPS,
                pillText = GainFormat.format(s.trebleDb),
                spoken = GainSpeech.format(s.trebleDb),
                resetLabel = "Reset to zero",
                prepare = { raw -> SliderMath.snap(raw).takeIf { abs(it - state().trebleDb) > 1e-4f } },
                commit = { treble.onValue(it) },
                haptic = { h, old, new -> h.onChange(old, new) },
                current = { state().trebleDb },
                onDragStart = treble.onDragStart, onDragEnd = treble.onDragEnd, onReset = treble.onReset,
                stacked = largeFont,
            )
            val percent = BalanceMath.toPercent(s.balance)
            ToneRow(
                label = "Balance", tag = "balance",
                value = percent.toFloat(),
                min = -BALANCE_MAX, max = BALANCE_MAX, steps = 199,
                pillText = BalanceFormat.format(percent),
                spoken = BalanceSpeech.format(percent),
                resetLabel = "Reset to centre",
                prepare = { raw ->
                    val p = snapBalance(raw)
                    p.toFloat().takeIf { p != BalanceMath.toPercent(state().balance) }
                },
                commit = { balance.onValue(it.roundToInt()) },
                haptic = { h, old, new ->
                    if (new == 0f && old != 0f) h.zeroDetent()
                    else if (floor(new / 10f) != floor(old / 10f)) h.tick()
                },
                current = { BalanceMath.toPercent(state().balance).toFloat() },
                onDragStart = balance.onDragStart, onDragEnd = balance.onDragEnd, onReset = balance.onReset,
                stacked = largeFont,
            )
        }
    }
}

/** Whole percent inside +-100; |p| <= 1 snaps to Centre like the core. */
internal fun snapBalance(raw: Float): Int {
    if (!raw.isFinite()) return 0
    val p = raw.roundToInt().coerceIn(-100, 100)
    return if (abs(p) <= 1) 0 else p
}

/** Idle time after a slider value change with no drag before its undo group is closed. */
private const val DISCRETE_EDIT_IDLE_MS = 400L

/**
 * One control row. [prepare] turns a raw slider value into the snapped new value, or null when nothing
 * changes (so no empty undo step starts); [commit] then stores it. The gesture group is per row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToneRow(
    label: String,
    tag: String,
    value: Float,
    min: Float,
    max: Float,
    steps: Int,
    pillText: String,
    spoken: String,
    resetLabel: String,
    prepare: (Float) -> Float?,
    commit: (Float) -> Unit,
    haptic: (Haptics, Float, Float) -> Unit,
    current: () -> Float,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    stacked: Boolean,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val group = remember { GestureGroup() }
    val startNow by rememberUpdatedStateOf(onDragStart)
    val endNow by rememberUpdatedStateOf(onDragEnd)
    val resetNow by rememberUpdatedStateOf(onReset)
    fun begin() {
        if (!group.open) {
            group.open = true
            startNow()
        }
    }
    fun end() {
        if (group.open) {
            group.open = false
            endNow()
        }
    }
    // A value change with no pointer drag (TalkBack SetProgress, D-pad) may arrive without
    // onValueChangeFinished; close the group after a short idle so undo is never blocked.
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    fun scheduleIdleClose() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(DISCRETE_EDIT_IDLE_MS)
            if (!dragging) end()
        }
    }
    DisposableEffect(Unit) { onDispose { end() } }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            when (it) {
                is DragInteraction.Start -> {
                    dragging = true
                    haptics.dragStart()
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    dragging = false
                    end()
                    haptics.dragEnd()
                }
                else -> {}
            }
        }
    }
    // Reset at the default is a no-op: no haptic, no undo step.
    val reset: () -> Unit = {
        if (current() != 0f) {
            haptics.reset()
            resetNow()
        }
    }
    val resetAction = listOf(
        CustomAccessibilityAction(resetLabel) {
            resetNow()
            true
        },
    )

    val slider: @Composable (Modifier) -> Unit = { mod ->
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = { raw ->
                // SetProgress (TalkBack, tests) is not snapped by the Slider itself.
                val v = prepare(raw)
                if (v != null) {
                    begin()
                    haptic(haptics, current(), v)
                    commit(v)
                    scheduleIdleClose()
                }
            },
            onValueChangeFinished = { end() },
            valueRange = min..max,
            steps = steps,
            interactionSource = interaction,
            thumb = { SliderThumb() },
            track = { st -> BipolarTrack(((st.value - min) / (max - min)).coerceIn(0f, 1f)) },
            modifier = mod
                .testTag("$tag-slider")
                .semantics {
                    contentDescription = label
                    stateDescription = spoken
                    customActions = resetAction
                }
                .observeDoubleTap(reset),
        )
    }
    val pill: @Composable () -> Unit = {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .widthIn(min = 72.dp)
                .heightIn(min = 48.dp)
                .testTag("$tag-pill")
                .clearAndSetSemantics {
                    text = AnnotatedString(pillText)
                    contentDescription = "$label $spoken"
                    customActions = resetAction
                }
                .observeDoubleTap(reset),
        ) {
            Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(pillText, style = MaterialTheme.typography.titleMedium.merge(Tnum), maxLines = 1)
            }
        }
    }

    if (stacked) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                pill()
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                slider(Modifier.fillMaxWidth())
            }
        }
    } else {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.widthIn(min = 56.dp))
            slider(Modifier.weight(1f))
            pill()
        }
    }
}

/**
 * 4 dp track whose active fill runs from the centre to the thumb, with a 2 x 12 dp centre tick.
 * No fill at the centre. [frac] is the thumb position 0..1.
 */
@Composable
private fun BipolarTrack(frac: Float) {
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.outline
    Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(base).drawBehind {
                val x0 = minOf(0.5f, frac) * size.width
                val x1 = maxOf(0.5f, frac) * size.width
                if (x1 > x0) drawRect(fill, topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height))
            },
        )
        Box(Modifier.width(2.dp).height(12.dp).clip(CircleShape).background(tick))
    }
}
