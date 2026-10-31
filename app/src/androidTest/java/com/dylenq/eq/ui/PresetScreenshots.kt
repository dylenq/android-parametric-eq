package com.dylenq.eq.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Evidence screenshots of the preset chip, sheet, dialogs and snackbar; runs only with `-e screenshots 1`. */
class PresetScreenshots {
    @get:Rule val rule = EqComposeRule()

    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose

    private fun shot(name: String) {
        Thread.sleep(700)
        val bmp: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(app.getExternalFilesDir(null)!!, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun click(tag: String) {
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    @Test fun presetScreens() {
        assumeTrue(args.getString("screenshots") == "1")
        rule.launch()
        compose.waitForIdle()
        shot("09-chip-no-preset")

        click("preset-chip")
        shot("09-sheet-empty")

        // Applied with the Undo snackbar.
        click("preset-row-builtin:Bass")
        compose.waitUntil(3000) { compose.onAllNodes(hasText("Applied Bass")).fetchSemanticsNodes().isNotEmpty() }
        shot("09-snackbar-applied-undo")

        // Active and edited, with one user preset.
        holder.savePresetAs("Night")
        holder.applyPreset("builtin:Treble")
        holder.setPreamp(-3f)
        compose.waitForIdle()
        click("preset-chip")
        shot("09-sheet-applied-edited")

        // Save-as dialog with a taken name.
        click("preset-save-as")
        compose.onNodeWithTag("preset-name-field").performTextReplacement("Bass")
        compose.waitForIdle()
        if (runCatching { compose.onNodeWithTag("preset-dialog-confirm").performClick() }.isSuccess) compose.waitForIdle()
        shot("09-save-as-dialog-error")
        click("preset-dialog-cancel")

        click("preset-menu-user:Night")
        click("preset-rename")
        shot("09-rename-dialog")
        click("preset-dialog-cancel")

        click("preset-menu-user:Night")
        click("preset-delete")
        shot("09-delete-confirm")
        click("preset-dialog-cancel")
    }

    @Test fun sheetAtLargeFont() {
        assumeTrue(args.getString("screenshots") == "1")
        holder.savePresetAs("Night")
        val spec = WindowSpec(411, 891, 2f)
        rule.host {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, spec.fontScale)) {
                Box(Modifier.fillMaxSize()) {
                    EqScreen(
                        holder = holder,
                        prefs = rule.uiPrefs,
                        notificationsGranted = true,
                        onStartService = {},
                        onAllowNotifications = {},
                        onOpenNotificationSettings = {},
                        spec = spec,
                    )
                }
            }
        }
        compose.waitForIdle()
        click("preset-chip")
        shot("09-sheet-large-font")
    }
}
