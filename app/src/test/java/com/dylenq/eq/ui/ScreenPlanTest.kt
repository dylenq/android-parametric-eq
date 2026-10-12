package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure layout decisions of the single-column screen. */
class ScreenPlanTest {
    @Test fun marginsFollowTheWidthClasses() {
        assertEquals(12, screenMarginDp(320))
        assertEquals(12, screenMarginDp(359))
        assertEquals(16, screenMarginDp(360))
        assertEquals(16, screenMarginDp(599))
        assertEquals(24, screenMarginDp(600))
    }

    @Test fun defaultPlotIsThirtyPercentClampedTo168And260() {
        assertEquals(168f, defaultPlotHeight(400), 0f)
        assertEquals(240f, defaultPlotHeight(800), 0.01f)
        assertEquals(260f, defaultPlotHeight(2000), 0f)
    }

    @Test fun plotKeepsItsDefaultWhenTheMinimumsFit() {
        val p = planColumn(availDp = 900f, fixedDp = 300f, desiredPlotDp = 240f)
        assertEquals(240f, p.plotDp, 0f)
        assertEquals(900f - 300f - 36f - 240f, p.contentDp, 0.01f)
        assertFalse(p.scrolls)
    }

    @Test fun plotShrinksBeforeTheColumnScrolls() {
        // room = 700 - 300 - 36 = 364; panel minimum 216 leaves 148 for the plot, but the plot never goes below 168.
        val p = planColumn(availDp = 700f, fixedDp = 300f, desiredPlotDp = 260f)
        assertEquals(168f, p.plotDp, 0f)
        val q = planColumn(availDp = 800f, fixedDp = 300f, desiredPlotDp = 260f)
        assertEquals(800f - 300f - 36f - 216f, q.plotDp, 0.01f)
        assertEquals(216f, q.contentDp, 0.01f)
        assertFalse(q.scrolls)
    }

    @Test fun columnScrollsWhenEvenTheMinimumsDoNotFit() {
        val p = planColumn(availDp = 600f, fixedDp = 300f, desiredPlotDp = 260f)
        assertEquals(168f, p.plotDp, 0f)
        assertEquals(216f, p.contentDp, 0f)
        assertTrue(p.scrolls)
        assertEquals(300f + 36f + 168f + 216f, p.totalDp, 0.01f)
    }

    @Test fun windowSpecClassesFollowTheUiSpec() {
        assertFalse(WindowSpec(360, 800).twoPane)
        assertTrue(WindowSpec(800, 360).twoPane)
        assertTrue(WindowSpec(840, 1200).twoPane)
        assertFalse(WindowSpec(839, 1200).twoPane)
        assertEquals(640, WindowSpec(700, 1200).maxContentWidthDp)
        assertEquals(12, WindowSpec(340, 800).marginDp)
        assertTrue(WindowSpec(340, 800).stackedModeRow)
        assertTrue(WindowSpec(411, 891, 2f).stackedModeRow)
        assertFalse(WindowSpec(411, 891, 1f).stackedModeRow)
        assertEquals(56, WindowSpec(800, 360).topBarHeightDp)
    }

    @Test fun panelMinimumGrowsOnlyWithLargeFont() {
        assertEquals(216f, graphicPanelMinDp(1f, false), 0.01f)
        assertEquals(169f, graphicPanelMinDp(1f, true), 0.01f)
        assertTrue(graphicPanelMinDp(2f, false) > graphicPanelMinDp(1.3f, false) - 0.01f)
        assertEquals(272f, graphicPanelMinDp(2f, false), 0.01f)
    }

    @Test fun curveChromeFollowsWidthAndFont() {
        assertEquals(68f, curveChromeDp(1f, 411), 0.01f)
        assertEquals(84f, curveChromeDp(1f, 360), 0.01f)
        assertEquals(132f, curveChromeDp(2f, 411), 0.01f)
    }
}
