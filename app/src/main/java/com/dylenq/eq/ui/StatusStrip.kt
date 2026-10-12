package com.dylenq.eq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Colour role of the strip's leading dot. */
enum class DotRole { ERROR, OUTLINE, PRIMARY }

/** What [StatusStrip] shows. [hint] is the second line of the "waiting" state. */
data class StatusInfo(
    val text: String,
    val dot: DotRole,
    val showStart: Boolean = false,
    val hint: String? = null,
)

const val WAITING_HINT = "Some apps, such as browsers, may not be detected."

/** UI-SPEC "StatusStrip": the first matching row wins. Pure, so the priority is unit tested. */
fun statusFor(serviceRunning: Boolean, bypass: Boolean, globalMode: Boolean, sessionCount: Int): StatusInfo = when {
    !serviceRunning -> StatusInfo("EQ is not running", DotRole.ERROR, showStart = true)
    bypass -> StatusInfo("Bypassed. Audio plays unchanged.", DotRole.OUTLINE)
    globalMode -> StatusInfo("Equalizing all audio", DotRole.PRIMARY)
    sessionCount <= 0 -> StatusInfo("Waiting for audio. Play something in another app.", DotRole.OUTLINE, hint = WAITING_HINT)
    sessionCount == 1 -> StatusInfo("Equalizing 1 app", DotRole.PRIMARY)
    else -> StatusInfo("Equalizing $sessionCount apps", DotRole.PRIMARY)
}

/**
 * One 40 dp line (a second line only in the waiting state, only when [showHint]): 8 dp dot, text, and
 * "Start" when the service is not running. A polite live region so TalkBack announces changes.
 */
@Composable
fun StatusStrip(
    state: () -> EqUiState,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    showHint: Boolean = true,
) {
    val s = state()
    val info = statusFor(s.serviceRunning, s.bypass, s.globalMode, s.sessionCount)
    val dotColor = when (info.dot) {
        DotRole.ERROR -> MaterialTheme.colorScheme.error
        DotRole.OUTLINE -> MaterialTheme.colorScheme.outline
        DotRole.PRIMARY -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("status-strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.size(8.dp).clip(CircleShape).background(dotColor).testTag("status-dot"))
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(info.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("status-text"))
            if (showHint && info.hint != null) {
                Text(
                    info.hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("status-hint"),
                )
            }
        }
        if (info.showStart) {
            TextButton(onClick = onStart, modifier = Modifier.testTag("status-start")) { Text("Start") }
        }
    }
}
