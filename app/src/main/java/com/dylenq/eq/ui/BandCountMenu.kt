package com.dylenq.eq.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dylenq.eq.R

/** The graphic band counts the core supports, in menu order. */
val BAND_COUNTS: List<Int> = listOf(5, 10, 15, 31)

private fun label(n: Int) = "$n bands"

/** UI-SPEC "Band-count picker": 112 dp x 40 dp outlined button (48 dp target) with a four-item menu. */
@Composable
fun BandCountMenu(count: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { open = true },
        modifier = modifier
            .widthIn(min = 112.dp)
            .testTag("band-count-button")
            .semantics { contentDescription = "Number of bands, $count" },
    ) {
        Text(label(count))
        Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null)
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (n in BAND_COUNTS) {
            DropdownMenuItem(
                text = { Text(label(n)) },
                leadingIcon = if (n == count) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.testTag("band-count-check")) }
                } else {
                    null
                },
                onClick = {
                    open = false
                    if (n != count) onSelect(n)
                },
                modifier = Modifier.testTag("band-count-item-$n"),
            )
        }
    }
}
