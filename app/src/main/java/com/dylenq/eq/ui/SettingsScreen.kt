package com.dylenq.eq.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dylenq.eq.R
import com.dylenq.eq.core.PresetInfo
import com.dylenq.eq.service.AppEntry
import com.dylenq.eq.service.SessionInfo
import com.dylenq.eq.service.SessionState
import com.dylenq.eq.service.TrackingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * here, where the switch is. Session and app package names come from other apps' broadcasts, so they are plain
 * text with no click or link; app labels come from the PackageManager and are plain text too.
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
    var trackingOn by remember { mutableStateOf(runtime.settings.playerTracking) }
    val needsPermission = status.tracking == TrackingState.NeedsPermission
    // A grant (ADB or Shizuku) takes effect with no restart: look again on resume and every 2 s while the setup is shown.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { runtime.recheckTracking() }
    LaunchedEffect(needsPermission) {
        while (needsPermission) {
            delay(TRACKING_POLL_MS)
            runtime.recheckTracking()
        }
    }
    val context = LocalContext.current
    val ui by holder.ui.collectAsStateWithLifecycle()
    val presetNames = remember(ui.presets) { ui.presets.associate { it.key to it.name } }
    val labels = remember(context) {
        AppLabels { pkg -> context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString() }
    }
    val sessions = remember(status.sessions) { SessionCopy.sorted(status.sessions) }
    val apps = remember(status.apps) { AppsCopy.sorted(status.apps) { labels.label(it) } }
    val engineBandsLine = status.engineMaxBands?.let { "Engine bands: $it (measured)" } ?: "Engine bands: not measured yet"
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
            // The notice is a new first item. A list longer than the screen keeps its current rows in place, so
            // the notice would land above the screen and a rejected switch would just flip back with no reason.
            val listState = rememberLazyListState()
            val notice = status.notice
            LaunchedEffect(notice) { if (notice != null) listState.scrollToItem(0) }
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize().testTag("settings-list"), state = listState) {
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
                item(key = "h-tracking") { SectionHeader("Advanced player tracking") }
                item(key = "tracking") {
                    TrackingSection(
                        checked = trackingOn,
                        state = status.tracking,
                        packageName = context.packageName,
                        onChange = { on ->
                            trackingOn = on
                            runtime.setPlayerTracking(on)
                        },
                        onShizuku = holder::grantWithShizuku,
                    )
                }
                item(key = "h-apps") {
                    SectionHeader("Apps", count = if (apps.isEmpty()) null else AppsCopy.countText(apps.size), countTag = "apps-count")
                }
                item(key = "apps-notes") { AppsNotes(globalOn = status.globalMode) }
                if (apps.isEmpty()) {
                    item(key = "apps-empty") { AppsEmptyBlock() }
                } else {
                    items(apps, key = { "a-${it.pkg}" }) { app ->
                        AppRow(
                            app = app,
                            title = labels.label(app.pkg),
                            presets = ui.presets,
                            presetText = AppsCopy.presetText(app.presetKey, presetNames),
                            onEnabled = { on -> runtime.setAppEnabled(app.pkg, on) },
                            onPreset = { key -> runtime.setAppPreset(app.pkg, key) },
                        )
                    }
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
                    items(sessions, key = { "s-${it.id}" }) { info ->
                        val key = status.apps.firstOrNull { it.pkg == info.pkg }?.presetKey
                        SessionRow(info, key?.let { presetNames[it] })
                    }
                }
                item(key = "h-about") { SectionHeader("About") }
                item(key = "engine-bands") { EngineBandsRow(engineBandsLine) }
                item(key = "about") { AboutRow(aboutLine) }
            }
        }
    }
}

const val TRACKING_POLL_MS = 2000L

@Composable
private fun TrackingSection(
    checked: Boolean,
    state: TrackingState,
    packageName: String,
    onChange: (Boolean) -> Unit,
    onShizuku: suspend () -> String,
) {
    Column(Modifier.testTag("player-tracking")) {
        SettingsSwitchRow(
            tag = "player-tracking-row",
            title = TrackingCopy.TITLE,
            supporting = TrackingCopy.SUPPORTING,
            checked = checked,
            onChange = onChange,
        )
        Text(
            TrackingCopy.statusLine(state),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == TrackingState.NeedsReboot || state is TrackingState.Unsupported) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("tracking-status")
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state == TrackingState.NeedsPermission) NeedsPermissionBlock(packageName, onShizuku)
    }
}

@Composable
private fun NeedsPermissionBlock(packageName: String, onShizuku: suspend () -> String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val command = remember(packageName) { TrackingCopy.adbCommand(packageName) }
    var copied by remember { mutableStateOf(false) }
    var shizukuMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(TrackingCopy.WHY, style = MaterialTheme.typography.bodyMedium)
        Text(TrackingCopy.NEEDS_COMPUTER, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(
                command,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth().testTag("tracking-adb-command"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("EQ permission command", command))
                    copied = true
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tracking-copy"),
            ) { Text(if (copied) TrackingCopy.COPIED else TrackingCopy.COPY_LABEL) }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    shizukuMessage = null
                    scope.launch {
                        try {
                            shizukuMessage = onShizuku()
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.heightIn(min = 48.dp).testTag("tracking-shizuku"),
            ) { Text(TrackingCopy.SHIZUKU_LABEL) }
        }
        shizukuMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("tracking-shizuku-message").semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: String? = null, countTag: String = "sessions-count") {
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
                modifier = Modifier.testTag(countTag).semantics { liveRegion = LiveRegionMode.Polite },
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
private fun SessionRow(info: SessionInfo, presetName: String? = null) {
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
            SessionState.SUPPRESSED, SessionState.DISABLED -> Box(
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
                SessionCopy.supporting(info, presetName),
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppsNotes(globalOn: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("apps-notes")) {
        Text(AppsCopy.FOLLOW_NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (globalOn) {
            Text(
                AppsCopy.GLOBAL_NOTE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("apps-global-note"),
            )
        }
    }
}

@Composable
private fun AppsEmptyBlock() {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 16.dp, vertical = 12.dp).testTag("apps-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(AppsCopy.EMPTY_TITLE, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Text(
            AppsCopy.EMPTY_BODY,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** App name and package are plain text (other apps' broadcasts), never a link or action. */
@Composable
private fun AppRow(
    app: AppEntry,
    title: String,
    presets: List<PresetInfo>,
    presetText: String,
    onEnabled: (Boolean) -> Unit,
    onPreset: (String?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("app-row-${app.pkg}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(AppsCopy.title(title), style = MaterialTheme.typography.bodyLarge)
                if (title != app.pkg) {
                    Text(app.pkg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (app.playingNow) {
                    Text("Playing now", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(
                Modifier
                    .size(48.dp)
                    .testTag("app-switch-${app.pkg}")
                    .toggleable(value = app.enabled, role = Role.Switch, onValueChange = onEnabled)
                    .semantics { stateDescription = if (app.enabled) "On" else "Off" },
                contentAlignment = Alignment.Center,
            ) {
                Switch(checked = app.enabled, onCheckedChange = null)
            }
        }
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                modifier = Modifier.heightIn(min = 48.dp).testTag("app-preset-${app.pkg}"),
            ) { Text(presetText) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(AppsCopy.DEFAULT_PRESET) },
                    onClick = { menuOpen = false; onPreset(null) },
                    modifier = Modifier.testTag("app-preset-${app.pkg}-live"),
                )
                presets.forEach { p ->
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { menuOpen = false; onPreset(p.key) },
                        modifier = Modifier.testTag("app-preset-${app.pkg}-${p.key}"),
                    )
                }
            }
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
