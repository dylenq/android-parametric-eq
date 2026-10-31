package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 09-UI-SPEC "small widths" and large font: the preset chip, the top bar icons and the sheet header. */
@RunWith(AndroidJUnit4::class)
class PresetChipLayoutTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val compose get() = rule.compose

    private fun hostScreen(spec: WindowSpec) = rule.host {
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

    private fun b(tag: String): DpRect {
        val n = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val p = n.positionInRoot
        return DpRect((p.x / d).dp, (p.y / d).dp, ((p.x + n.size.width) / d).dp, ((p.y + n.size.height) / d).dp)
    }

    @Test fun at320dpAndFontScale2TheTopBarIconsStayInsideAndTheChipEllipsizesOneLine() {
        val name = "A".repeat(40)
        val spec = WindowSpec(320, 640, 2f)
        app.uiHolder().savePresetAs(name)
        hostScreen(spec)
        compose.waitForIdle()
        for (tag in listOf("undo", "redo", "equalizer-toggle", "overflow")) {
            val r = b(tag)
            assertTrue("$tag $r inside 0..320 dp", r.left >= 0.dp && r.right <= 320.dp + 0.5.dp)
        }
        val chip = b("preset-chip")
        assertTrue("chip at least 48 dp tall: ${chip.height}", chip.height >= 47.5.dp)
        assertTrue("chip ends before the undo button: $chip vs ${b("undo")}", chip.right <= b("undo").left + 1.dp)
        val node = compose.onNode(hasText(name), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)!!.action!!.invoke(results)
        assertEquals("single line", 1, results.first().lineCount)
        assertTrue("ellipsized", results.first().isLineEllipsized(0))
    }

    @Test fun twoPaneWindowOpensTheSheetAndAppliesBass() {
        val spec = WindowSpec(900, 420)
        assertTrue(spec.twoPane)
        hostScreen(spec)
        compose.waitForIdle()
        compose.onNodeWithTag("preset-chip").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("preset-sheet").assertIsDisplayed()
        compose.onNodeWithTag("preset-row-builtin:Bass").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("builtin:Bass", app.uiHolder().ui.value.activePresetKey)
    }

    @Test fun largeFontStacksTheHeaderButtonsAndKeepsTheFirstRowReachable() {
        val spec = WindowSpec(411, 891, 2f)
        assertTrue(spec.largeFont)
        hostScreen(spec)
        compose.waitForIdle()
        compose.onNodeWithTag("preset-chip").performClick()
        compose.waitForIdle()
        val save = b("preset-save")
        val saveAs = b("preset-save-as")
        assertTrue("stacked: different tops $save $saveAs", saveAs.top >= save.bottom - 1.dp)
        assertEquals("stacked: same left", save.left.value, saveAs.left.value, 1f)
        compose.onNodeWithTag("preset-row-builtin:Flat").performScrollTo().assertIsDisplayed()
        assertTrue("row at least 48 dp: ${b("preset-row-builtin:Flat").height}", b("preset-row-builtin:Flat").height >= 47.5.dp)
    }
}
