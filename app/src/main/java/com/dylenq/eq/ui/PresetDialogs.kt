package com.dylenq.eq.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue

/** Cuts [s] to at most [PresetNames.MAX_NAME_CHARS] code points (the core counts chars, not UTF-16 units). */
private fun capName(s: String): String =
    if (s.codePointCount(0, s.length) <= PresetNames.MAX_NAME_CHARS) s
    else s.substring(0, s.offsetByCodePoints(0, PresetNames.MAX_NAME_CHARS))

/**
 * Save-as and rename dialog (09-UI-SPEC section 3). [existingNames] is every display name (built-ins
 * included); [ownName] is the preset being renamed (its own name is not "taken"). [onSubmit] returns the core
 * result; a rejection is shown in the supporting-text slot and the dialog stays open.
 */
@Composable
internal fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    existingNames: List<String>,
    ownName: String?,
    onSubmit: (String) -> PresetResult,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    var touched by rememberSaveable { mutableStateOf(false) }
    var rejected by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val check = PresetNames.check(value.text, existingNames, ownName)
    val unchanged = ownName != null && value.text.trim() == initial.trim() && value.text.trim() == ownName.trim()
    val canConfirm = check == null && !unchanged
    // A blank field is not an error until the user has typed; the confirm button is disabled either way.
    val shownError = rejected ?: check?.takeIf { touched || it != PresetNames.NAME_BLANK }
    val submit = {
        if (canConfirm) {
            when (val r = onSubmit(value.text)) {
                PresetResult.Ok -> onDismiss()
                is PresetResult.Rejected -> rejected = r.message
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    val capped = capName(it.text)
                    value = if (capped == it.text) it else it.copy(text = capped, selection = TextRange(capped.length))
                    touched = true
                    rejected = null
                },
                label = { Text("Name") },
                singleLine = true,
                isError = shownError != null,
                supportingText = {
                    Text(
                        shownError ?: "Up to ${PresetNames.MAX_NAME_CHARS} characters",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = if (shownError != null) Modifier.semantics { error(shownError) } else Modifier,
                    )
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focus).testTag("preset-name-field"),
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canConfirm, modifier = Modifier.testTag("preset-dialog-confirm")) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("preset-dialog-cancel")) { Text("Cancel") }
        },
    )
}

/** Delete confirmation (T-09-09): the Delete label is in the error colour, Cancel is the default dismiss. */
@Composable
internal fun DeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete preset?") },
        text = { Text("Delete $name? This can't be undone.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.testTag("preset-dialog-confirm"),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("preset-dialog-cancel")) { Text("Cancel") }
        },
    )
}
