package com.dylenq.eq.ui

import com.dylenq.eq.core.Band
import java.util.Locale

/** The spoken text of a node's semantics child (UI-SPEC "Accessibility": Plot node). */
object NodeSpeech {
    /** "Band 3, Peak" ([index] is zero based). */
    fun contentDescription(index: Int, band: Band): String = "Band ${index + 1}, ${band.kind.label()}"

    /** "1.25 kilohertz, plus 3.5 decibels, Q 1.00, selected"; pass kinds omit the gain; ", disabled" when off. */
    fun stateDescription(band: Band, selected: Boolean): String = buildString {
        append(FreqSpeech.format(band.freq))
        if (band.kind.hasGain()) append(", ").append(GainSpeech.format(band.gainDb))
        append(", Q ").append(String.format(Locale.US, "%.2f", band.q))
        if (!band.enabled) append(", disabled")
        if (selected) append(", selected")
    }
}
