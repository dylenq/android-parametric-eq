package com.dylenq.eq.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.service.SessionIntent
import com.dylenq.eq.testsupport.EqComposeRule
import com.dylenq.eq.testsupport.FakeEffectFactory
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Evidence screenshots of Settings; runs only with `-e screenshots 1`. */
class SettingsScreenshots {
    @get:Rule val rule = EqComposeRule { FakeEffectFactory().also { it.failSession(0, IllegalStateException("x")) } }

    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val compose: ComposeTestRule get() = rule.compose

    private fun shot(name: String) {
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null)!!, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun settings() {
        assumeTrue(args.getString("screenshots") == "1")
        rule.launch(); compose.waitForIdle()
        compose.onNodeWithTag("overflow").performClick(); compose.waitForIdle()
        compose.onNodeWithTag("settings-item").performClick(); compose.waitForIdle()
        Thread.sleep(800)
        shot("05-11-settings-default")
        rule.runtime.post(SessionIntent.Event.Open(4521, "com.example.player"))
        rule.runtime.post(SessionIntent.Event.Open(4522, "org.example.browser"))
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("session-row-4522")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(600)
        shot("05-11-settings-with-session")
        compose.onNodeWithTag("global-mode").performClick()
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("banner-notice")).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(800)
        shot("05-11-settings-global-rejected")
    }
}
