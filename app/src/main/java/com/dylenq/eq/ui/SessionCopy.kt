package com.dylenq.eq.ui

import com.dylenq.eq.service.SessionInfo
import com.dylenq.eq.service.SessionState

/** Pure copy and ordering for the Settings sessions list (UI-SPEC "Detected sessions"). */
object SessionCopy {
    fun stateText(state: SessionState): String = when (state) {
        SessionState.OK -> "Equalizing"
        SessionState.NO_CONTROL -> "Android didn't give EQ control of this session"
        SessionState.FAILED -> "The effect couldn't be attached"
        SessionState.SUPPRESSED -> "Covered by global mode"
    }

    fun rowTitle(pkg: String): String = if (pkg.isBlank()) "Unknown app" else pkg

    fun supporting(info: SessionInfo): String = "Session ${info.id} · ${stateText(info.state)}"

    fun countText(n: Int): String = if (n == 1) "1 session" else "$n sessions"

    private fun rank(s: SessionState): Int = when (s) {
        SessionState.NO_CONTROL, SessionState.FAILED -> 0
        SessionState.SUPPRESSED -> 1
        SessionState.OK -> 2
    }

    fun sorted(list: List<SessionInfo>): List<SessionInfo> =
        list.sortedWith(compareBy<SessionInfo>({ rank(it.state) }, { it.pkg }, { it.id }))
}
