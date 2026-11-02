package com.dylenq.eq.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** The real route source on the emulator: no special permission, and the speaker is what it reports. */
@RunWith(AndroidJUnit4::class)
class RealOutputRouteTest {
    @Test
    fun the_emulator_speaker_is_detected_without_a_permission() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val route = AudioManagerRoute(ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val info = route.current()
        Log.i("RealOutputRouteTest", "route=$info path=${route.lastPath} securityException=${route.sawSecurityException}")
        assertEquals("speaker", info.key)
        assertEquals("Phone speaker", info.name)
    }
}
