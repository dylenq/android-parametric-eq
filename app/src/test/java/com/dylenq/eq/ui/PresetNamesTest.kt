package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresetNamesTest {
    private val existing = listOf("Flat", "Bass", "Night")

    @Test fun validFreeName() = assertNull(PresetNames.check("Morning", existing))
    @Test fun blankRejected() = assertEquals(PresetNames.NAME_BLANK, PresetNames.check("   ", existing))
    @Test fun emptyRejected() = assertEquals(PresetNames.NAME_BLANK, PresetNames.check("", existing))
    @Test fun exactly40Allowed() = assertNull(PresetNames.check("a".repeat(40), existing))
    @Test fun fortyOneRejected() = assertEquals(PresetNames.NAME_INVALID, PresetNames.check("a".repeat(41), existing))
    @Test fun trimBeforeLength() = assertNull(PresetNames.check("  " + "a".repeat(40) + "  ", existing))
    @Test fun controlCharRejected() = assertEquals(PresetNames.NAME_INVALID, PresetNames.check("a\tb", existing))
    @Test fun caseInsensitiveTaken() = assertEquals(PresetNames.NAME_TAKEN, PresetNames.check("night", existing))
    @Test fun builtInTaken() = assertEquals(PresetNames.NAME_TAKEN, PresetNames.check("BASS", existing))
    @Test fun ownNameExcluded() = assertNull(PresetNames.check("NIGHT", existing, ownName = "Night"))
    @Test fun otherNameStillTakenWithOwn() = assertEquals(PresetNames.NAME_TAKEN, PresetNames.check("flat", existing, ownName = "Night"))
    @Test fun codePointsCounted() = assertNull(PresetNames.check("🎧".repeat(40), existing))
}
