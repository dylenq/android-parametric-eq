package com.dylenq.eq.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.EqComposeRule
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Evidence screenshots of the band list, only when run with `-e screenshots 1` (it does nothing in the
 * normal suite). Files go to the app's external files dir; pull them with adb before the app is removed.
 */
class BandListScreenshots {
    @get:Rule val rule = EqComposeRule()

    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()

    private fun shot(name: String) {
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val dir = app.getExternalFilesDir(null)!!
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun host() {
        rule.runtime.controller.edit {
            it.setMode(EqMode.PARAMETRIC)
            it.setBand(0u, Band(FilterKind.LOW_SHELF, 63f, 5f, 0.71f, true))
            it.setBand(1u, Band(FilterKind.PEAK, 250f, -2.5f, 1.4f, true))
            it.setBand(3u, Band(FilterKind.PEAK, 1250f, 3.5f, 1f, false))
            it.setBand(5u, Band(FilterKind.HIGH_PASS, 8000f, 0f, 0.71f, true))
        }
        rule.host {
            val ui by holder.ui.collectAsStateWithLifecycle()
            val actions = androidx.compose.runtime.remember { holder.bandListActions() }
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                ParametricBandList({ ui }, actions, false)
            }
        }
    }

    @Test fun listWithOneRowExpandedAndFocusedField() {
        assumeTrue(args.getString("screenshots") == "1")
        host()
        holder.selectBand(1)
        rule.compose.waitForIdle()
        Thread.sleep(800)
        shot("05-07-band-list-expanded")
        rule.compose.onNodeWithTag("band-field-freq-1").performClick()
        rule.compose.waitForIdle()
        Thread.sleep(2500)
        shot("05-07-band-list-field-focused")
    }
}
