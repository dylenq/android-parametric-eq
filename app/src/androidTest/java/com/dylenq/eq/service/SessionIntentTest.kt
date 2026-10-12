package com.dylenq.eq.service

import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionIntentTest {
    private fun intent(action: String?, session: Int? = 42, pkg: String? = "com.example.player"): Intent {
        val i = if (action == null) Intent() else Intent(action)
        if (session != null) i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
        if (pkg != null) i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, pkg)
        return i
    }

    private val open = AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
    private val close = AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION

    @Test fun valid_open_parses() {
        assertEquals(SessionIntent.Event.Open(42, "com.example.player"), SessionIntent.parse(intent(open)))
    }

    @Test fun valid_close_parses() {
        assertEquals(SessionIntent.Event.Close(42, "com.example.player"), SessionIntent.parse(intent(close)))
    }

    @Test fun session_zero_rejected() = assertNull(SessionIntent.parse(intent(open, session = 0)))

    @Test fun negative_session_rejected() = assertNull(SessionIntent.parse(intent(open, session = -1)))

    @Test fun missing_session_extra_rejected() = assertNull(SessionIntent.parse(intent(open, session = null)))

    @Test fun missing_package_rejected() = assertNull(SessionIntent.parse(intent(open, pkg = null)))

    @Test fun package_over_255_chars_rejected() {
        val long = "com." + "a".repeat(252)
        assertEquals(256, long.length)
        assertNull(SessionIntent.parse(intent(open, pkg = long)))
    }

    @Test fun package_at_255_chars_accepted() {
        val ok = "com." + "a".repeat(251)
        assertEquals(255, ok.length)
        assertEquals(SessionIntent.Event.Open(42, ok), SessionIntent.parse(intent(open, pkg = ok)))
    }

    @Test fun package_with_space_rejected() = assertNull(SessionIntent.parse(intent(open, pkg = "a b")))

    @Test fun package_without_dot_rejected() = assertNull(SessionIntent.parse(intent(open, pkg = "player")))

    @Test fun wrong_action_rejected() = assertNull(SessionIntent.parse(intent("com.example.OTHER")))

    @Test fun no_action_rejected() = assertNull(SessionIntent.parse(intent(null)))

    @Test fun null_intent_rejected() = assertNull(SessionIntent.parse(null))
}
