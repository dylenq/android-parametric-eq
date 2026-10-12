package com.dylenq.eq.ui

import kotlin.math.abs

/** A value on the curve and the frequency where it occurs. */
data class Extremum(val db: Float, val hz: Float)

/**
 * Statistics and strings derived from one sampled curve (UI-SPEC "Curve View": Target vs Actual,
 * Headroom chip; "Accessibility": curve semantics). The caption sentence lives only here.
 */
object CurveStats {
    /** Target is drawn from this difference. */
    const val TARGET_THRESHOLD_DB = 0.5f

    /** The caption is shown from this difference. */
    const val CAPTION_THRESHOLD_DB = 1.0f

    /** Headroom below this is "over". */
    const val OVER_THRESHOLD_DB = -0.05f

    fun peak(freqs: FloatArray, achieved: FloatArray): Extremum {
        var best = 0
        for (i in achieved.indices) if (achieved[i] > achieved[best]) best = i
        return Extremum(achieved[best], freqs[best])
    }

    fun trough(freqs: FloatArray, achieved: FloatArray): Extremum {
        var best = 0
        for (i in achieved.indices) if (achieved[i] < achieved[best]) best = i
        return Extremum(achieved[best], freqs[best])
    }

    /** Largest abs(ideal - achieved) and where it is. */
    fun maxDiff(freqs: FloatArray, ideal: FloatArray, achieved: FloatArray): Extremum {
        var best = 0
        var bestD = -1f
        for (i in achieved.indices) {
            val d = abs(ideal[i] - achieved[i])
            if (d > bestD) { bestD = d; best = i }
        }
        return Extremum(bestD.coerceAtLeast(0f), freqs[best])
    }

    fun targetVisible(maxDiffDb: Float) = maxDiffDb >= TARGET_THRESHOLD_DB

    fun captionVisible(maxDiffDb: Float) = maxDiffDb >= CAPTION_THRESHOLD_DB

    /** "Actual differs from target by up to 2.3 dB near 100 Hz." or null below 1.0 dB. */
    fun caption(freqs: FloatArray, ideal: FloatArray, achieved: FloatArray): String? {
        val d = maxDiff(freqs, ideal, achieved)
        if (!captionVisible(d.db)) return null
        return "Actual differs from target by up to ${DbFormat.magnitude(d.db)} dB near ${FreqFormat.withUnit(d.hz)}."
    }

    fun isOver(headroomDb: Float) = headroomDb < OVER_THRESHOLD_DB

    /** "Headroom 3.5 dB", or "Over by 2.5 dB" below -0.05 dB. */
    fun headroomText(headroomDb: Float): String =
        if (isOver(headroomDb)) {
            "Over by ${DbFormat.magnitude(-headroomDb)} dB"
        } else {
            "Headroom ${DbFormat.magnitude(headroomDb.coerceAtLeast(0f))} dB"
        }

    /** Index runs where achieved exceeds the clip level at -preamp. */
    fun clipSegments(achieved: FloatArray, preampDb: Float): List<IntRange> {
        val limit = -preampDb
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in achieved.indices) {
            if (achieved[i] > limit) {
                if (start < 0) start = i
            } else if (start >= 0) {
                out.add(start until i)
                start = -1
            }
        }
        if (start >= 0) out.add(start until achieved.size)
        return out
    }

    /** Semantics state text for the plot node. */
    fun stateDescription(freqs: FloatArray, ideal: FloatArray, achieved: FloatArray, bypass: Boolean): String {
        if (bypass) return "Equalizer is off. Curve not applied."
        val p = peak(freqs, achieved)
        val t = trough(freqs, achieved)
        val sb = StringBuilder()
        sb.append("Peak ${GainSpeech.format(p.db)} at ${FreqSpeech.format(p.hz)}. ")
        sb.append("Lowest ${GainSpeech.format(t.db)} at ${FreqSpeech.format(t.hz)}.")
        val d = maxDiff(freqs, ideal, achieved)
        if (captionVisible(d.db)) {
            sb.append(" Actual differs from target by up to ${GainSpeech.magnitude(d.db)} near ${FreqSpeech.format(d.hz)}.")
        }
        return sb.toString()
    }
}
