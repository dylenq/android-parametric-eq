package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
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
        assertEquals(260f, plotHeightFor(1000, 900f, 1.0f, false), 0f)
        assertEquals(240f, plotHeightFor(800, 840f, 1.0f, false), 0.01f)
    }

    @Test fun plotClampsTo168WhenTheColumnWillScroll() {
        assertEquals(168f, plotHeightFor(640, 540f, 1.0f, false), 0f)
        assertEquals(168f, plotHeightFor(800, 760f, 2.0f, true), 0f)
    }
}
