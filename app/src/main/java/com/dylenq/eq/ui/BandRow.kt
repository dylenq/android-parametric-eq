package com.dylenq.eq.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.dylenq.eq.R
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.FilterKind

private val KINDS = listOf(
    FilterKind.PEAK, FilterKind.LOW_SHELF, FilterKind.HIGH_SHELF, FilterKind.LOW_PASS, FilterKind.HIGH_PASS,
)

/** "63 Hz · +5.0 dB · Q 0.71"; pass kinds omit the gain: "8 kHz · Q 0.71". */
internal fun bandSummary(band: Band): String = buildString {
    append(FreqFormat.withUnit(band.freq))
    if (band.kind.hasGain()) append(" · ").append(GainFormat.format(band.gainDb))
    append(" · Q ").append(NumericField.formatQ(band.q))
}

/**
 * One accordion row (UI-SPEC "Band List"). The collapsed header is always shown; the selected band
 * expands in place with the kind selector, the [fields] slot and the Delete / sign actions.
 * The rows of the list share one rounded card: [first] and [last] pick the corners.
 */
@Composable
internal fun BandRow(
    index: Int,
    band: Band,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    actions: BandListActions,
    fields: @Composable (Int, Band) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val container = if (selected) cs.surfaceContainerHigh else cs.surfaceContainerLow
    val r = 12.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    val reduced = rememberReducedMotion()
    val ms = if (reduced) 0 else ACCORDION_MS
    Column(modifier.fillMaxWidth().clip(shape).background(container)) {
        if (!first) HorizontalDivider(Modifier.padding(start = 56.dp), color = cs.outlineVariant)
        BandHeader(index, band, selected, container, actions)
        AnimatedVisibility(
            visible = selected,
            enter = expandVertically(tween(ms)),
            exit = shrinkVertically(tween(ms)),
        ) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag("band-expanded-$index")) {
                KindSelector(index, band, actions.onSetKind)
                Spacer(Modifier.height(8.dp))
                fields(index, band)
                BandActions(index, band, actions)
            }
        }
    }
}

@Composable
private fun BandHeader(index: Int, band: Band, selected: Boolean, container: androidx.compose.ui.graphics.Color, actions: BandListActions) {
    val spoken = "Band ${index + 1}, ${band.kind.label()}, ${NodeSpeech.stateDescription(band, selected = false)}"
    val toggle = { actions.onSelect(if (selected) null else index) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(onClick = toggle)
                .clearAndSetSemantics {
                    testTag = "band-row-$index"
                    contentDescription = spoken
                    stateDescription = if (selected) "Expanded" else "Collapsed"
                    role = Role.Button
                    onClick(label = if (selected) "Collapse" else "Expand") { toggle(); true }
                }
                ,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BandMarker(index, band, selected, container)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp).alpha(if (band.enabled) 1f else 0.6f)) {
                Text(band.kind.label(), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                Text(
                    bandSummary(band),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = band.enabled,
            onCheckedChange = { actions.onSetEnabled(index, it) },
            modifier = Modifier
                .semantics { contentDescription = "Band ${index + 1} enabled" }
                .testTag("band-switch-$index"),
        )
    }
}

/** The node glyph of the plot at 32 dp with the band number centred (hollow when disabled). */
@Composable
private fun BandMarker(index: Int, band: Band, selected: Boolean, container: androidx.compose.ui.graphics.Color) {
    val cs = MaterialTheme.colorScheme
    val spec = NodeGlyphs.spec(band.kind, NodeVisualState(selected = selected, enabled = band.enabled))
    // The glyph is 1.1x the plot glyph so the digit fits; the tail stays 8 dp long. A tail pushes the
    // glyph away from it so the whole mark stays inside the 32 dp cell.
    val scale = 1.1f
    val shift = when (spec.tail) {
        NodeTail.LEFT -> 4f
        NodeTail.RIGHT -> -4f
        NodeTail.NONE -> 0f
    }
    val colors = NodeGlyphColors(cs.primary, cs.outline, container)
    val number = (index + 1).toString()
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(32.dp)) {
            val centre = Offset(size.width / 2f + shift * density, size.height / 2f)
            drawNodeGlyph(centre, spec, colors, dpPx = density * scale, tailLengthDp = 8f / scale)
        }
        Text(
            number,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            color = if (band.enabled) cs.onPrimary else cs.onSurfaceVariant,
            modifier = Modifier.padding(start = (shift).coerceAtLeast(0f).dp, end = (-shift).coerceAtLeast(0f).dp),
        )
    }
}

@Composable
private fun KindSelector(index: Int, band: Band, onKind: (Int, FilterKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(MaterialTheme.shapes.small)
                .border(1.dp, cs.outline, MaterialTheme.shapes.small)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(start = 16.dp, end = 8.dp)
                .semantics(mergeDescendants = true) { contentDescription = "Band ${index + 1} filter type, ${band.kind.label()}" }
                .testTag("band-kind-$index"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(band.kind.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = cs.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("band-kind-menu-$index")) {
            for (kind in KINDS) {
                DropdownMenuItem(
                    text = { Text(kind.label()) },
                    leadingIcon = if (kind == band.kind) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                    } else {
                        { Spacer(Modifier.size(24.dp)) }
                    },
                    onClick = {
                        open = false
                        if (kind != band.kind) onKind(index, kind)
                    },
                    modifier = Modifier.testTag("band-kind-item-${kind.name.lowercase()}"),
                )
            }
        }
    }
}

@Composable
private fun BandActions(index: Int, band: Band, actions: BandListActions) {
    val focus: FocusManager = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { actions.onDelete(index) },
            modifier = Modifier.testTag("band-delete-$index"),
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Delete band") }
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = {
                // A focused gain field would otherwise keep showing the old sign.
                focus.clearFocus()
                actions.onFlipSign(index)
            },
            enabled = band.kind.hasGain(),
            modifier = Modifier.testTag("band-sign-$index").semantics { contentDescription = "Change sign of gain" },
        ) { Text("±", style = MaterialTheme.typography.titleMedium) }
    }
}
