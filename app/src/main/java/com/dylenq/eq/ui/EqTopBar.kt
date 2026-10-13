package com.dylenq.eq.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
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
import com.dylenq.eq.core.EqMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    heightDp: Int = 64,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    phase5Slot: @Composable RowScope.() -> Unit = {},
) {
    val s = state()
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .height(heightDp.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("EQ", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        phase5Slot()
        UndoRedoButtons(
            canUndo = s.canUndo,
            canRedo = s.canRedo,
            gestureOpen = s.gestureOpen,
            onUndo = onUndo,
            onRedo = onRedo,
        )
        EqualizerToggle(on = !s.bypass, onChange = { on -> onBypass(!on) })
        OverflowMenu(
            canReset = s.mode == EqMode.PARAMETRIC || s.graphicGainsDb.any { it != 0f } || s.preampDb != 0f,
            onResetAll = onResetAll,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * Undo and Redo (UI-SPEC "Undo and Redo", PEQ-06): 48 dp icon buttons in both modes. Enabled only when the
 * core has something to undo (redo) and no gesture is open, so a step can never land mid-drag.
 */
@Composable
fun UndoRedoButtons(
    canUndo: Boolean,
    canRedo: Boolean,
    gestureOpen: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    HistoryButton("Undo", R.drawable.ic_undo, "undo", canUndo && !gestureOpen, onUndo)
    HistoryButton("Redo", R.drawable.ic_redo, "redo", canRedo && !gestureOpen, onRedo)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryButton(label: String, icon: Int, tag: String, enabled: Boolean, onClick: () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            ),
            modifier = Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label },
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
        }
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
private fun OverflowMenu(canReset: Boolean, onResetAll: () -> Unit, onOpenSettings: () -> Unit) {
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
        DropdownMenuItem(
            text = { Text("Settings") },
            onClick = {
                open = false
                onOpenSettings()
            },
            modifier = Modifier.height(48.dp).testTag("settings-item"),
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
    /** When this returns the snackbar is dismissed early (the action it offered is no longer valid). */
    dismissWhen: (suspend () -> Unit)? = null,
    onAction: () -> Unit = {},
) {
    coroutineScope {
        val timer = launch {
            delay(durationMs)
            currentSnackbarData?.dismiss()
        }
        val watcher = dismissWhen?.let { w ->
            launch {
                w()
                currentSnackbarData?.dismiss()
            }
        }
        val result = showSnackbar(message, actionLabel, duration = SnackbarDuration.Indefinite)
        timer.cancel()
        watcher?.cancel()
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
            // Parametric: the holder emits the "Bands and preamp reset" message, shown by EqMessageSnackbars.
            if (holder.ui.value.mode != EqMode.GRAPHIC) return@remember Unit
            // Undo is only valid while nothing else has been edited since the reset; any later edit,
            // undo or redo dismisses the snackbar (otherwise Undo would undo that later edit).
            val serial = holder.editSerial.value
            scope.launch {
                host.showTimed(
                    RESET_ALL_MESSAGE,
                    RESET_SNACKBAR_MS,
                    "Undo",
                    dismissWhen = { holder.editSerial.first { it != serial } },
                ) { holder.undo() }
            }
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
