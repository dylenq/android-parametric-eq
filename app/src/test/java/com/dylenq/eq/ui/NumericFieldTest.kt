package com.dylenq.eq.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumericFieldTest {
    private fun freq(s: String) = NumericField.parseFrequency(s)
    private fun gain(s: String) = NumericField.parseGain(s)
    private fun q(s: String) = NumericField.parseQ(s)

    private fun assertValue(expected: Float, r: FieldParse) {
        assertTrue("expected Value($expected) but got $r", r is FieldParse.Value)
        assertEquals(expected, (r as FieldParse.Value).value, 0f)
    }

    private fun assertClamped(expected: Float, message: String, r: FieldParse) {
        assertTrue("expected Clamped($expected) but got $r", r is FieldParse.Clamped)
        r as FieldParse.Clamped
        assertEquals(expected, r.value, 0f)
        assertEquals(message, r.message)
    }

    private fun assertInvalid(r: FieldParse) {
        assertTrue("expected Invalid but got $r", r is FieldParse.Invalid)
        assertEquals("Enter a number.", (r as FieldParse.Invalid).message)
    }

    @Test fun frequencyPlainAndUnits() {
        assertValue(1500f, freq("1500"))
        assertValue(1500f, freq("1.5k"))
        assertValue(1500f, freq("1.5 kHz"))
        assertValue(1500f, freq("1.5KHZ"))
        assertValue(1500f, freq("1500 Hz"))
        assertValue(1500f, freq("1500hz"))
        assertValue(1500f, freq("  1500  "))
        assertValue(63f, freq("63"))
        assertValue(31.5f, freq("31.5"))
        assertValue(20f, freq("20"))
        assertValue(20000f, freq("20000"))
        assertValue(20000f, freq("20k"))
    }

    @Test fun frequencyCommaDecimal() {
        assertValue(1500f, freq("1,5k"))
        assertValue(31.5f, freq("31,5"))
        assertValue(1500f, freq("1,5 kHz"))
    }

    @Test fun frequencyClamps() {
        assertClamped(20000f, NumericField.FREQ_MESSAGE, freq("99999"))
        assertClamped(20f, NumericField.FREQ_MESSAGE, freq("5"))
        assertClamped(20f, NumericField.FREQ_MESSAGE, freq("0"))
        assertClamped(20000f, NumericField.FREQ_MESSAGE, freq("25k"))
        // Negative numbers are out of range, not unparsable: they clamp, never pass through.
        assertClamped(20f, NumericField.FREQ_MESSAGE, freq("-5k"))
        assertEquals("Frequency limited to 20 Hz to 20 kHz.", NumericField.FREQ_MESSAGE)
    }

    @Test fun frequencyRejectsHostileAndMalformedText() {
        for (s in listOf("abc", "", " ", "-", "NaN", "Infinity", "-Infinity", "0x1p3", "0x10", "1.5f", "1e400", "1e3", "k", "hz", "khz", ".", ",", "1.5.5", "1 5", "1500 kg", "--5", "+")) {
            val r = freq(s)
            assertTrue("'$s' parsed as $r", r is FieldParse.Invalid)
        }
    }

    @Test fun overLongTextIsRejectedBeforeParsing() {
        assertInvalid(freq("1".repeat(17)))
        assertInvalid(gain("1".repeat(40)))
        assertInvalid(q("0." + "1".repeat(30)))
        assertValue(1500f, freq("0000000000001500"))
    }

    @Test fun gainAcceptedForms() {
        assertValue(-3.5f, gain("-3.5"))
        assertValue(3f, gain("+3"))
        assertValue(-3.5f, gain("−3.5"))
        assertValue(3f, gain("3 dB"))
        assertValue(3.5f, gain("3.5dB"))
        assertValue(3.5f, gain("3,5"))
        assertValue(0f, gain("0"))
        assertValue(24f, gain("24"))
        assertValue(-24f, gain("-24"))
        assertValue(3.3f, gain("3.34"))
    }

    @Test fun gainNegativeZeroIsPositiveZero() {
        val r = gain("-0") as FieldParse.Value
        assertEquals(0, r.value.toRawBits())
    }

    @Test fun gainClampsAndRejects() {
        assertClamped(24f, NumericField.GAIN_MESSAGE, gain("99"))
        assertClamped(-24f, NumericField.GAIN_MESSAGE, gain("-99"))
        assertClamped(-24f, NumericField.GAIN_MESSAGE, gain("−25"))
        assertEquals("Gain limited to −24 dB to +24 dB.", NumericField.GAIN_MESSAGE)
        for (s in listOf("abc", "", "-", "NaN", "Infinity", "0x1p3", "1e400", "dB", "3 k", "3 hz")) assertInvalid(gain(s))
    }

    @Test fun qAcceptedForms() {
        assertValue(0.71f, q(".71"))
        assertValue(1f, q("1"))
        assertValue(1.5f, q("1,5"))
        assertValue(0.71f, q("0.707"))
        assertValue(0.1f, q("0.1"))
        assertValue(18f, q("18"))
        assertValue(1.23f, q("1.2345"))
    }

    @Test fun qClampsAndRejects() {
        assertClamped(0.1f, NumericField.Q_MESSAGE, q("0"))
        assertClamped(0.1f, NumericField.Q_MESSAGE, q("0.05"))
        assertClamped(18f, NumericField.Q_MESSAGE, q("50"))
        assertClamped(0.1f, NumericField.Q_MESSAGE, q("-1"))
        assertEquals("Q limited to 0.1 to 18.", NumericField.Q_MESSAGE)
        for (s in listOf("abc", "", "-", "NaN", "Infinity", "1e5", "0x2", "1.5f", "1.5 k", "q")) assertInvalid(q(s))
    }

    @Test fun formatFrequency() {
        assertEquals("63", NumericField.formatFrequency(63f))
        assertEquals("31.5", NumericField.formatFrequency(31.5f))
        assertEquals("1250", NumericField.formatFrequency(1250f))
        assertEquals("20000", NumericField.formatFrequency(20000f))
        assertEquals("20", NumericField.formatFrequency(20f))
        assertEquals("100", NumericField.formatFrequency(99.96f))
        assertEquals("1235", NumericField.formatFrequency(1234.5f))
    }

    @Test fun formatGain() {
        assertEquals("3.5", NumericField.formatGain(3.5f))
        assertEquals("-3.5", NumericField.formatGain(-3.5f))
        assertEquals("0", NumericField.formatGain(0f))
        assertEquals("0", NumericField.formatGain(-0f))
        assertEquals("0", NumericField.formatGain(-0.04f))
        assertEquals("24", NumericField.formatGain(24f))
        assertEquals("-24", NumericField.formatGain(-24f))
    }

    @Test fun formatQ() {
        assertEquals("1.00", NumericField.formatQ(1f))
        assertEquals("0.71", NumericField.formatQ(0.707f))
        assertEquals("18.00", NumericField.formatQ(18f))
        assertEquals("0.10", NumericField.formatQ(0.1f))
        assertEquals("1.23", NumericField.formatQ(1.2345f))
    }

    @Test fun formatThenParseRoundTrips() {
        for (hz in floatArrayOf(20f, 31.5f, 63f, 250f, 1250f, 20000f)) assertValue(hz, freq(NumericField.formatFrequency(hz)))
        for (db in floatArrayOf(-24f, -3.5f, 0f, 0.1f, 12.3f, 24f)) assertValue(db, gain(NumericField.formatGain(db)))
        for (qq in floatArrayOf(0.1f, 0.71f, 1f, 4.25f, 18f)) assertValue(qq, q(NumericField.formatQ(qq)))
    }

    private inline fun withLocale(l: Locale, block: () -> Unit) {
        val old = Locale.getDefault()
        Locale.setDefault(l)
        try { block() } finally { Locale.setDefault(old) }
    }

    @Test fun behaviourIsLocaleIndependent() {
        for (l in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE, Locale("ar"))) {
            withLocale(l) {
                assertEquals("31.5", NumericField.formatFrequency(31.5f))
                assertEquals("-3.5", NumericField.formatGain(-3.5f))
                assertEquals("0.71", NumericField.formatQ(0.707f))
                assertValue(1500f, freq("1,5k"))
                assertValue(1500f, freq("1.5k"))
                assertValue(-3.5f, gain("-3,5"))
                assertValue(-3.5f, gain("-3.5"))
                assertValue(1.5f, q("1,5"))
                assertValue(1.5f, q("1.5"))
            }
        }
    }

    @Test fun shouldCommitOnlyWhenTextChanged() {
        assertFalse(NumericField.shouldCommit("1.23", "1.23"))
        assertFalse(NumericField.shouldCommit("", ""))
        assertTrue(NumericField.shouldCommit("1.23", "1.234"))
        assertTrue(NumericField.shouldCommit("1.23", ""))
    }
}
