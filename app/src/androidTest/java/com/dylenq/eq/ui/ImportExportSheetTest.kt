package com.dylenq.eq.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PRE-05..07 entry points: Import and Export in the preset sheet header, their enabled state and layout, and
 * the picker intents. The system picker itself is not driven (no Espresso-Intents): the launch is the one
 * untested line.
 */
@RunWith(AndroidJUnit4::class)
class ImportExportSheetTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val compose get() = rule.compose

    private fun hostSheet(spec: WindowSpec) {
        rule.host {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, spec.fontScale)) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(spec.widthDp.dp, spec.heightDp.dp)) {
                        EqScreen(
                            holder = app.uiHolder(),
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
        }
        compose.waitForIdle()
        compose.onNodeWithTag("preset-chip").performClick()
        compose.waitForIdle()
    }

    private fun heightDp(tag: String): Float {
        val n = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return n.size.height / InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    }

    @Test fun headerHasImportAndExportAtLeast48dpTall() {
        app.uiHolder().setMode(EqMode.PARAMETRIC)
        hostSheet(WindowSpec(400, 800))
        compose.onNodeWithTag("preset-import").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("preset-export").assertIsDisplayed().assertIsEnabled()
        assertTrue(heightDp("preset-import") >= 47.5f)
        assertTrue(heightDp("preset-export") >= 47.5f)
        compose.onNode(hasText(ImportExportCopy.EXPORT_NOTE)).assertIsDisplayed()
    }

    @Test fun exportIsDisabledInGraphicModeWithTheReason() {
        app.uiHolder().setMode(EqMode.GRAPHIC)
        hostSheet(WindowSpec(400, 800))
        compose.onNodeWithTag("preset-import").assertIsEnabled()
        compose.onNodeWithTag("preset-export").assertIsNotEnabled()
        compose.onNode(hasText(ImportExportCopy.EXPORT_DISABLED)).assertIsDisplayed()
        val state = compose.onNodeWithTag("preset-export").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.StateDescription)
        assertEquals(ImportExportCopy.EXPORT_DISABLED, state)
    }

    @Test fun actionsStackWithoutClippingAtLargeFont() {
        app.uiHolder().setMode(EqMode.PARAMETRIC)
        val spec = WindowSpec(360, 740, 1.5f)
        hostSheet(spec)
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val imp = compose.onNodeWithTag("preset-import").assertIsDisplayed().fetchSemanticsNode()
        val exp = compose.onNodeWithTag("preset-export").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("stacked: export below import", exp.positionInRoot.y >= imp.positionInRoot.y + imp.size.height - 1)
        for (n in listOf(imp, exp)) {
            assertTrue(n.positionInRoot.x >= 0f && (n.positionInRoot.x + n.size.width) / d <= spec.widthDp + 0.5f)
            assertTrue(n.size.height / d >= 47.5f)
        }
    }

    @Test fun openDocumentIntentAsksForTextFilesWithoutAStoragePermission() {
        val i = ImportExportCopy.openDocumentIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, i.action)
        assertEquals("text/*", i.type)
        assertTrue(i.categories.contains(Intent.CATEGORY_OPENABLE))
        assertArrayEquals(arrayOf("application/octet-stream"), i.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test fun createDocumentIntentIsNamedAfterThePreset() {
        val i = ImportExportCopy.createDocumentIntent("HD650")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, i.action)
        assertEquals("text/plain", i.type)
        assertTrue(i.categories.contains(Intent.CATEGORY_OPENABLE))
        assertEquals("HD650 ParametricEQ.txt", i.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("EQ ParametricEQ.txt", ImportExportCopy.createDocumentIntent(null).getStringExtra(Intent.EXTRA_TITLE))
    }
}
