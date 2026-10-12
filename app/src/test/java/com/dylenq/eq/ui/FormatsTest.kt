package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {
    @Test fun freqNominalValues() {
        assertEquals("20", FreqFormat.format(20f))
        assertEquals("31.5", FreqFormat.format(31.5f))
        assertEquals("63", FreqFormat.format(63f))
        assertEquals("125", FreqFormat.format(125f))
        assertEquals("500", FreqFormat.format(500f))
        assertEquals("1k", FreqFormat.format(1000f))
        assertEquals("1.25k", FreqFormat.format(1250f))
        assertEquals("2k", FreqFormat.format(2000f))
        assertEquals("12.5k", FreqFormat.format(12500f))
        assertEquals("16k", FreqFormat.format(16000f))
        assertEquals("20k", FreqFormat.format(20000f))
    }

    @Test fun freqSnapsWithinThreePercent() {
        assertEquals("100", FreqFormat.format(100.2f))
        assertEquals("1k", FreqFormat.format(1029f))
        assertEquals("1k", FreqFormat.format(971f))
        assertEquals("63", FreqFormat.format(62f))
    }

    @Test fun freqOtherwiseThreeSignificantDigits() {
        assertEquals("1.35k", FreqFormat.format(1347f))
        assertEquals("450", FreqFormat.format(450f))
        assertEquals("91.3", FreqFormat.format(91.3f))
        assertEquals("1.1k", FreqFormat.format(1100f))
    }

    @Test fun freqRoundingToAThousandBecomesK() {
        assertEquals("1k", FreqFormat.format(999.6f))
    }

    @Test fun freqWithUnit() {
        assertEquals("100 Hz", FreqFormat.withUnit(100.2f))
        assertEquals("1 kHz", FreqFormat.withUnit(1000f))
        assertEquals("1.25 kHz", FreqFormat.withUnit(1250f))
    }

    @Test fun gainFormat() {
        assertEquals("+3.5 dB", GainFormat.format(3.5f))
        assertEquals("−2.0 dB", GainFormat.format(-2.0f))
        assertEquals("0 dB", GainFormat.format(0f))
        assertEquals("0 dB", GainFormat.format(-0.04f))
        assertEquals("0 dB", GainFormat.format(0.04f))
        assertEquals("+0.1 dB", GainFormat.format(0.06f))
        assertEquals("−12.0 dB", GainFormat.format(-12f))
    }

    @Test fun freqSpeech() {
        assertEquals("1 kilohertz", FreqSpeech.format(1000f))
        assertEquals("63 hertz", FreqSpeech.format(63f))
        assertEquals("1.25 kilohertz", FreqSpeech.format(1250f))
        assertEquals("31.5 hertz", FreqSpeech.format(31.5f))
    }

    @Test fun gainSpeech() {
        assertEquals("plus 3.5 decibels", GainSpeech.format(3.5f))
        assertEquals("minus 2 decibels", GainSpeech.format(-2f))
        assertEquals("0 decibels", GainSpeech.format(0f))
        assertEquals("plus 1 decibel", GainSpeech.format(1f))
        assertEquals("0 decibels", GainSpeech.format(-0.04f))
    }
}
