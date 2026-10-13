package com.dylenq.eq.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * The single activity's two destinations: the equalizer and Settings. Settings is a `rememberSaveable` flag
 * (no navigation library, no deep link), closed by the top-bar arrow or system / predictive back, and it
 * survives rotation. The main screen's saveable state (scroll, selection, field text) is parked in a
 * [androidx.compose.runtime.saveable.SaveableStateHolder] while Settings is showing, so the round trip
 * returns to the same screen.
 */
@Composable
fun EqRoot(
    holder: UiStateHolder,
    prefs: UiPrefs,
    notificationsGranted: Boolean,
    onStartService: () -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
    spec: WindowSpec = WindowSpec.current(),
) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    val saveable = rememberSaveableStateHolder()
    val reduced = rememberReducedMotion()
    Crossfade(
        targetState = settingsOpen,
        animationSpec = if (reduced) snap() else tween(200),
        label = "destination",
        modifier = modifier,
    ) { settings ->
        if (settings) {
            saveable.SaveableStateProvider("settings") {
                SettingsScreen(holder = holder, onBack = { settingsOpen = false }, spec = spec)
            }
        } else {
            saveable.SaveableStateProvider("main") {
                EqScreen(
                    holder = holder,
                    prefs = prefs,
                    notificationsGranted = notificationsGranted,
                    onStartService = onStartService,
                    onAllowNotifications = onAllowNotifications,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onOpenSettings = { settingsOpen = true },
                    spec = spec,
                )
            }
        }
    }
}
