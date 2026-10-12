package com.dylenq.eq.service

/** SUPPRESSED: tracked while global mode is on, no per-session effect attached. */
enum class SessionState { OK, NO_CONTROL, FAILED, SUPPRESSED }

data class SessionInfo(val id: Int, val pkg: String, val state: SessionState)

/** Immutable snapshot published by the runtime. Notice carries store and global-mode messages. */
data class ServiceStatus(
    val sessionCount: Int = 0,
    val bypass: Boolean = false,
    val globalMode: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val notice: String? = null,
)
