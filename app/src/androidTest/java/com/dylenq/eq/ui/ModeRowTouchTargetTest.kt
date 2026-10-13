package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.height
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L-10(a): the Graphic/Parametric segmented buttons and the band-count button are tappable across the full
 * 48 dp, with no dead strip at the top or bottom of the row.
 */
@RunWith(AndroidJUnit4::class)
class ModeRowTouchTargetTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose
    private val ctrl get() = rule.runtime.controller
    private val density get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun hostRow(fontScale: Float = 1f, widthDp: Int? = null) = rule.host {
        val ui by holder.ui.collectAsStateWithLifecycle()
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                ModeRow(
                    ui.mode, ui.graphicBandCount, holder::setMode, holder::setBandCount,
                    stacked = widthDp?.let { modeRowStacked(it, fontScale) } ?: (fontScale >= 1.3f),
                )
            }
        }
    }

    private fun tap(tag: String, xFraction: Float, yPx: (heightPx: Float) -> Float) {
        compose.onNodeWithTag(tag).performTouchInput { click(Offset(width * xFraction, yPx(height.toFloat()))) }
        compose.waitForIdle()
    }

    private fun checkSegments() {
        val seg = compose.onNodeWithTag("mode-segmented").getUnclippedBoundsInRoot()
        for (tag in listOf("mode-graphic", "mode-parametric")) {
            val h = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height
            assertTrue("$tag is >= 48 dp tall: $h", h >= 47.5.dp)
        }
        assertTrue("segmented row >= 48 dp: ${seg.height}", seg.height >= 47.5.dp)
        val edge = 2 * density
        for ((target, other, x) in listOf(
            Triple(EqMode.PARAMETRIC, EqMode.GRAPHIC, 0.75f),
            Triple(EqMode.GRAPHIC, EqMode.PARAMETRIC, 0.25f),
        )) {
            for (top in listOf(true, false)) {
                holder.setMode(other)
                val until = System.currentTimeMillis() + 3000
                while (holder.ui.value.mode != other && System.currentTimeMillis() < until) { Thread.sleep(20); compose.waitForIdle() }
                assertEquals(other, holder.ui.value.mode)
                tap("mode-segmented", x) { h -> if (top) edge else h - edge }
                val until2 = System.currentTimeMillis() + 3000
                while (holder.ui.value.mode != target && System.currentTimeMillis() < until2) { Thread.sleep(20); compose.waitForIdle() }
                assertEquals("a tap 2 dp inside the ${if (top) "top" else "bottom"} edge switches to $target", target, holder.ui.value.mode)
            }
        }
    }

    @Test fun segmentedButtonsAreFullyTappableAtNormalFontScale() {
        hostRow()
        checkSegments()
    }

    @Test fun segmentedButtonsAreFullyTappableInTheStackedForm() {
        hostRow(fontScale = 1.5f)
        checkSegments()
    }

    @Test fun segmentedButtonsAreFullyTappableInTheNarrowStackedForm() {
        hostRow(widthDp = 340)
        checkSegments()
    }

    @Test fun bandCountButtonIs48DpAndOpensFromItsTopAndBottomEdges() {
        for (fontScale in listOf(1f, 1.5f)) {
            hostRow(fontScale)
            holder.setMode(EqMode.GRAPHIC)
            compose.waitForIdle()
            val h = compose.onNodeWithTag("band-count-button").getUnclippedBoundsInRoot().height
            assertTrue("band-count button >= 48 dp tall (font scale $fontScale): $h", h >= 47.5.dp)
            val edge = 2 * density
            for (top in listOf(true, false)) {
                tap("band-count-button", 0.5f) { hh -> if (top) edge else hh - edge }
                assertTrue(
                    "band-count menu opens from a tap 2 dp inside the ${if (top) "top" else "bottom"} edge",
                    compose.onAllNodes(hasTestTag("band-count-item-10")).fetchSemanticsNodes().isNotEmpty(),
                )
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close()
                Thread.sleep(300)
                compose.waitForIdle()
            }
        }
    }
}
