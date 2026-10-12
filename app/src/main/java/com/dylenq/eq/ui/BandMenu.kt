package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dylenq.eq.R
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.FilterKind

/** Sentence-case name of a filter kind, shown in the menu and spoken by TalkBack. */
internal fun FilterKind.label(): String = when (this) {
    FilterKind.PEAK -> "Peak"
    FilterKind.LOW_SHELF -> "Low shelf"
    FilterKind.HIGH_SHELF -> "High shelf"
    FilterKind.LOW_PASS -> "Low pass"
    FilterKind.HIGH_PASS -> "High pass"
}

private val MENU_KINDS = listOf(
    FilterKind.PEAK, FilterKind.LOW_SHELF, FilterKind.HIGH_SHELF, FilterKind.LOW_PASS, FilterKind.HIGH_PASS,
)

private fun FilterKind.tagName(): String = name.lowercase()

/**
 * The long-press menu of a node (PEQ-04): the five filter kinds with a check on the current one, a
 * divider, Disable/Enable and Delete band. Every choice closes the menu through [onDismiss]; dismissing
 * on its own changes nothing.
 */
@Composable
fun BandMenu(
    band: Band,
    expanded: Boolean,
    onKind: (FilterKind) -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("peq-menu"),
        ) {
            for (kind in MENU_KINDS) {
                DropdownMenuItem(
                    text = { Text(kind.label()) },
                    leadingIcon = if (kind == band.kind) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.testTag("peq-menu-check")) }
                    } else {
                        // Same width as the check so every label starts at the same x.
                        { Spacer(Modifier.size(24.dp)) }
                    },
                    onClick = {
                        onDismiss()
                        if (kind != band.kind) onKind(kind)
                    },
                    modifier = Modifier.testTag("peq-menu-kind-${kind.tagName()}"),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (band.enabled) "Disable" else "Enable") },
                leadingIcon = { Spacer(Modifier.size(24.dp)) },
                onClick = {
                    onDismiss()
                    onToggleEnabled()
                },
                modifier = Modifier.testTag("peq-menu-enable"),
            )
            DropdownMenuItem(
                text = { Text("Delete band", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Spacer(Modifier.size(24.dp)) },
                onClick = {
                    onDismiss()
                    onDelete()
                },
                modifier = Modifier.testTag("peq-menu-delete"),
            )
        }
    }
}
