package com.dylenq.eq.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.dylenq.eq.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val RESET_ALL_MESSAGE = "Sliders and preamp reset"
const val CORE_ERROR_MESSAGE = "Couldn't apply that change."
internal const val RESET_SNACKBAR_MS = 6000L
internal const val ERROR_SNACKBAR_MS = 4000L

/**
 * UI-SPEC region 1: title, the Equalizer toggle and the overflow menu. [phase5Slot] is the reserved,
 * empty place for the Phase 5 Undo/Redo icon buttons (no icons are added now).
 */
@Composable
fun EqTopBar(
    state: () -> EqUiState,
    onBypass: (Boolean) -> Unit,
    onResetAll: () -> Unit,
    modifier: Modifier = Modifier,
    phase5Slot: @Composable RowScope.() -> Unit = {},
) {
    val s = state()
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(64.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("EQ", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        phase5Slot()
        EqualizerToggle(on = !s.bypass, onChange = { on -> onBypass(!on) })
        OverflowMenu(canReset = s.graphicGainsDb.any { it != 0f } || s.preampDb != 0f, onResetAll = onResetAll)
    }
}

/** 48 dp toggle: filled `primary` when the EQ is active, outlined when bypassed. Same state as the notification. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerToggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Equalizer") } },
        state = rememberTooltipState(),
    ) {
        FilledIconToggleButton(
            checked = on,
            onCheckedChange = onChange,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedContainerColor = MaterialTheme.colorScheme.primary,
                checkedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = modifier
                .size(48.dp)
                .then(if (on) Modifier else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                .testTag("equalizer-toggle")
                .semantics {
                    contentDescription = "Equalizer"
                    stateDescription = if (on) "On" else "Off"
                },
        ) {
            Icon(painterResource(R.drawable.ic_power_settings_new), contentDescription = null, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun OverflowMenu(canReset: Boolean, onResetAll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = Modifier.size(48.dp).testTag("overflow").semantics { contentDescription = "More options" },
    ) {
        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Reset all") },
            enabled = canReset,
            onClick = {
                open = false
                onResetAll()
            },
            modifier = Modifier.testTag("reset-all"),
        )
    }
}

/**
 * Shows [message] for [durationMs] (SnackbarDuration.Long is not 6 s, so the snackbar is indefinite and
 * dismissed by a timer). [onAction] runs if the action was pressed.
 */
internal suspend fun SnackbarHostState.showTimed(
    message: String,
    durationMs: Long,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    coroutineScope {
        val timer = launch {
            delay(durationMs)
            currentSnackbarData?.dismiss()
        }
        val result = showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite)
        timer.cancel()
        if (result == SnackbarResult.ActionPerformed) onAction()
    }
}

/**
 * Returns the Reset all handler: zeroes sliders and preamp as one undo step, then shows the
 * "Sliders and preamp reset" snackbar for 6 s with an Undo action that restores them.
 */
@Composable
fun rememberResetAll(holder: UiStateHolder, host: SnackbarHostState): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember(holder, host) {
        {
            holder.resetAll()
            scope.launch { host.showTimed(RESET_ALL_MESSAGE, RESET_SNACKBAR_MS, "Undo") { holder.undo() } }
            Unit
        }
    }
}

/** Shows "Couldn't apply that change." for 4 s (no action) for every holder error event. */
@Composable
fun EqErrorSnackbars(holder: UiStateHolder, host: SnackbarHostState) {
    LaunchedEffect(holder, host) {
        holder.errors.collect { msg -> launch { host.showTimed(msg, ERROR_SNACKBAR_MS) } }
    }
}
