package com.dylenq.eq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

internal const val PREAMP_MIN_DB = -24f
internal const val PREAMP_MAX_DB = 6f

/** Slider steps between the ends: 30 dB / 0.5 dB = 60 intervals, 59 interior stops. */
internal const val PREAMP_STEPS = 59

const val LIMITER_DESCRIPTION = "Eases sustained loud sections. It can't catch short peaks."
private val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** "Over by 2.5 dB" magnitude of a negative headroom. */
internal fun overBy(headroomDb: Float): String = String.format(Locale.US, "%.1f", -headroomDb)

/** The suggested-preamp chip is shown when it would change the preamp by at least 0.5 dB. */
internal fun showsSuggestion(suggestedDb: Float, preampDb: Float): Boolean =
    suggestedDb < 0f && abs(suggestedDb - preampDb) >= 0.5f

/**
 * UI-SPEC "Preamp, Limiter, Bypass": preamp slider with value pill, suggested-preamp row and limiter
 * switch. [compact] is the landscape form (two 56 dp rows, headroom text omitted).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreampCard(
    state: () -> EqUiState,
    onPreamp: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onReset: () -> Unit,
    onApplySuggested: () -> Unit,
    onLimiter: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val s = state()
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }

    // One undo step per gesture: opened with the first change, closed when the gesture finishes.
    val group = remember { GestureGroup() }
    val startNow by rememberUpdatedStateOf(onDragStart)
    val endNow by rememberUpdatedStateOf(onDragEnd)
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
    DisposableEffect(Unit) { onDispose { end() } }
    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            when (it) {
                is DragInteraction.Start -> haptics.dragStart()
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    end()
                    haptics.dragEnd()
                }
                else -> {}
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(if (compact) 8.dp else 16.dp)) {
            // Row 1: label, slider, value pill.
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Preamp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = s.preampDb.coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB),
                    onValueChange = { raw ->
                        // SetProgress (TalkBack, tests) is not snapped by the Slider itself.
                        val v = snapPreamp(raw)
                        val cur = state().preampDb
                        if (abs(v - cur) > 1e-4f) {
                            begin()
                            haptics.onChange(cur, v)
                            onPreamp(v)
                        }
                    },
                    onValueChangeFinished = { end() },
                    valueRange = PREAMP_MIN_DB..PREAMP_MAX_DB,
                    steps = PREAMP_STEPS,
                    interactionSource = interaction,
                    thumb = { PreampThumb() },
                    track = { st ->
                        val span = PREAMP_MAX_DB - PREAMP_MIN_DB
                        val frac = ((st.value - PREAMP_MIN_DB) / span).coerceIn(0f, 1f)
                        Box(
                            Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(frac).fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("preamp-slider")
                        .semantics {
                            contentDescription = "Preamp"
                            stateDescription = GainSpeech.format(s.preampDb)
                            customActions = listOf(
                                CustomAccessibilityAction("Reset to zero") {
                                    onReset()
                                    true
                                },
                            )
                        }
                        .observeDoubleTap {
                            haptics.reset()
                            onReset()
                        },
                )
                PreampPill(s.preampDb, onReset)
            }

            if (!compact) SuggestedRow(s, onApplySuggested)
            LimiterRow(s.limiterEnabled, onLimiter, compact)
        }
    }
}

/** Snaps to the 0.5 dB grid inside the preamp range. */
internal fun snapPreamp(db: Float): Float =
    (Math.round(db * 2f) / 2f).coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB).let { if (it == 0f) 0f else it }

/**
 * Reports a double tap without consuming anything: the slider keeps its own press and drag handling
 * (a `detectTapGestures` parent loses the slider's drag, and never sees a tap the slider consumed),
 * so this watches the Initial pass only.
 */
private fun Modifier.observeDoubleTap(onDoubleTap: () -> Unit): Modifier = pointerInput(Unit) {
    var lastUp = 0L
    var lastPos = Offset.Unspecified
    val slop = viewConfiguration.touchSlop
    val timeout = viewConfiguration.doubleTapTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var up: PointerInputChange? = null
        var moved = false
        while (true) {
            val ev = awaitPointerEvent(PointerEventPass.Initial)
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if ((ch.position - down.position).getDistance() > slop) {
                moved = true
                break
            }
            if (ch.changedToUp()) {
                up = ch
                break
            }
        }
        if (moved || up == null) {
            lastPos = Offset.Unspecified
        } else if (lastPos != Offset.Unspecified &&
            up.uptimeMillis - lastUp <= timeout &&
            (up.position - lastPos).getDistance() <= slop * 4
        ) {
            lastPos = Offset.Unspecified
            onDoubleTap()
        } else {
            lastUp = up.uptimeMillis
            lastPos = up.position
        }
    }
}

private class GestureGroup {
    var open = false
}

/** Keeps the latest lambda without restarting effects (the gesture group outlives recompositions). */
@Composable
private fun <T> rememberUpdatedStateOf(v: T) = androidx.compose.runtime.rememberUpdatedState(v)

/** 48 dp touch slot around a 20 dp thumb. */
@Composable
private fun PreampThumb() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun PreampPill(preampDb: Float, onReset: () -> Unit) {
    val haptics = rememberHaptics()
    val label = GainFormat.format(preampDb)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .widthIn(min = 72.dp)
            .heightIn(min = 48.dp)
            .testTag("preamp-pill")
            .clearAndSetSemantics {
                text = AnnotatedString(label)
                contentDescription = "Preamp ${GainSpeech.format(preampDb)}"
                customActions = listOf(
                    CustomAccessibilityAction("Reset to zero") {
                        onReset()
                        true
                    },
                )
            }
            .observeDoubleTap {
                haptics.reset()
                onReset()
            },
    ) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium.merge(Tnum))
        }
    }
}

/** Chip when a suggestion would change the preamp, else helper text; an over-the-limit error replaces the helper. */
@Composable
private fun SuggestedRow(s: EqUiState, onApply: () -> Unit) {
    val chip = showsSuggestion(s.suggestedPreampDb, s.preampDb)
    val over = s.headroomAfterPreampDb < -0.05f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chip) {
            AssistChip(
                onClick = onApply,
                label = { Text("Apply suggested ${GainFormat.format(s.suggestedPreampDb)}") },
                modifier = Modifier.testTag("apply-suggested"),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (over) {
            Text(
                "Over by ${overBy(s.headroomAfterPreampDb)} dB. Lower the preamp or apply the suggestion.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f).testTag("over-by"),
            )
        } else if (!chip) {
            Text(
                "Headroom ${String.format(Locale.US, "%.1f", s.headroomAfterPreampDb)} dB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("headroom"),
            )
        }
    }
}

@Composable
private fun LimiterRow(on: Boolean, onChange: (Boolean) -> Unit, compact: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("limiter-row")
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(top = if (compact) 0.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Limiter", style = MaterialTheme.typography.bodyMedium)
            Text(
                LIMITER_DESCRIPTION,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("limiter-description"),
            )
        }
        Switch(checked = on, onCheckedChange = null, modifier = Modifier.testTag("limiter-switch"))
    }
}
