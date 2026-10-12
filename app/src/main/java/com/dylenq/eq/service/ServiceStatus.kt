package com.dylenq.eq.service

enum class SessionState { OK, NO_CONTROL, FAILED }

data class SessionInfo(val id: Int, val pkg: String, val state: SessionState)

/** Immutable snapshot published by the runtime. Later plans add global mode and store notices. */
data class ServiceStatus(
    val sessionCount: Int = 0,
    val bypass: Boolean = false,
    val sessions: List<SessionInfo> = emptyList(),
    val notice: String? = null,
)
