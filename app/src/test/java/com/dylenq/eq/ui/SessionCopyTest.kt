package com.dylenq.eq.ui

import com.dylenq.eq.service.SessionInfo
import com.dylenq.eq.service.SessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCopyTest {
    @Test fun stateTextsAreTheUiSpecCopy() {
        assertEquals("Equalizing", SessionCopy.stateText(SessionState.OK))
        assertEquals("Android didn't give EQ control of this session", SessionCopy.stateText(SessionState.NO_CONTROL))
        assertEquals("The effect couldn't be attached", SessionCopy.stateText(SessionState.FAILED))
        assertEquals("Covered by global mode", SessionCopy.stateText(SessionState.SUPPRESSED))
    }

    @Test fun blankPackageIsUnknownApp() {
        assertEquals("com.example.player", SessionCopy.rowTitle("com.example.player"))
        assertEquals("Unknown app", SessionCopy.rowTitle(""))
        assertEquals("Unknown app", SessionCopy.rowTitle("   "))
    }

    @Test fun supportingLineHasSessionIdAndState() {
        assertEquals("Session 4521 · Equalizing", SessionCopy.supporting(SessionInfo(4521, "p", SessionState.OK)))
        assertEquals(
            "Session 7 · Covered by global mode",
            SessionCopy.supporting(SessionInfo(7, "p", SessionState.SUPPRESSED)),
        )
    }

    @Test fun problemsFirstThenSuppressedThenOkEachByPackageThenId() {
        val list = listOf(
            SessionInfo(1, "b.ok", SessionState.OK),
            SessionInfo(2, "a.ok", SessionState.OK),
            SessionInfo(3, "z.sup", SessionState.SUPPRESSED),
            SessionInfo(4, "m.fail", SessionState.FAILED),
            SessionInfo(5, "a.nocontrol", SessionState.NO_CONTROL),
            SessionInfo(6, "a.ok", SessionState.OK),
        )
        assertEquals(listOf(5, 4, 3, 2, 6, 1), SessionCopy.sorted(list).map { it.id })
    }

    @Test fun countTextIsSingularOrPlural() {
        assertEquals("1 session", SessionCopy.countText(1))
        assertEquals("2 sessions", SessionCopy.countText(2))
        assertEquals("0 sessions", SessionCopy.countText(0))
    }
}
