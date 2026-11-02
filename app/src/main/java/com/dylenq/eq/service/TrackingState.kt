package com.dylenq.eq.service

/** One row of the audio server's session table. [name] is null when the column was blank. */
data class SessionRow(val session: Int, val pid: Int, val uid: Int, val name: String?)

/** Outcome of reading the table. Every failure is a value, never a throw. */
sealed interface DumpResult {
    data class Sessions(val rows: List<SessionRow>) : DumpResult
    /** The audio server refused the dump (permission). */
    data object Denied : DumpResult
    data class Unavailable(val reason: String) : DumpResult
}

/** What the Settings section shows for advanced player tracking. */
sealed interface TrackingState {
    data object Off : TrackingState
    data object NeedsPermission : TrackingState
    data class Working(val count: Int) : TrackingState
    data object NeedsReboot : TrackingState
    data class Unsupported(val reason: String) : TrackingState
}

/**
 * Pure mapping from the switch, the permission and the latest scan. A denial while the permission
 * is granted means the audio server cached an earlier refusal, so the phone needs one reboot.
 * No scan yet counts as working with no sessions.
 */
fun trackingStateFor(enabled: Boolean, permissionGranted: Boolean, lastResult: DumpResult?): TrackingState = when {
    !enabled -> TrackingState.Off
    !permissionGranted -> TrackingState.NeedsPermission
    lastResult == null -> TrackingState.Working(0)
    lastResult is DumpResult.Sessions -> TrackingState.Working(lastResult.rows.size)
    lastResult is DumpResult.Denied -> TrackingState.NeedsReboot
    lastResult is DumpResult.Unavailable -> TrackingState.Unsupported(lastResult.reason)
    else -> TrackingState.Off
}
