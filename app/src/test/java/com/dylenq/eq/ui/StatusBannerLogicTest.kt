package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure status-strip priority, banner selection and small helpers (no Android types). */
class StatusBannerLogicTest {
    private fun s(running: Boolean = true, bypass: Boolean = false, global: Boolean = false, n: Int = 0) =
        statusFor(running, bypass, global, n)

    // ---- statusFor ---------------------------------------------------------------------

    @Test fun serviceStoppedBeatsEverythingAndOffersStart() {
        val i = s(running = false, bypass = true, global = true, n = 3)
        assertEquals("EQ is not running", i.text)
        assertEquals(DotRole.ERROR, i.dot)
        assertTrue(i.showStart)
    }

    @Test fun bypassBeatsGlobalAndSessions() {
        val i = s(bypass = true, global = true, n = 3)
        assertEquals("Bypassed. Audio plays unchanged.", i.text)
        assertEquals(DotRole.OUTLINE, i.dot)
        assertFalse(i.showStart)
    }

    @Test fun globalModeBeatsSessionCount() {
        val i = s(global = true, n = 0)
        assertEquals("Equalizing all audio", i.text)
        assertEquals(DotRole.PRIMARY, i.dot)
    }

    @Test fun zeroSessionsWaitsAndCarriesTheBrowserHint() {
        val i = s(n = 0)
        assertEquals("Waiting for audio. Play something in another app.", i.text)
        assertEquals(DotRole.OUTLINE, i.dot)
        assertEquals("Some apps, such as browsers, may not be detected.", i.hint)
    }

    @Test fun oneSessionIsSingular() {
        val i = s(n = 1)
        assertEquals("Equalizing 1 app", i.text)
        assertEquals(DotRole.PRIMARY, i.dot)
        assertNull(i.hint)
    }

    @Test fun threeSessionsArePlural() {
        assertEquals("Equalizing 3 apps", s(n = 3).text)
        assertEquals("Equalizing 2 apps", s(n = 2).text)
    }

    @Test fun onlyTheWaitingStateHasAHint() {
        for (n in listOf(1, 2, 7)) assertNull(s(n = n).hint)
        assertNull(s(bypass = true).hint)
        assertNull(s(running = false).hint)
    }

    // ---- selectBanner ------------------------------------------------------------------

    @Test fun noticeWinsOverThePermissionBanners() {
        assertEquals(BannerKind.NOTICE, selectBanner("x", 36, false, false, false))
        assertEquals(BannerKind.NOTICE, selectBanner("x", 28, true, true, true))
    }

    @Test fun rationaleShowsFirstOnApi33WithoutThePermission() {
        assertEquals(BannerKind.RATIONALE, selectBanner(null, 33, false, false, false))
        assertEquals(BannerKind.RATIONALE, selectBanner(null, 37, false, false, true))
    }

    @Test fun afterTheRationaleIsAnsweredTheOffHintShowsOncePerLaunch() {
        assertEquals(BannerKind.NOTIFICATIONS_OFF, selectBanner(null, 36, false, true, false))
        assertNull(selectBanner(null, 36, false, true, true))
    }

    @Test fun grantedPermissionShowsNoPermissionBanner() {
        assertNull(selectBanner(null, 36, true, false, false))
        assertNull(selectBanner(null, 36, true, true, false))
    }

    @Test fun belowApi33NeverShowsRationaleOrOffHint() {
        for (sdk in listOf(28, 30, 32)) {
            for (granted in listOf(true, false)) {
                for (answered in listOf(true, false)) {
                    for (dismissed in listOf(true, false)) {
                        assertNull("sdk $sdk", selectBanner(null, sdk, granted, answered, dismissed))
                    }
                }
            }
        }
    }

    @Test fun api33IsTheFirstLevelThatAsks() {
        assertNull(selectBanner(null, 32, false, false, false))
        assertEquals(BannerKind.RATIONALE, selectBanner(null, 33, false, false, false))
    }

    // ---- helpers shipped with this plan ------------------------------------------------

    @Test fun modeRowStacksOnNarrowWidthOrLargeFont() {
        assertTrue(modeRowStacked(320, 1.0f))
        assertTrue(modeRowStacked(359, 1.0f))
        assertFalse(modeRowStacked(360, 1.0f))
        assertTrue(modeRowStacked(411, 1.3f))
        assertFalse(modeRowStacked(411, 1.29f))
    }

    @Test fun suggestionShowsOnlyForANegativeSuggestionHalfADbAway() {
        assertTrue(showsSuggestion(-6.5f, 0f))
        assertFalse(showsSuggestion(-6.5f, -6.5f))
        assertFalse(showsSuggestion(-6.5f, -6.2f))
        assertTrue(showsSuggestion(-6.5f, -6.0f))
        assertFalse(showsSuggestion(0f, 3f))
    }

    @Test fun preampSnapsToHalfDbAndClampsToTheRange() {
        assertEquals(-5f, snapPreamp(-5.2f), 0f)
        assertEquals(-24f, snapPreamp(-30f), 0f)
        assertEquals(6f, snapPreamp(9f), 0f)
        assertEquals(0f, snapPreamp(0.2f), 0f)
    }
}
