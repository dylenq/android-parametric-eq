package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dylenq.eq.R
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.FilterKind

const val BAND_LIST_EMPTY_TITLE = "No bands yet"
const val BAND_LIST_EMPTY_BODY = "Double-tap the curve to add a band, or use Add band. Each band shapes one part of the sound."
const val BAND_LIST_HINT_WITH_PINCH = "Drag a point to move it. Pinch to change Q. Long-press for more."
const val BAND_LIST_HINT = "Drag a point to move it. Long-press for more."
const val BAND_LIST_FULL = "All 16 bands are in use. Remove one to add another."

/** Everything the band list can do, as plain lambdas so the composables never see the holder. */
class BandListActions(
    val onSelect: (Int?) -> Unit,
    val onSetKind: (Int, FilterKind) -> Unit,
    val onSetEnabled: (Int, Boolean) -> Unit,
    val onDelete: (Int) -> Unit,
    val onAddBand: () -> Unit,
    val onRemoveAll: () -> Unit,
    val onFlipSign: (Int) -> Unit = {},
    val onFieldFocus: (Int) -> Unit = {},
    val onFieldChange: (index: Int, freqHz: Float?, gainDb: Float?, q: Float?) -> Unit = { _, _, _, _ -> },
    val onFieldBlur: () -> Unit = {},
)

/** The real wiring of [BandListActions] to the holder. */
fun UiStateHolder.bandListActions() = BandListActions(
    onSelect = ::selectBand,
    onSetKind = ::setBandKind,
    onSetEnabled = ::setBandEnabled,
    onDelete = ::removeBand,
    onAddBand = ::addBandFromList,
    onRemoveAll = ::removeAllBands,
    onFlipSign = ::flipBandGainSign,
    onFieldFocus = ::bandFieldFocusGained,
    onFieldChange = ::setBandFromField,
    onFieldBlur = ::bandFieldFocusLost,
)

internal const val BAND_LIST_FIXED_ITEMS = 2

/**
 * The accordion band list (PEQ-05, UI-SPEC "Band List"): header, hint or empty state, one collapsed
 * 56 dp row per band with only the selected band expanded in place, and an optional [footer] item
 * (plan 08 passes the preamp card). Selection lives in [EqUiState.selectedBand], so the curve and the
 * list stay in sync; tapping a node selects, expands and scrolls the row into view.
 */
@Composable
fun ParametricBandList(
    state: () -> EqUiState,
    actions: BandListActions,
    pinchHintSeen: Boolean,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    fields: @Composable (index: Int, band: Band) -> Unit = { i, b -> BandFieldRow(i, b, actions) },
) {
    val ui = state()
    val bands = ui.bands
    val selected = ui.selectedBand?.takeIf { it in bands.indices }
    val listState = rememberLazyListState()
    val reduced = rememberReducedMotion()
    val bandsSize = bands.size

    // Bring the selected row into view once its accordion animation has finished.
    val sizeNow = rememberUpdatedState(bandsSize)
    LaunchedEffect(selected, reduced) {
        val sel = selected ?: return@LaunchedEffect
        if (!reduced) {
            // Frame-clock based (not delay) so it follows the animation clock, including in tests.
            val start = withFrameNanos { it }
            while (withFrameNanos { it } - start < (ACCORDION_MS + 20) * 1_000_000L) { /* wait for the accordion */ }
        }
        if (sel >= sizeNow.value) return@LaunchedEffect
        val target = BAND_LIST_FIXED_ITEMS + sel
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        val fullyVisible = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) {
            if (reduced) listState.scrollToItem(target) else listState.animateScrollToItem(target)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.testTag("band-list"),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "header") {
            BandListHeader(count = bandsSize, onAdd = actions.onAddBand, onRemoveAll = actions.onRemoveAll)
        }
        item(key = "hint") {
            if (bandsSize == 0) {
                EmptyState(actions.onAddBand)
            } else {
                val text = when {
                    bandsSize >= ParametricMath.MAX_BANDS -> BAND_LIST_FULL
                    pinchHintSeen -> BAND_LIST_HINT
                    else -> BAND_LIST_HINT_WITH_PINCH
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp)
                        .padding(bottom = 8.dp)
                        .testTag("band-list-hint"),
                )
            }
        }
        itemsIndexed(bands, key = { i, _ -> "band-$i" }) { i, band ->
            BandRow(
                index = i,
                band = band,
                selected = i == selected,
                first = i == 0,
                last = i == bandsSize - 1,
                actions = actions,
                fields = fields,
            )
        }
        if (footer != null) {
            item(key = "footer") {
                Column(Modifier.padding(top = 16.dp)) { footer() }
            }
        }
    }
}

internal const val ACCORDION_MS = 200

@Composable
private fun BandListHeader(count: Int, onAdd: () -> Unit, onRemoveAll: () -> Unit) {
    val stacked = LocalDensity.current.fontScale >= 1.3f
    val title: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("Bands", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.size(8.dp))
            Text(
                "$count of ${ParametricMath.MAX_BANDS}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("band-count"),
            )
        }
    }
    val actions: @Composable () -> Unit = {
        IconButton(onClick = onRemoveAll, enabled = count > 0, modifier = Modifier.testTag("band-remove-all")) {
            Icon(painterResource(R.drawable.ic_delete_sweep), contentDescription = "Remove all bands")
        }
        if (count > 0) {
            FilledTonalButton(
                onClick = onAdd,
                enabled = count < ParametricMath.MAX_BANDS,
                modifier = Modifier.height(48.dp).testTag("band-add"),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add band")
            }
        }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth()) {
            title()
            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { title() }
            actions()
        }
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("band-list-empty"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(BAND_LIST_EMPTY_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(
                BAND_LIST_EMPTY_BODY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            FilledTonalButton(
                onClick = onAdd,
                modifier = Modifier.padding(top = 12.dp).height(48.dp).testTag("band-add-empty"),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add band")
            }
        }
    }
}
