package com.dylenq.eq.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylenq.eq.R
import com.dylenq.eq.service.SessionInfo
import com.dylenq.eq.service.SessionState

const val GLOBAL_MODE_TITLE = "Global mode (legacy)"
const val GLOBAL_MODE_SUPPORTING =
    "Applies the EQ to all audio on the phone and replaces the per-app effects. It uses a deprecated " +
        "Android feature and may not work on some phones. If the phone rejects it, EQ turns it off again."
const val START_ON_BOOT_TITLE = "Start when the phone turns on"
const val START_ON_BOOT_SUPPORTING =
    "Starts EQ in the background after the phone restarts, so it's ready when audio plays. " +
        "Some phones block this until you open EQ once. Off by default."
const val SESSIONS_EMPTY_TITLE = "No audio sessions detected"
const val SESSIONS_EMPTY_BODY = "Play something in another app. Some apps, such as browsers, may not be detected."

/**
 * PEQ-07: the Settings destination. Switches apply immediately; the global-mode notice (a rejection) is shown
 * here, where the switch is. Session package names come from other apps' broadcasts, so they are plain text:
 * no PackageManager label lookup and no click or link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    holder: UiStateHolder,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    spec: WindowSpec = WindowSpec.current(),
) {
    val runtime = holder.runtime
    val status by runtime.status.collectAsStateWithLifecycle()
    // Optimistic: flips on tap, then follows the runtime (a rejection changes globalMode and sets the notice).
    var pending by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(status.globalMode, status.notice) { pending = null }
    val globalOn = pending ?: status.globalMode
    var startOnBoot by remember { mutableStateOf(runtime.settings.startOnBoot) }
    val sessions = remember(status.sessions) { SessionCopy.sorted(status.sessions) }
    val context = LocalContext.current
    // The probe publishes status after it records the count, so keying on status refreshes this line.
    val engineBandsLine = remember(status) {
        runtime.settings.engineMaxBands?.let { "Engine bands: $it (measured)" } ?: "Engine bands: not measured yet"
    }
    val aboutLine = remember {
        val app = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }
        "Version $app · core ${holder.coreVersion()} · Free software under GPL-3.0"
    }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("settings-back")) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
                expandedHeight = spec.topBarHeightDp.dp,
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize().testTag("settings-list")) {
                if (status.notice != null) {
                    item(key = "notice") {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            NoticeBanner(status.notice ?: "", holder::dismissNotice)
                        }
                    }
                }
                item(key = "h-audio") { SectionHeader("Audio") }
                item(key = "global") {
                    SettingsSwitchRow(
                        tag = "global-mode",
                        title = GLOBAL_MODE_TITLE,
                        supporting = GLOBAL_MODE_SUPPORTING,
                        checked = globalOn,
                        onChange = { on ->
                            pending = on
                            runtime.setGlobalMode(on)
                        },
                    )
                }
                item(key = "boot") {
                    SettingsSwitchRow(
                        tag = "start-on-boot",
                        title = START_ON_BOOT_TITLE,
                        supporting = START_ON_BOOT_SUPPORTING,
                        checked = startOnBoot,
                        onChange = { on ->
                            startOnBoot = on
                            runtime.setStartOnBoot(on)
                        },
                    )
                }
                item(key = "h-sessions") {
                    SectionHeader(
                        "Detected audio sessions",
                        count = if (sessions.isEmpty()) null else SessionCopy.countText(sessions.size),
                    )
                }
                if (sessions.isEmpty()) {
                    item(key = "sessions-empty") { SessionsEmptyBlock() }
                } else {
                    items(sessions, key = { "s-${it.id}" }) { SessionRow(it) }
                }
                item(key = "h-about") { SectionHeader("About") }
                item(key = "engine-bands") { EngineBandsRow(engineBandsLine) }
                item(key = "about") { AboutRow(aboutLine) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (count != null) {
            Text(
                count,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("sessions-count").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(tag: String, title: String, supporting: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$tag-supporting"),
            )
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.testTag("$tag-switch"))
    }
}

@Composable
private fun SessionRow(info: SessionInfo) {
    val problem = info.state == SessionState.NO_CONTROL || info.state == SessionState.FAILED
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp).testTag("session-row-${info.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (info.state) {
            SessionState.OK -> Icon(painterResource(R.drawable.ic_check), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            SessionState.NO_CONTROL, SessionState.FAILED ->
                Icon(painterResource(R.drawable.ic_warning), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
            SessionState.SUPPRESSED -> Box(
                Modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape).clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 10.dp, height = 2.dp).border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
        Column(Modifier.weight(1f)) {
            // Plain text only: the package string is attacker-influenced (other apps' broadcasts).
            Text(SessionCopy.rowTitle(info.pkg), style = MaterialTheme.typography.bodyLarge)
            Text(
                SessionCopy.supporting(info),
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SessionsEmptyBlock() {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 12.dp).testTag("sessions-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(SESSIONS_EMPTY_TITLE, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(
            SESSIONS_EMPTY_BODY,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EngineBandsRow(line: String) {
    Text(
        line,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("engine-bands"),
    )
}

@Composable
private fun AboutRow(line: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp).testTag("about-row")) {
        Text("EQ for Android", style = MaterialTheme.typography.bodyLarge)
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
