package com.dylenq.eq.service

import android.content.Intent
import android.media.audiofx.AudioEffect

/** Pure parser for OPEN/CLOSE session broadcasts. Extras come from untrusted apps. */
object SessionIntent {
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    sealed interface Event {
        val session: Int
        val pkg: String
        data class Open(override val session: Int, override val pkg: String) : Event
        data class Close(override val session: Int, override val pkg: String) : Event
    }

    fun parse(i: Intent?): Event? {
        val action = i?.action ?: return null
        val session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: return null
        // Session 0 is reachable only through global mode, never through a broadcast.
        if (session <= 0 || pkg.length > 255 || !PKG.matches(pkg)) return null
        return when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> Event.Open(session, pkg)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> Event.Close(session, pkg)
            else -> null
        }
    }
}
