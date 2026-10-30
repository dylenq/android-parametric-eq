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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollTo
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

    /** The window is smaller than the screen in the two-pane tests, so "displayed" is checked against the pane. */
    private fun assertInside(tag: String, pane: String) {
        val t = b(tag)
        val p = b(pane)
        assertTrue("$tag $t inside $pane $p", t.top >= p.top - 1.dp && t.bottom <= p.bottom + 1.dp && t.left >= p.left - 1.dp && t.right <= p.right + 1.dp)
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

    /**
     * UI-SPEC acceptance check 1, rewritten for Phase 8 (A1): the Tone & balance card adds about 192 dp, so at
     * 360 x 800 dp the column scrolls; the card and every control are reachable by scrolling, nothing overlaps.
     */
    @Test fun portrait360x800WithNoBannerReachesEverythingByScrolling() {
        val spec = WindowSpec(360, 800)
        hostScreen(spec)
        compose.waitForIdle()
        val statusText = node("status-text").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        val startShown = compose.onAllNodes(hasTestTag("status-start")).fetchSemanticsNodes().isNotEmpty()
        Log.i("EQ-LAYOUT", "360x800 status strip shows '$statusText' start-button=$startShown")
        val blocks = listOf("status-strip", "curve", "mode-row", "slider-row", "preamp-slider", "limiter-row", "tone-card")
            .joinToString(" ") { "$it=${b(it).top.value.toInt()}..${b(it).bottom.value.toInt()}(h${b(it).height.value.toInt()})" }
        val plot = b("curve").height.value
        Log.i("EQ-LAYOUT", "360x800 no banner: $blocks plot=$plot track=${b("band-0").height} column-scroll-max-px=${verticalMax("eq-column")} tone-bottom=${b("tone-card").bottom}")
        node("tone-card").assertExists()
        assertTrue("the column scrolls to reach the card: ${verticalMax("eq-column")}", verticalMax("eq-column") > 0f)
        for (tag in listOf("curve", "mode-row", "slider-row")) {
            assertTrue("$tag inside the 800 dp window: ${b(tag)}", b(tag).bottom <= 800.dp)
        }
        // 140 dp minimum, less up to a pixel of density rounding (139.8 at 2.625x).
        assertTrue("plot >= 140 dp: $plot", plot >= 139.5f)
        assertTrue("slider tracks >= 120 dp: ${b("band-0").height}", b("band-0").height >= 120.dp)
        // Limiter row stays above the tone card; the card's rows do not overlap each other.
        assertTrue("limiter above tone card", b("limiter-row").bottom <= b("tone-card").top + 1.dp)
        assertTrue("bass above treble", b("bass-slider").bottom <= b("treble-slider").top + 1.dp)
        assertTrue("treble above balance", b("treble-slider").bottom <= b("balance-slider").top + 1.dp)
        for (tag in listOf("bass-slider", "treble-slider", "balance-slider")) {
            node(tag).performScrollTo()
            node(tag).assertIsDisplayed()
        }
        // The segmented buttons and the band-count button are 48 dp tall since plan 05-08 (ModeRowTouchTargetTest).
        for (tag in listOf("equalizer-toggle", "mode-row", "band-count-slot", "preamp-pill", "limiter-row", "bass-pill", "treble-pill", "balance-pill", "bass-slider", "treble-slider", "balance-slider")) {
            assertTrue("$tag touch target >= 48 dp: ${b(tag).height} x ${b(tag).width}", b(tag).height >= 47.5.dp)
        }
    }

    @Test fun portrait411x891WithoutABannerReachesToneCardByScrolling() {
        hostScreen(WindowSpec(411, 891))
        val h = 891.dp
        for (tag in listOf("curve", "mode-row", "slider-row")) {
            assertTrue("$tag inside the window: ${b(tag)}", b(tag).bottom <= h)
        }
        node("tone-card").assertExists()
        assertTrue("limiter above tone card", b("limiter-row").bottom <= b("tone-card").top + 1.dp)
        node("balance-slider").performScrollTo()
        node("balance-slider").assertIsDisplayed()
        Log.i("EQ-LAYOUT", "411x891: curve=${b("curve")} plot-height=${b("curve").height} slider-row=${b("slider-row")} tone-card=${b("tone-card")} scroll-max=${verticalMax("eq-column")}")
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

    @Test fun fontScale2StacksTheToneCardWithoutOverlap() {
        hostScreen(WindowSpec(411, 891, 2f))
        node("tone-card").assertExists()
        val card = b("tone-card")
        assertTrue("stacked card is at least 344 dp: ${card.height}", card.height >= 343.dp)
        assertTrue("below the preamp card", b("limiter-row").bottom <= card.top + 1.dp)
        val tags = listOf("bass", "treble", "balance").flatMap { listOf("$it-slider", "$it-pill") }
        for (t in tags) {
            node(t).performScrollTo()
            assertTrue("$t >= 48 dp tall: ${b(t).height}", b(t).height >= 47.5.dp)
            assertTrue("$t inside the card horizontally", b(t).left >= card.left - 1.dp && b(t).right <= card.right + 1.dp)
        }
        for (i in tags.indices) for (j in i + 1 until tags.size) {
            val x = b(tags[i]); val y = b(tags[j])
            val overlap = x.left < y.right && y.left < x.right && x.top < y.bottom && y.top < x.bottom
            assertTrue("${tags[i]} $x overlaps ${tags[j]} $y", !overlap)
        }
    }

    @Test fun width1000TwoPaneRightPaneHoldsTheToneCard() {
        hostScreen(WindowSpec(1000, 700))
        node("two-pane").assertExists()
        val right = b("right-pane")
        val card = b("tone-card")
        assertTrue("tone card inside the right pane: $card vs $right", card.left >= right.left - 1.dp && card.right <= right.right + 1.dp)
        assertTrue("card under the preamp card", b("limiter-row").bottom <= card.top + 1.dp)
        node("balance-slider").performScrollTo()
        assertInside("balance-slider", "right-pane")
        Log.i("EQ-LAYOUT", "1000x700: right=$right tone=$card right-scroll-max=${verticalMax("right-pane-scroll")}")
    }

    @Test fun landscape800x360KeepsPreampBoundsAndReachesTheToneCard() {
        hostScreen(WindowSpec(800, 360))
        val preamp = b("limiter-row").bottom - b("preamp-slider").top
        assertTrue("compact preamp card is two rows: $preamp", preamp <= 112.dp)
        assertTrue("right pane scrolls to reach the card: ${verticalMax("right-pane-scroll")}", verticalMax("right-pane-scroll") > 0f)
        assertTrue("card under the preamp card", b("limiter-row").bottom <= b("tone-card").top + 1.dp)
        for (t in listOf("bass-slider", "treble-slider", "balance-slider")) {
            node(t).performScrollTo()
            assertInside(t, "right-pane")
        }
    }

    @Test fun thirtyOneBandGraphicDoesNotOverflowHorizontally() {
        hostScreen(WindowSpec(360, 800))
        app.uiHolder().setBandCount(31)
        compose.waitForIdle()
        val card = b("tone-card")
        assertTrue("tone card inside 360 dp: $card", card.left >= 0.dp && card.right <= 360.dp)
        for (t in listOf("bass-pill", "treble-pill", "balance-pill")) {
            assertTrue("$t inside the card: ${b(t)}", b(t).right <= card.right + 1.dp)
        }
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
