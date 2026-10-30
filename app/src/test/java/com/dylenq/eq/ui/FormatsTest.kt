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

    @Test fun dbMagnitudeNeverPrintsANegativeZero() {
        assertEquals("0.0", DbFormat.magnitude(0f))
        assertEquals("0.0", DbFormat.magnitude(-0.0f))
        assertEquals("0.0", DbFormat.magnitude(-0.04f))
        assertEquals("0.0", DbFormat.magnitude(0.04f))
        assertEquals("0.1", DbFormat.magnitude(-0.06f))
        assertEquals("3.5", DbFormat.magnitude(3.5f))
        assertEquals("12.0", DbFormat.magnitude(-12f))
    }

    @Test fun noFormatterPrintsANegativeZeroForAnyTinyValue() {
        val tiny = listOf(-0.0f, 0.0f, -1e-9f, 1e-9f, -0.01f, -0.049f, 0.049f, -0.0499f)
        for (v in tiny) {
            assertEquals("gain $v", "0 dB", GainFormat.format(v))
            assertEquals("speech $v", "0 decibels", GainSpeech.format(v))
            assertEquals("magnitude $v", "0.0", DbFormat.magnitude(v))
            assertEquals("speech magnitude $v", "0 decibels", GainSpeech.magnitude(v))
            for (text in listOf(CurveStats.headroomText(v), CurveStats.headroomText(-v), overByText(v))) {
                assertEquals("no minus in '$text'", false, text.contains("-") || text.contains("−"))
            }
        }
        assertEquals("Headroom 0.0 dB", CurveStats.headroomText(-0.0f))
    }

    private fun overByText(v: Float) = "Over by ${overBy(v)} dB"

    @Test fun balanceFormat() {
        assertEquals("L 30", BalanceFormat.format(-30))
        assertEquals("Centre", BalanceFormat.format(0))
        assertEquals("R 30", BalanceFormat.format(30))
        assertEquals("R 100", BalanceFormat.format(100))
        assertEquals("L 100", BalanceFormat.format(-100))
    }

    @Test fun balanceSpeech() {
        assertEquals("30 percent left", BalanceSpeech.format(-30))
        assertEquals("centred", BalanceSpeech.format(0))
        assertEquals("30 percent right", BalanceSpeech.format(30))
        assertEquals("100 percent right", BalanceSpeech.format(100))
    }

    @Test fun balancePercentMapping() {
        assertEquals(30, BalanceMath.toPercent(0.304f))
        assertEquals(0, BalanceMath.toPercent(-0.006f))
        assertEquals(0, BalanceMath.toPercent(0.01f))
        assertEquals(2, BalanceMath.toPercent(0.02f))
        assertEquals(100, BalanceMath.toPercent(5f))
        assertEquals(-100, BalanceMath.toPercent(-5f))
        assertEquals(0, BalanceMath.toPercent(Float.NaN))
    }

    @Test fun balanceToCoreClampsAndScales() {
        assertEquals(0.3f, BalanceMath.toCore(30), 1e-6f)
        assertEquals(1f, BalanceMath.toCore(250), 0f)
        assertEquals(-1f, BalanceMath.toCore(-250), 0f)
        assertEquals(0f, BalanceMath.toCore(0), 0f)
    }
}
