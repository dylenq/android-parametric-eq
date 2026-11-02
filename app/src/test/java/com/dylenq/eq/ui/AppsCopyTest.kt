package com.dylenq.eq.ui

import com.dylenq.eq.service.AppEntry
import com.dylenq.eq.service.SessionInfo
import com.dylenq.eq.service.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppsCopyTest {
    private fun app(pkg: String, playing: Boolean = false, seen: Long = 0) =
        AppEntry(pkg, true, null, pkg, playing, if (playing) SessionState.OK else null, seen)

    @Test fun playingFirstThenMostRecentThenName() {
        val list = listOf(
            app("old", seen = 1),
            app("new", seen = 9),
            app("b.play", playing = true, seen = 2),
            app("a.play", playing = true, seen = 2),
            app("z", seen = 5),
        )
        assertEquals(listOf("a.play", "b.play", "new", "z", "old"), AppsCopy.sorted(list) { it }.map { it.pkg })
    }

    @Test fun equalRecencyBreaksTiesByShownName() {
        val list = listOf(app("p2", seen = 3), app("p1", seen = 3))
        val labels = mapOf("p1" to "Zed", "p2" to "alpha")
        assertEquals(listOf("p2", "p1"), AppsCopy.sorted(list) { labels.getValue(it) }.map { it.pkg })
    }

    @Test fun labelResolvesAndIsCached() {
        var calls = 0
        val l = AppLabels { calls++; "Player" }
        assertEquals("Player", l.label("com.x"))
        assertEquals("Player", l.label("com.x"))
        assertEquals(1, calls)
    }

    @Test fun labelFallsBackToPackageTextWhenMissingBlankOrThrowing() {
        assertEquals("com.gone", AppLabels { null }.label("com.gone"))
        assertEquals("com.blank", AppLabels { "  " }.label("com.blank"))
        assertEquals("com.boom", AppLabels { throw IllegalStateException("x") }.label("com.boom"))
    }

    @Test fun presetTextFallsBackToLiveEq() {
        val names = mapOf("user:warm" to "Warm")
        assertEquals("Live EQ (default)", AppsCopy.presetText(null, names))
        assertEquals("Warm", AppsCopy.presetText("user:warm", names))
        assertEquals("Live EQ (default)", AppsCopy.presetText("user:deleted", names))
    }

    @Test fun sessionRowAddsThePresetWhenThereIsOne() {
        val info = SessionInfo(7, "p", SessionState.OK)
        assertEquals("Session 7 · Equalizing", SessionCopy.supporting(info))
        assertEquals("Session 7 · Equalizing · Warm", SessionCopy.supporting(info, "Warm"))
    }

    @Test fun copyHasNoEmDashAndNamesTheRules() {
        val all = listOf(
            AppsCopy.DEFAULT_PRESET, AppsCopy.EMPTY_TITLE, AppsCopy.EMPTY_BODY, AppsCopy.FOLLOW_NOTE, AppsCopy.GLOBAL_NOTE,
            SessionCopy.stateText(SessionState.DISABLED),
        )
        all.forEach { assertFalse(it, it.contains('—')) }
        assertEquals("The list fills as apps start playing audio.", AppsCopy.EMPTY_BODY)
        assertEquals(true, AppsCopy.FOLLOW_NOTE.contains("do not follow the live EQ"))
        assertEquals(true, AppsCopy.GLOBAL_NOTE.contains("ignored"))
    }

    @Test fun countText() {
        assertEquals("1 app", AppsCopy.countText(1))
        assertEquals("3 apps", AppsCopy.countText(3))
    }
}
