package com.dylenq.eq.ui

import com.dylenq.eq.service.TrackingState
import com.dylenq.eq.shizuku.GrantResult

/** Text for the "Advanced player tracking" section of Settings. Plain language, pure, no Android types. */
object TrackingCopy {
    const val TITLE = "Track apps that don't announce themselves"
    const val SUPPORTING =
        "Finds players that never tell EQ they are playing, so the equalizer can reach them too. " +
            "It needs one permission that Android only lets you grant from a computer or with Shizuku."
    const val WHY = "Android keeps this permission out of the normal prompts. EQ only uses it to read which apps are playing sound."
    const val NEEDS_COMPUTER = "Setup needs a computer once. Connect the phone with USB debugging on, then run the command."
    const val COPY_LABEL = "Copy"
    const val COPIED = "Copied"
    const val SHIZUKU_LABEL = "Use Shizuku"
    const val REBOOT = "Reboot the phone once."

    fun statusLine(state: TrackingState): String = when (state) {
        TrackingState.Off -> "Off"
        TrackingState.NeedsPermission -> "Needs a permission before it can work."
        is TrackingState.Working ->
            if (state.count == 1) "Working. Watching 1 app that is playing." else "Working. Watching ${state.count} apps that are playing."
        TrackingState.NeedsReboot -> REBOOT
        is TrackingState.Unsupported -> "Not supported on this phone: ${state.reason}"
    }

    fun adbCommand(packageName: String): String = "adb shell pm grant $packageName android.permission.DUMP"

    fun shizukuMessage(result: GrantResult): String = when (result) {
        GrantResult.Granted -> "Done. Tracking will start in a moment."
        is GrantResult.Failed -> if (result.output.isBlank()) "Shizuku could not grant the permission." else "Shizuku could not grant the permission: ${result.output}"
        GrantResult.NotInstalled -> "Shizuku is not installed. Install the Shizuku app, or use the command above."
        GrantResult.NotRunning -> "Shizuku is not running. Open the Shizuku app and start it, then try again."
        GrantResult.Refused -> "Shizuku refused. Allow EQ in the Shizuku app and try again."
    }
}
