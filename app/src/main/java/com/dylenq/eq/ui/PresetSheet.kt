package com.dylenq.eq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
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
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                item(key = "h-builtin") { SectionHeader("Built-in") }
                items(builtIns, key = { it.key }) { p -> PresetRow(p, ui, holder, onDismiss) }
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
                    items(userPresets, key = { it.key }) { p -> PresetRow(p, ui, holder, onDismiss) }
                }
            }
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
private fun PresetRow(p: PresetInfo, ui: EqUiState, holder: UiStateHolder, onDismiss: () -> Unit) {
    val active = ui.activePresetKey == p.key
    val edited = active && ui.presetModified
    val bg = if (active) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    val desc = p.name + (if (active) ", active" else "") + (if (edited) ", edited" else "")
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) {
                holder.applyPreset(p.key)
                onDismiss()
            }
            .padding(horizontal = 16.dp)
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
            if (edited) {
                Text(
                    "Edited",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
}
