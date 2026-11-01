package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Shown instead of a crash when the audio core cannot start (the rest of the app depends on it). */
@Composable
fun StartupErrorScreen(reason: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize().testTag("startup-error")) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("EQ could not start", style = MaterialTheme.typography.headlineSmall)
            Text(
                "The audio engine failed to load, so the equalizer is off and other apps play normally. " +
                    "Reinstalling the app may help. This build may not support this phone's processor.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(reason, style = MaterialTheme.typography.bodySmall)
        }
    }
}
