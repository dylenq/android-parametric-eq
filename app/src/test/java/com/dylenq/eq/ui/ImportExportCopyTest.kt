package com.dylenq.eq.ui

import com.dylenq.eq.core.EqException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImportExportCopyTest {
    private val c = ImportExportCopy

    @Test fun plainSuccess() = assertEquals("Imported HD650, 5 filters", c.importedMessage("HD650", 5, 0, 0))

    @Test fun singularFilter() = assertEquals("Imported HD650, 1 filter", c.importedMessage("HD650", 1, 0, 0))

    @Test fun skippedIsShown() = assertEquals("Imported HD650, 5 filters, 2 skipped", c.importedMessage("HD650", 5, 2, 0))

    @Test fun clampedIsShown() = assertEquals("Imported HD650, 5 filters, 1 clamped", c.importedMessage("HD650", 5, 0, 1))

    @Test fun bothAreShownTogether() = assertEquals("Imported HD650, 5 filters, 2 skipped, 1 clamped", c.importedMessage("HD650", 5, 2, 1))

    @Test fun graphicUsesBands() = assertEquals("Imported Sub, 10 bands", c.importedMessage("Sub", 10, 0, 0, noun = "band"))

    @Test fun errorCopyPerCause() {
        assertEquals(c.NOT_AN_EQ_FILE, c.importErrorText(EqException.NotAnEqFile()))
        assertEquals(c.TOO_LARGE, c.importErrorText(EqException.FileTooLarge()))
        assertEquals("You can keep up to 100 presets. Delete one to save another.", c.importErrorText(EqException.TooManyPresets()))
        assertEquals(c.UNREADABLE, c.importErrorText(IOException("boom /secret/path")))
        assertEquals(c.UNREADABLE, c.importErrorText(SecurityException("provider text")))
    }

    @Test fun copyIsDistinctAndPlain() {
        val all = listOf(c.NOT_AN_EQ_FILE, c.TOO_LARGE, c.UNREADABLE, c.EXPORT_NOTE, c.EXPORT_DISABLED, c.EXPORT_FAILED)
        assertEquals(all.size, all.toSet().size)
        all.forEach {
            assertNotEquals("", it)
            assertFalse(it, it.contains('\u2014'))
        }
    }

    @Test fun exportCopyMatchesTheSpec() {
        assertEquals("Tone and balance are not saved in this file.", c.EXPORT_NOTE)
        assertEquals("Export needs the parametric EQ: the file holds parametric filters.", c.EXPORT_DISABLED)
    }
}
