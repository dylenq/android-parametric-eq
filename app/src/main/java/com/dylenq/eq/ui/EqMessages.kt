package com.dylenq.eq.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shows the holder's [UiMessage]s as snackbars (UI-SPEC "Undo and Redo"). An undoable message (delete a band,
 * remove all, reset) stays 6 s with an Undo action; any later edit, undo or redo dismisses it, so its Undo can
 * never undo something else (T-05-14). A plain message (the 16-band limit) stays 4 s with no action. A new
 * message replaces the one on screen because the host shows one snackbar at a time.
 */
@Composable
fun EqMessageSnackbars(holder: UiStateHolder, host: SnackbarHostState) {
    LaunchedEffect(holder, host) {
        // Cancelling the previous show removes its snackbar, so a newer message really replaces it. Without
        // this the host queued messages behind the visible one while their 4 s timers ran, and a message
        // emitted in a burst (for example "Deleted" then "That preset no longer exists.") could be lost.
        var shown: kotlinx.coroutines.Job? = null
        holder.messages.collect { msg ->
            shown?.cancel()
            // The holder bumps editSerial before emitting, so the serial read here belongs to the action.
            val serial = holder.editSerial.value
            shown = launch {
                host.showTimed(
                    msg.text,
                    if (msg.undoable) RESET_SNACKBAR_MS else ERROR_SNACKBAR_MS,
                    actionLabel = if (msg.undoable) "Undo" else null,
                    dismissWhen = if (msg.undoable) ({ holder.editSerial.first { it != serial } }) else null,
                ) { holder.undo() }
            }
        }
    }
}

/** Ctrl+Z undoes; Ctrl+Shift+Z and Ctrl+Y redo. The holder ignores both while a gesture is open. */
internal fun undoRedoShortcut(e: KeyEvent, holder: UiStateHolder): Boolean {
    if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return false
    return when {
        e.key == Key.Z && e.isShiftPressed -> { holder.redo(); true }
        e.key == Key.Z -> { holder.undo(); true }
        e.key == Key.Y -> { holder.redo(); true }
        else -> false
    }
}
