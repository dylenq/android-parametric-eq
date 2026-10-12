package com.dylenq.eq.ui

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.roundToInt

private fun trimmed(x: Double): String =
    BigDecimal(x).round(MathContext(3)).stripTrailingZeros().toPlainString()

/**
 * Frequency label per UI-SPEC "Graphic Mode: Sliders" (Frequency label). Values within 3% of an
 * ISO third-octave nominal snap to it; others use three significant digits.
 */
object FreqFormat {
    /** The 31 ISO third-octave centres (the core's 31-band centres, nominal values). */
    val NOMINALS = floatArrayOf(
        20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
        800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f,
        12500f, 16000f, 20000f,
    )

    fun format(hz: Float): String {
        val snapped = NOMINALS.firstOrNull { abs(hz / it - 1f) <= 0.03f } ?: hz
        val x = snapped.toDouble()
        val s = trimmed(x)
        return if (s.toDouble() >= 1000.0) trimmed(x / 1000.0) + "k" else s
    }

    /** "100 Hz" or "1 kHz": the label with its unit. */
    fun withUnit(hz: Float): String {
        val f = format(hz)
        return if (f.endsWith("k")) f.dropLast(1) + " kHz" else "$f Hz"
    }
}

/** Gain label: one decimal, explicit sign, U+2212 minus, unit. Exactly zero has no sign or decimal. */
object GainFormat {
    fun format(db: Float): String {
        val t = (db * 10f).roundToInt()
        if (t == 0) return "0 dB"
        val sign = if (t > 0) "+" else "−"
        val a = abs(t)
        return "$sign${a / 10}.${a % 10} dB"
    }
}

/**
 * Unsigned one-decimal magnitude for headroom, "over by" and caption texts ("3.5", "0.0"). The value is
 * rounded to tenths first and printed from the integer, so a result of zero never carries a sign: -0.0f,
 * -0.04f and 0.04f all print "0.0" (UI-SPEC: no sign at zero; the chip text has no sign of its own).
 */
object DbFormat {
    fun magnitude(db: Float): String {
        val t = abs((db * 10f).roundToInt())
        return "${t / 10}.${t % 10}"
    }
}

/** Spoken frequency, for content descriptions. */
object FreqSpeech {
    fun format(hz: Float): String {
        val f = FreqFormat.format(hz)
        return if (f.endsWith("k")) f.dropLast(1) + " kilohertz" else "$f hertz"
    }
}

/** Spoken gain: "plus 3.5 decibels", "minus 2 decibels", "0 decibels". */
object GainSpeech {
    fun format(db: Float): String {
        val t = (db * 10f).roundToInt()
        if (t == 0) return "0 decibels"
        val a = abs(t)
        val num = if (a % 10 == 0) "${a / 10}" else "${a / 10}.${a % 10}"
        return "${if (t > 0) "plus" else "minus"} $num ${if (a == 10) "decibel" else "decibels"}"
    }

    /** Unsigned magnitude, e.g. "2.3 decibels". */
    fun magnitude(db: Float): String {
        val a = abs((db * 10f).roundToInt())
        val num = if (a % 10 == 0) "${a / 10}" else "${a / 10}.${a % 10}"
        return "$num ${if (a == 10) "decibel" else "decibels"}"
    }
}
