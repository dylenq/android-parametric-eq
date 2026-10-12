package com.dylenq.eq.ui

import android.content.pm.ActivityInfo
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-SPEC layouts A and B, the width table and large-font behaviour, measured with explicit [WindowSpec]s. */
@RunWith(AndroidJUnit4::class)
class LayoutTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val compose get() = rule.compose

    /** Hosts the whole [EqScreen] in a box of exactly the window size, with the spec's font scale. */
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

    /** Unclipped bounds in dp (getBoundsInRoot is clipped by scroll viewports, which hides off-screen nodes). */
    private fun b(tag: String): DpRect {
        val n = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val p = n.positionInRoot
        return DpRect((p.x / d).dp, (p.y / d).dp, ((p.x + n.size.width) / d).dp, ((p.y + n.size.height) / d).dp)
    }

    private fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    private fun verticalMax(tag: String): Float {
        val n = node(tag).fetchSemanticsNode()
        return n.config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    }

    @Test fun portrait360x800CurveAboveControlsAndHeaderRegionsVisible() {
        val spec = WindowSpec(360, 800)
        hostScreen(spec)
        val curve = b("curve")
        val mode = b("mode-row")
        val limiter = b("limiter-row")
        assertTrue("curve ${curve.bottom} above mode row ${mode.top}", curve.bottom <= mode.top)
        assertTrue("curve above the preamp card", curve.top < b("preamp-slider").top)
        assertTrue("top bar inside the window", b("equalizer-toggle").top >= 0.dp && b("equalizer-toggle").bottom <= 800.dp)
        assertTrue("curve fully inside the window", curve.bottom <= 800.dp && curve.top >= 0.dp)
        assertTrue("mode row inside the window: ${mode.bottom}", mode.bottom <= 800.dp)
        // Measured, not asserted (the spec's arithmetic does not close at 800 dp, see the summary).
        Log.i("EQ-LAYOUT", "360x800: curve=${curve.top}..${curve.bottom} mode=${mode.top}..${mode.bottom} limiter-bottom=${limiter.bottom} column-scroll-max=${verticalMax("eq-column")}")
        assertTrue("tight column keeps 130 dp tracks (216 dp panel minimum applies above 880 dp): ${b("slider-row").height}", b("slider-row").height >= 128.dp)
    }

    /** UI-SPEC acceptance check 1: everything, limiter row included, visible at 360 x 800 dp with no banner. */
    @Test fun portrait360x800WithNoBannerFitsEverythingWithoutScrolling() {
        val spec = WindowSpec(360, 800)
        hostScreen(spec)
        compose.waitForIdle()
        val statusText = node("status-text").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val startShown = compose.onAllNodes(hasTestTag("status-start")).fetchSemanticsNodes().isNotEmpty()
        Log.i("EQ-LAYOUT", "360x800 status strip shows '$statusText' start-button=$startShown")
        val blocks = listOf("status-strip", "curve", "mode-row", "slider-row", "preamp-slider", "limiter-row")
            .joinToString(" ") { "$it=${b(it).top.value.toInt()}..${b(it).bottom.value.toInt()}(h${b(it).height.value.toInt()})" }
        val plot = b("curve").height.value
        Log.i("EQ-LAYOUT", "360x800 no banner: $blocks plot=$plot track=${b("band-0").height} column-scroll-max-px=${verticalMax("eq-column")} limiter-bottom=${b("limiter-row").bottom}")
        assertEquals("no vertical scrolling", 0f, verticalMax("eq-column"), 0f)
        for (tag in listOf("curve", "mode-row", "slider-row", "preamp-slider", "limiter-row")) {
            assertTrue("$tag inside the 800 dp window: ${b(tag)}", b(tag).bottom <= 800.dp)
        }
        assertTrue("plot >= 140 dp: $plot", plot >= 140f)
        assertTrue("slider tracks >= 120 dp: ${b("band-0").height}", b("band-0").height >= 120.dp)
        // The Material segmented buttons are 40 dp tall as shipped (not changed here); their row is 48 dp.
        for (tag in listOf("equalizer-toggle", "mode-row", "band-count-slot", "preamp-pill", "limiter-row")) {
            assertTrue("$tag touch target >= 48 dp: ${b(tag).height} x ${b(tag).width}", b(tag).height >= 47.5.dp)
        }
    }

    @Test fun portrait411x891WithoutABannerFitsWithoutScrolling() {
        hostScreen(WindowSpec(411, 891))
        assertEquals("no vertical scrolling", 0f, verticalMax("eq-column"), 0f)
        val h = 891.dp
        for (tag in listOf("curve", "mode-row", "slider-row", "limiter-row")) {
            assertTrue("$tag inside the window: ${b(tag)}", b(tag).bottom <= h)
        }
        Log.i("EQ-LAYOUT", "411x891: curve=${b("curve")} plot-height=${b("curve").height} slider-row=${b("slider-row")} limiter-bottom=${b("limiter-row").bottom}")
    }

    @Test fun landscape800x360IsTwoPanesWithLongTracksAndCompactPreamp() {
        val spec = WindowSpec(800, 360)
        hostScreen(spec)
        node("two-pane").assertExists()
        val left = b("left-pane")
        val right = b("right-pane")
        assertTrue("curve pane left of the slider pane", left.right <= right.left)
        assertTrue("curve left of sliders", b("curve").left < b("slider-row").left)
        val content = right.right - left.left
        val ratio = left.width.value / content.value
        assertEquals("left pane is about 44% of the content width", 0.44f, ratio, 0.04f)
        assertTrue("slider track >= 120 dp: ${b("band-0").height}", b("band-0").height >= 120.dp)
        val preamp = b("limiter-row").bottom - b("preamp-slider").top
        assertTrue("compact preamp card is two rows: $preamp", preamp <= 112.dp)
        assertTrue("sliders right of the curve", b("band-0").left > b("curve").right)
        Log.i("EQ-LAYOUT", "800x360: curve=${b("curve")} track=${b("band-0").height} preamp-rows=$preamp right-scroll-max=${verticalMax("right-pane-scroll")}")
    }

    @Test fun width340UsesTwelveDpMarginsAndStacksTheModeRow() {
        hostScreen(WindowSpec(340, 800))
        assertEquals(12f, b("mode-row").left.value, 0.5f)
        assertTrue("segmented above the band menu", b("mode-segmented").bottom <= b("band-count-slot").top)
    }

    @Test fun width1000PortraitIsTwoPane() {
        hostScreen(WindowSpec(840, 1200))
        node("two-pane").assertExists()
    }

    @Test fun width700PortraitIsCappedAt640AndCentred() {
        hostScreen(WindowSpec(700, 1200))
        val mode = b("mode-row")
        assertTrue("capped to 640 dp minus margins: ${mode.width}", mode.width <= 640.dp - 48.dp + 0.5.dp)
        assertEquals("centred", (700f - mode.right.value), mode.left.value, 1f)
    }

    @Test fun fontScale2KeepsPitch48StacksTheModeRowScrollsAndDoesNotOverlap() {
        val spec = WindowSpec(411, 891, 2f)
        hostScreen(spec)
        assertEquals("slider pitch is 48 dp", 48f, b("band-0").width.value, 1f)
        assertTrue("mode row stacked", b("mode-segmented").bottom <= b("band-count-slot").top)
        assertTrue("the column scrolls vertically: ${verticalMax("eq-column")}", verticalMax("eq-column") > 0f)
        val tops = listOf("curve", "mode-row", "slider-row", "limiter-row").map { it to b(it) }
        for (i in 1 until tops.size) {
            assertTrue("${tops[i].first} does not overlap ${tops[i - 1].first}: $tops", tops[i].second.top >= tops[i - 1].second.bottom - 1.dp)
        }
        assertTrue("slider panel keeps its minimum", b("slider-row").height >= 160.dp)
    }

    @Test fun rotationSwitchesBetweenColumnAndTwoPanesAndKeepsState() {
        val scenario = rule.launch()
        compose.waitForIdle()
        app.uiHolder().setBandCount(15)
        app.uiHolder().onBandDragStart(2)
        app.uiHolder().onBandDragChange(2, 3f)
        app.uiHolder().onBandDragEnd()
        compose.waitForIdle()
        try {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            val deadline = System.currentTimeMillis() + 10_000
            while (compose.onAllNodes(hasTestTag("two-pane")).fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                compose.waitForIdle()
            }
            node("two-pane").assertExists()
            assertEquals(3f, rule.runtime.controller.snapshot().graphicGainsDb[2], 0.01f)
            assertEquals(15, rule.runtime.controller.snapshot().graphicBandCount.toInt())
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val d2 = System.currentTimeMillis() + 10_000
            while (compose.onAllNodes(hasTestTag("eq-column")).fetchSemanticsNodes().isEmpty() && System.currentTimeMillis() < d2) {
                Thread.sleep(100)
                compose.waitForIdle()
            }
            node("eq-column").assertExists()
            assertEquals(15, rule.runtime.controller.snapshot().graphicBandCount.toInt())
        } finally {
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
}
