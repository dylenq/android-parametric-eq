package com.dylenq.eq.ui

import com.dylenq.eq.service.TrackingState
import com.dylenq.eq.shizuku.GrantResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingCopyTest {
    private val states = listOf(
        TrackingState.Off,
        TrackingState.NeedsPermission,
        TrackingState.Working(0),
        TrackingState.Working(1),
        TrackingState.Working(3),
        TrackingState.NeedsReboot,
        TrackingState.Unsupported("no app ids in this Android version"),
    )
    private val results = listOf(
        GrantResult.Granted,
        GrantResult.Failed("Unknown permission"),
        GrantResult.NotInstalled,
        GrantResult.NotRunning,
        GrantResult.Refused,
    )

    @Test fun everyStateHasText() {
        for (s in states) assertTrue("$s", TrackingCopy.statusLine(s).isNotBlank())
        assertEquals("Off", TrackingCopy.statusLine(TrackingState.Off))
    }

    @Test fun workingShowsTheCount() {
        assertTrue(TrackingCopy.statusLine(TrackingState.Working(3)).contains("3 apps"))
        assertTrue(TrackingCopy.statusLine(TrackingState.Working(1)).contains("1 app "))
    }

    @Test fun rebootTextIsExact() {
        assertEquals("Reboot the phone once.", TrackingCopy.statusLine(TrackingState.NeedsReboot))
    }

    @Test fun unsupportedShowsItsReason() {
        assertTrue(TrackingCopy.statusLine(TrackingState.Unsupported("no app ids")).contains("no app ids"))
    }

    @Test fun adbCommandUsesThePassedPackage() {
        assertEquals("adb shell pm grant com.dylenq.eq android.permission.DUMP", TrackingCopy.adbCommand("com.dylenq.eq"))
        assertEquals("adb shell pm grant com.x.y android.permission.DUMP", TrackingCopy.adbCommand("com.x.y"))
    }

    @Test fun everyShizukuResultHasADistinctSentence() {
        val msgs = results.map { TrackingCopy.shizukuMessage(it) }
        assertTrue(msgs.all { it.isNotBlank() })
        assertEquals(msgs.size, msgs.toSet().size)
        assertTrue(TrackingCopy.shizukuMessage(GrantResult.Failed("Unknown permission")).contains("Unknown permission"))
    }

    @Test fun noEmDashesAnywhere() {
        val all = states.map { TrackingCopy.statusLine(it) } + results.map { TrackingCopy.shizukuMessage(it) } +
            listOf(TrackingCopy.TITLE, TrackingCopy.SUPPORTING, TrackingCopy.WHY, TrackingCopy.NEEDS_COMPUTER, TrackingCopy.adbCommand("com.dylenq.eq"))
        for (t in all) assertFalse("em dash in: $t", t.contains('—') || t.contains('–'))
    }
}
