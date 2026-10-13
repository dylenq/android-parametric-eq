package com.dylenq.eq.ui

import java.math.BigDecimal
import java.math.RoundingMode

/** The outcome of parsing typed text. Exact values are [Value]; out-of-range ones are [Clamped]; the rest [Invalid]. */
sealed interface FieldParse {
    /** In range, rounded to the field precision. */
    data class Value(val value: Float) : FieldParse

    /** Parsed but outside the limits: [value] is the limit, [message] the UI-SPEC text. */
    data class Clamped(val value: Float, val message: String) : FieldParse

    data class Invalid(val message: String) : FieldParse
}

/**
 * Pure parsing and formatting of the band-list text fields (phase 05, L-08, T-05-12). Typed text goes
 * through a strict pipeline: length guard, trim, unit strip, regex, toDouble, isFinite, scale, clamp,
 * round. It never uses toDoubleOrNull on raw text (which would accept "NaN", "Infinity", hex floats
 * and exponents). Output always uses '.' as the decimal separator regardless of locale; input accepts
 * '.' or ',' as the decimal separator in any locale. A comma is always a decimal separator, never a
 * thousands separator ("1,500" is 1.5).
 */
object NumericField {
    const val FREQ_MESSAGE = "Frequency limited to 20 Hz to 20 kHz."
    const val GAIN_MESSAGE = "Gain limited to −24 dB to +24 dB."
    const val Q_MESSAGE = "Q limited to 0.1 to 18."
    const val INVALID_MESSAGE = "Enter a number."

    /** Longer text is rejected before any parsing (T-05-13). */
    const val MAX_LENGTH = 16

    private val NUMBER = Regex("^[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)$")

    /** Trim, minus sign unification, lower case; null when too long. */
    private fun normalise(text: String): String? {
        if (text.length > MAX_LENGTH) return null
        return text.trim().replace('−', '-').lowercase()
    }

    /** The finite double of a strictly numeric string, else null. */
    private fun number(s: String): Double? {
        if (!NUMBER.matches(s)) return null
        val d = s.replace(',', '.').toDouble()
        return if (d.isFinite()) d else null
    }

    private fun round(x: Double, scale: Int): Float =
        BigDecimal(x).setScale(scale, RoundingMode.HALF_UP).toFloat() + 0f

    fun parseFrequency(text: String): FieldParse {
        var s = normalise(text) ?: return FieldParse.Invalid(INVALID_MESSAGE)
        var factor = 1.0
        when {
            s.endsWith("khz") -> { s = s.dropLast(3); factor = 1000.0 }
            s.endsWith("hz") -> s = s.dropLast(2)
            s.endsWith("k") -> { s = s.dropLast(1); factor = 1000.0 }
        }
        val d = number(s.trim()) ?: return FieldParse.Invalid(INVALID_MESSAGE)
        val hz = d * factor
        if (!hz.isFinite()) return FieldParse.Invalid(INVALID_MESSAGE)
        val min = ParametricMath.MIN_HZ.toDouble()
        val max = ParametricMath.MAX_HZ.toDouble()
        if (hz < min) return FieldParse.Clamped(ParametricMath.MIN_HZ, FREQ_MESSAGE)
        if (hz > max) return FieldParse.Clamped(ParametricMath.MAX_HZ, FREQ_MESSAGE)
        val r = round(hz, if (hz < 100.0) 1 else 0).coerceIn(ParametricMath.MIN_HZ, ParametricMath.MAX_HZ)
        return FieldParse.Value(r)
    }

    fun parseGain(text: String): FieldParse {
        var s = normalise(text) ?: return FieldParse.Invalid(INVALID_MESSAGE)
        if (s.endsWith("db")) s = s.dropLast(2)
        val d = number(s.trim()) ?: return FieldParse.Invalid(INVALID_MESSAGE)
        val limit = ParametricMath.MAX_GAIN_DB.toDouble()
        if (d > limit) return FieldParse.Clamped(ParametricMath.MAX_GAIN_DB, GAIN_MESSAGE)
        if (d < -limit) return FieldParse.Clamped(-ParametricMath.MAX_GAIN_DB, GAIN_MESSAGE)
        return FieldParse.Value(round(d, 1).coerceIn(-ParametricMath.MAX_GAIN_DB, ParametricMath.MAX_GAIN_DB))
    }

    fun parseQ(text: String): FieldParse {
        val s = normalise(text) ?: return FieldParse.Invalid(INVALID_MESSAGE)
        val d = number(s) ?: return FieldParse.Invalid(INVALID_MESSAGE)
        // Compared as decimal literals: 0.1f widened to double is 0.10000000149, which would clamp "0.1".
        if (d < 0.1) return FieldParse.Clamped(ParametricMath.MIN_Q, Q_MESSAGE)
        if (d > 18.0) return FieldParse.Clamped(ParametricMath.MAX_Q, Q_MESSAGE)
        return FieldParse.Value(round(d, 2).coerceIn(ParametricMath.MIN_Q, ParametricMath.MAX_Q))
    }

    private fun plain(x: Float, scale: Int, keepZeros: Boolean): String {
        if (!x.isFinite()) return "0"
        var b = BigDecimal(x.toDouble()).setScale(scale, RoundingMode.HALF_UP)
        if (!keepZeros) b = b.stripTrailingZeros()
        return b.toPlainString()
    }

    /** "63", "31.5", "1250": one decimal only below 100 Hz and only when needed. */
    fun formatFrequency(hz: Float): String = plain(hz, if (hz < 100f) 1 else 0, keepZeros = false)

    /** "3.5", "-3.5" (ASCII hyphen-minus), "0". */
    fun formatGain(db: Float): String = plain(db, 1, keepZeros = false)

    /** Always two decimals: "0.71", "1.00", "18.00". */
    fun formatQ(q: Float): String = plain(q, 2, keepZeros = true)

    /** A blur with the text unchanged since focus is a no-op: no commit, no rewrite of the stored value. */
    fun shouldCommit(textAtFocus: String, currentText: String): Boolean = textAtFocus != currentText
}
