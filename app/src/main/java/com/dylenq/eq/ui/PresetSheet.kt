package com.dylenq.eq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dylenq.eq.R
import com.dylenq.eq.core.PresetInfo

internal const val USER_KEY_PREFIX = "user:"

/**
 * The preset picker (09-UI-SPEC section 2): a pinned header above a scrolling list of built-in and user
 * presets. Tapping a row applies it and closes the sheet; the "Applied" snackbar is shown by the main Scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresetSheet(ui: EqUiState, holder: UiStateHolder, spec: WindowSpec, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val builtIns = ui.presets.filter { it.builtin }
    val userPresets = ui.presets.filter { !it.builtin }
    // Which dialog is open and for which preset survive rotation and font changes (UI-SPEC Pitfall 8).
    var dialog by rememberSaveable { mutableStateOf(PresetDialog.None.name) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    val open = PresetDialog.valueOf(dialog)
    fun show(d: PresetDialog, key: String? = null) { dialog = d.name; target = key }
    fun closeDialog() { dialog = PresetDialog.None.name; target = null }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.semantics { paneTitle = "Presets" }.testTag("preset-sheet"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "Presets",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                HeaderActions(ui, holder, spec.largeFont, onSaveAs = { show(PresetDialog.SaveAs) })
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                item(key = "h-builtin") { SectionHeader("Built-in") }
                items(builtIns, key = { it.key }) { p -> PresetRow(p, ui, holder, onDismiss, onRename = { show(PresetDialog.Rename, p.key) }, onDelete = { show(PresetDialog.Delete, p.key) }) }
                item(key = "h-user") { SectionHeader("Your presets") }
                if (userPresets.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Save the current sound to reuse it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag("preset-empty"),
                        )
                    }
                } else {
                    items(userPresets, key = { it.key }) { p -> PresetRow(p, ui, holder, onDismiss, onRename = { show(PresetDialog.Rename, p.key) }, onDelete = { show(PresetDialog.Delete, p.key) }) }
                }
            }
        }
    }
    val names = ui.presets.map { it.name }
    val targetInfo = ui.presets.firstOrNull { it.key == target }
    when (open) {
        PresetDialog.None -> Unit
        PresetDialog.SaveAs -> NameDialog(
            title = "Save as new preset",
            confirmLabel = "Save",
            initial = "",
            existingNames = names,
            ownName = null,
            onSubmit = { n ->
                holder.savePresetAs(n).also { if (it == PresetResult.Ok) { closeDialog(); onDismiss() } }
            },
            onDismiss = ::closeDialog,
        )
        PresetDialog.Rename -> if (targetInfo == null) closeDialog() else NameDialog(
            title = "Rename preset",
            confirmLabel = "Rename",
            initial = targetInfo.name,
            existingNames = names,
            ownName = targetInfo.name,
            onSubmit = { n -> holder.renamePreset(targetInfo.key, n) },
            onDismiss = ::closeDialog,
        )
        PresetDialog.Delete -> if (targetInfo == null) closeDialog() else DeleteDialog(
            name = targetInfo.name,
            onConfirm = {
                holder.deletePreset(targetInfo.key)
                closeDialog()
            },
            onDismiss = ::closeDialog,
        )
    }
}

private enum class PresetDialog { None, SaveAs, Rename, Delete }

/** Save (overwrite the active user preset) and Save as new...; stacked at font scale 1.3 and up. */
@Composable
private fun HeaderActions(ui: EqUiState, holder: UiStateHolder, stacked: Boolean, onSaveAs: () -> Unit) {
    val activeIsUser = ui.activePresetKey?.startsWith(USER_KEY_PREFIX) == true
    val canSave = activeIsUser && ui.presetModified
    val saveDisabledState = if (activeIsUser) "Save, no changes to save" else "Save, choose Save as new to name this sound"
    val save: @Composable (Modifier) -> Unit = { m ->
        FilledTonalButton(
            onClick = { holder.savePreset() },
            enabled = canSave,
            modifier = m.heightIn(min = 48.dp).testTag("preset-save")
                .semantics { if (!canSave) stateDescription = saveDisabledState },
        ) { Text("Save") }
    }
    val saveAs: @Composable (Modifier) -> Unit = { m ->
        OutlinedButton(onClick = onSaveAs, modifier = m.heightIn(min = 48.dp).testTag("preset-save-as")) {
            Text("Save as new\u2026")
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            save(Modifier.fillMaxWidth())
            saveAs(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            save(Modifier.weight(1f))
            saveAs(Modifier.weight(1f))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun PresetRow(
    p: PresetInfo,
    ui: EqUiState,
    holder: UiStateHolder,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val active = ui.activePresetKey == p.key
    val edited = active && ui.presetModified
    val bg = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val desc = p.name + (if (active) ", active" else "") + (if (edited) ", edited" else "")
    val supportColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().background(bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 56.dp)
                .clickable(role = Role.Button) {
                    holder.applyPreset(p.key)
                    onDismiss()
                }
                .padding(start = 16.dp, end = if (p.builtin) 16.dp else 0.dp)
                .testTag("preset-row-${p.key}")
                .semantics(mergeDescendants = true) { contentDescription = desc },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (edited) Text("Edited", style = MaterialTheme.typography.labelSmall, color = supportColor)
            }
            if (active) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (!p.builtin) RowMenu(p, onRename, onDelete)
    }
}

@Composable
private fun RowMenu(p: PresetInfo, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menu = true },
            modifier = Modifier.size(48.dp).testTag("preset-menu-${p.key}")
                .semantics { contentDescription = "More options for ${p.name}" },
        ) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = null)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { menu = false; onRename() },
                modifier = Modifier.height(48.dp).testTag("preset-rename"),
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menu = false; onDelete() },
                modifier = Modifier.height(48.dp).testTag("preset-delete"),
            )
        }
    }
}
