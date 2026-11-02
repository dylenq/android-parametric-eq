package com.dylenq.eq.service

/**
 * SUPPRESSED: tracked while global mode is on, no per-session effect attached.
 * DISABLED: the user switched this app off, so no effect is attached.
 */
enum class SessionState { OK, NO_CONTROL, FAILED, SUPPRESSED, DISABLED }

/** One per-app rule merged with what is playing now. [name] is the plain package; the UI looks up the label. */
data class AppEntry(
    val pkg: String,
    val enabled: Boolean,
    val presetKey: String?,
    val name: String,
    val playingNow: Boolean,
    val state: SessionState?,
)

/** [tracked]: found by advanced player tracking instead of the app's own broadcast. */
data class SessionInfo(val id: Int, val pkg: String, val state: SessionState, val tracked: Boolean = false)

/** Immutable snapshot published by the runtime. Notice carries store and global-mode messages. */
data class ServiceStatus(
    val sessionCount: Int = 0,
    val bypass: Boolean = false,
    val globalMode: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val notice: String? = null,
    /** True between EqService attach and detach. */
    val serviceRunning: Boolean = false,
    /** Measured engine band count for this OS build, or null when not measured yet. */
    val engineMaxBands: Int? = null,
    /** State of advanced player tracking (Settings). */
    val tracking: TrackingState = TrackingState.Off,
    /** Every per-app rule, in the core's order (sorting is the UI's job). */
    val apps: List<AppEntry> = emptyList(),
)
