package com.dylenq.eq.ui

import android.content.Intent
import com.dylenq.eq.core.EqException
import com.dylenq.eq.core.ImportKind
import com.dylenq.eq.core.ImportReport

/** Copy for importing and exporting EQ files. Plain language; provider and path text is never shown. */
internal object ImportExportCopy {
    const val NOT_AN_EQ_FILE = "That doesn't look like an EQ file."
    const val TOO_LARGE = "That file is too large to be an EQ file."
    const val UNREADABLE = "Couldn't read that file."
    const val EXPORT_NOTE = "Tone and balance are not saved in this file."
    const val EXPORT_DISABLED = "Export needs the parametric EQ: the file holds parametric filters."
    const val EXPORT_FAILED = "Couldn't save the file."
    const val NO_PICKER = "No file picker is available on this device."

    /** The system document picker for an EQ file. Documents the user picks need no storage permission. */
    fun openDocumentIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/*"
        // Some providers label plain text files as octet-stream.
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream"))
    }

    /** The system "save as" picker, proposing "<preset or EQ> ParametricEQ.txt". */
    fun createDocumentIntent(presetName: String?): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "text/plain"
        putExtra(Intent.EXTRA_TITLE, "${presetName ?: "EQ"} ParametricEQ.txt")
    }

    /** "Imported HD650, 5 filters, 2 skipped, 1 clamped": skipped and clamped appear only when not zero. */
    fun importedMessage(name: String, filters: Int, skipped: Int, clamped: Int, noun: String = "filter"): String = buildString {
        append("Imported ").append(name).append(", ").append(filters).append(' ').append(noun)
        if (filters != 1) append('s')
        if (skipped > 0) append(", ").append(skipped).append(" skipped")
        if (clamped > 0) append(", ").append(clamped).append(" clamped")
    }

    fun importedMessage(report: ImportReport): String = importedMessage(
        report.name,
        report.filters.toInt(),
        report.skipped.toInt(),
        report.clamped.toInt(),
        noun = if (report.kind == ImportKind.GRAPHIC) "band" else "filter",
    )

    fun importErrorText(e: Throwable): String = when (e) {
        is EqException.NotAnEqFile -> NOT_AN_EQ_FILE
        is EqException.FileTooLarge -> TOO_LARGE
        is EqException.TooManyPresets -> PresetNames.TOO_MANY
        else -> UNREADABLE
    }
}
