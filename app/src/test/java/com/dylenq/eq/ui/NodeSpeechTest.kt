package com.dylenq.eq.ui

import com.dylenq.eq.core.Band
import com.dylenq.eq.core.FilterKind
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeSpeechTest {
    private fun band(kind: FilterKind, freq: Float, gain: Float, q: Float, enabled: Boolean = true) =
        Band(kind, freq, gain, q, enabled)

    @Test fun contentDescriptionNumbersFromOneAndSpeaksTheKind() {
        assertEquals("Band 3, Peak", NodeSpeech.contentDescription(2, band(FilterKind.PEAK, 1000f, 0f, 1f)))
        assertEquals("Band 1, Low shelf", NodeSpeech.contentDescription(0, band(FilterKind.LOW_SHELF, 80f, 0f, 0.7f)))
        assertEquals("Band 7, High shelf", NodeSpeech.contentDescription(6, band(FilterKind.HIGH_SHELF, 1e4f, 0f, 0.7f)))
        assertEquals("Band 2, Low pass", NodeSpeech.contentDescription(1, band(FilterKind.LOW_PASS, 8000f, 0f, 0.71f)))
        assertEquals("Band 16, High pass", NodeSpeech.contentDescription(15, band(FilterKind.HIGH_PASS, 30f, 0f, 0.71f)))
    }

    @Test fun stateDescriptionMatchesTheSpecExample() {
        assertEquals(
            "1.25 kilohertz, plus 3.5 decibels, Q 1.00, selected",
            NodeSpeech.stateDescription(band(FilterKind.PEAK, 1250f, 3.5f, 1f), selected = true),
        )
    }

    @Test fun notSelectedHasNoSuffix() {
        assertEquals(
            "250 hertz, minus 2 decibels, Q 0.50",
            NodeSpeech.stateDescription(band(FilterKind.PEAK, 250f, -2f, 0.5f), selected = false),
        )
    }

    @Test fun passKindsOmitTheGain() {
        assertEquals(
            "8 kilohertz, Q 0.71",
            NodeSpeech.stateDescription(band(FilterKind.LOW_PASS, 8000f, 6f, 0.71f), selected = false),
        )
        assertEquals(
            "30 hertz, Q 0.71",
            NodeSpeech.stateDescription(band(FilterKind.HIGH_PASS, 30f, -6f, 0.71f), selected = false),
        )
    }

    @Test fun disabledIsAddedBeforeSelected() {
        assertEquals(
            "1 kilohertz, 0 decibels, Q 1.00, disabled, selected",
            NodeSpeech.stateDescription(band(FilterKind.PEAK, 1000f, 0f, 1f, enabled = false), selected = true),
        )
        assertEquals(
            "1 kilohertz, 0 decibels, Q 1.00, disabled",
            NodeSpeech.stateDescription(band(FilterKind.PEAK, 1000f, 0f, 1f, enabled = false), selected = false),
        )
    }

    @Test fun qIsAlwaysTwoDecimals() {
        assertEquals(
            "100 hertz, plus 0.1 decibels, Q 18.00",
            NodeSpeech.stateDescription(band(FilterKind.PEAK, 100f, 0.1f, 18f), selected = false),
        )
        assertEquals(
            "100 hertz, plus 1 decibel, Q 0.10",
            NodeSpeech.stateDescription(band(FilterKind.PEAK, 100f, 1f, 0.1f), selected = false),
        )
    }
}
