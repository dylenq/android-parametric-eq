package com.dylenq.eq.ui

internal object ImportExportCopy {
    const val NOT_AN_EQ_FILE = ""
    const val TOO_LARGE = ""
    const val UNREADABLE = ""
    const val EXPORT_NOTE = ""
    const val EXPORT_DISABLED = ""
    const val EXPORT_FAILED = ""

    fun importedMessage(name: String, filters: Int, skipped: Int, clamped: Int, noun: String = "filter"): String = TODO()

    fun importErrorText(e: Throwable): String = TODO()
}
