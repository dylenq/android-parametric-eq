package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val PARAMETRIC_TITLE = "Parametric editing isn't available yet"
const val PARAMETRIC_BODY = "The curve above shows your current parametric bands. Switch back to Graphic to shape the sound."
const val PARAMETRIC_BODY_EMPTY = "There are no parametric bands yet, so the sound is unchanged. Switch back to Graphic to shape it."

/** Phase 4 stand-in for the Phase 5 band list; the curve above stays live. No button. */
@Composable
fun ParametricPlaceholder(hasEnabledBands: Boolean, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("parametric-placeholder"),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(PARAMETRIC_TITLE, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("parametric-title"))
            Text(
                if (hasEnabledBands) PARAMETRIC_BODY else PARAMETRIC_BODY_EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp).testTag("parametric-body"),
            )
        }
    }
}
