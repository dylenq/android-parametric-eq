package com.dylenq.eq.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Evidence screenshots of the top bar and the Undo snackbar; runs only with `-e screenshots 1`. */
class UndoRedoScreenshots {
    @get:Rule val rule = EqComposeRule()

    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()

    private fun shot(name: String) {
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null)!!, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun topBarAndSnackbar() {
        assumeTrue(args.getString("screenshots") == "1")
        rule.runtime.controller.edit { it.setMode(EqMode.PARAMETRIC) }
        rule.launch()
        rule.compose.waitForIdle()
        Thread.sleep(800)
        shot("05-09-topbar-undo-redo-disabled")
        holder.setBandKind(1, com.dylenq.eq.core.FilterKind.HIGH_SHELF)
        rule.compose.waitForIdle()
        Thread.sleep(500)
        shot("05-09-topbar-undo-enabled")
        rule.compose.onNodeWithTag("undo").performClick()
        rule.compose.waitForIdle()
        Thread.sleep(500)
        shot("05-09-topbar-redo-enabled-undo-disabled")
        holder.removeBand(3)
        rule.compose.waitForIdle()
        Thread.sleep(800)
        shot("05-09-undo-snackbar")
        rule.compose.onNodeWithTag("overflow").performClick()
        rule.compose.waitForIdle()
        Thread.sleep(500)
        shot("05-09-overflow-menu")
    }
}
