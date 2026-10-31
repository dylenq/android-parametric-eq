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
 * never undo something else (T-05-14). The serial it watches is the one stamped on the message when the holder
 * emitted it, and Undo does nothing if that serial has moved. A plain message (the 16-band limit, "Saved X")
 * stays 4 s with no action. A newer undoable message replaces whatever is on screen, and a plain message
 * replaces a plain one, but a plain message never dismisses a still-valid Undo snackbar: it waits and is shown
 * when that one ends (a newer plain message replaces a waiting one).
 */
@Composable
fun EqMessageSnackbars(holder: UiStateHolder, host: SnackbarHostState) {
    LaunchedEffect(holder, host) {
        // Cancelling the previous show removes its snackbar, so a newer message really replaces it. Without
        // this the host queued messages behind the visible one while their 4 s timers ran, and a message
        // emitted in a burst (for example "Deleted" then "That preset no longer exists.") could be lost.
        // Everything below runs on this effect's single dispatcher, so the plain vars need no locking.
        val scope = this
        var shown: kotlinx.coroutines.Job? = null
        var shownUndoable = false
        var waiting: UiMessage? = null

        fun show(msg: UiMessage) {
            shownUndoable = msg.undoable
            val serial = msg.serial
            shown = scope.launch {
                host.showTimed(
                    msg.text,
                    if (msg.undoable) RESET_SNACKBAR_MS else ERROR_SNACKBAR_MS,
                    actionLabel = if (msg.undoable) "Undo" else null,
                    dismissWhen = if (msg.undoable) ({ holder.editSerial.first { it != serial } }) else null,
                ) { holder.undoIfUnchanged(serial) }
                // Reached only when this snackbar ended on its own (a cancel throws past here).
                shownUndoable = false
                waiting?.let { next ->
                    waiting = null
                    show(next)
                }
            }
        }

        holder.messages.collect { msg ->
            val current = shown
            val undoVisible = current != null && current.isActive && shownUndoable
            if (!msg.undoable && undoVisible) {
                waiting = msg
            } else {
                waiting = null
                current?.cancel()
                show(msg)
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
