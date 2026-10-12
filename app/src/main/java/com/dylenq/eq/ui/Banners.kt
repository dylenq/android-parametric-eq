package com.dylenq.eq.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dylenq.eq.R

/** Which banner is showing; priority is the declaration order. */
enum class BannerKind { NOTICE, RATIONALE, NOTIFICATIONS_OFF }

/**
 * UI-SPEC "BannerSlot" priority: a runtime notice wins; then, on API 33+ without the permission, the
 * rationale until answered, then the "notifications are off" hint once per launch. Below API 33 there is
 * never a rationale or off banner. Pure, so the permission flow is unit tested.
 */
fun selectBanner(
    notice: String?,
    sdkInt: Int,
    permissionGranted: Boolean,
    rationaleAnswered: Boolean,
    offHintDismissed: Boolean,
): BannerKind? = when {
    notice != null -> BannerKind.NOTICE
    sdkInt < 33 || permissionGranted -> null
    !rationaleAnswered -> BannerKind.RATIONALE
    !offHintDismissed -> BannerKind.NOTIFICATIONS_OFF
    else -> null
}

const val RATIONALE_TITLE = "Show a notification?"
const val RATIONALE_BODY =
    "EQ runs in the background while other apps play. Android needs a notification so it stays running, " +
        "and it lets you switch the EQ off from there."
const val NOTIFICATIONS_OFF_TEXT =
    "Notifications are off. EQ still works, but you can't switch it off from the notification."

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The single banner region. [notice] is shown verbatim as plain text (no links or spans). The permission
 * launcher ([onAllow]) is only ever called from the rationale banner's "Allow".
 */
@Composable
fun BannerSlot(
    notice: String?,
    permissionGranted: Boolean,
    prefs: UiPrefs,
    onDismissNotice: () -> Unit,
    onAllow: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    var answered by remember(prefs) { mutableStateOf(prefs.rationaleAnswered) }
    var offDismissed by remember(prefs) { mutableStateOf(prefs.offHintDismissed) }
    val kind = selectBanner(notice, sdkInt, permissionGranted, answered, offDismissed)
    val reduced = rememberReducedMotion()
    // Keep the last banner on screen while it collapses.
    var last by remember { mutableStateOf<BannerKind?>(null) }
    if (kind != null) last = kind
    val shown = kind ?: last

    AnimatedVisibility(
        visible = kind != null,
        modifier = modifier,
        enter = if (reduced) expandVertically(snap()) + fadeIn(snap()) else expandVertically(tween(250, easing = Emphasized)) + fadeIn(tween(250)),
        exit = if (reduced) shrinkVertically(snap()) + fadeOut(snap()) else shrinkVertically(tween(250, easing = Emphasized)) + fadeOut(tween(250)),
    ) {
        when (shown) {
            BannerKind.NOTICE -> Banner(
                tag = "banner-notice",
                container = MaterialTheme.colorScheme.tertiaryContainer,
                content = MaterialTheme.colorScheme.onTertiaryContainer,
                icon = R.drawable.ic_info,
                title = null,
                text = notice ?: "",
                actions = { TextButton(onClick = onDismissNotice, modifier = Modifier.testTag("banner-dismiss")) { Text("Dismiss") } },
            )
            BannerKind.RATIONALE -> Banner(
                tag = "banner-rationale",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                icon = R.drawable.ic_info,
                title = RATIONALE_TITLE,
                text = RATIONALE_BODY,
                actions = {
                    TextButton(
                        onClick = {
                            prefs.rationaleAnswered = true
                            answered = true
                        },
                        modifier = Modifier.testTag("rationale-not-now"),
                    ) { Text("Not now") }
                    TextButton(
                        onClick = {
                            prefs.rationaleAnswered = true
                            answered = true
                            onAllow()
                        },
                        modifier = Modifier.testTag("rationale-allow"),
                    ) { Text("Allow") }
                },
            )
            BannerKind.NOTIFICATIONS_OFF -> Banner(
                tag = "banner-notifications-off",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                icon = R.drawable.ic_notifications_off,
                title = null,
                text = NOTIFICATIONS_OFF_TEXT,
                actions = {
                    TextButton(onClick = onOpenNotificationSettings, modifier = Modifier.testTag("off-open-settings")) { Text("Open settings") }
                    TextButton(
                        onClick = {
                            prefs.offHintDismissed = true
                            offDismissed = true
                        },
                        modifier = Modifier.testTag("off-dismiss"),
                    ) { Text("Dismiss") }
                },
            )
            null -> {}
        }
    }
}

@Composable
private fun Banner(
    tag: String,
    container: Color,
    content: Color,
    icon: Int,
    title: String?,
    text: String,
    actions: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(bottom = 8.dp).testTag(tag),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("banner-title"))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("banner-text"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
    }
}
