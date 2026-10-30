package com.dylenq.eq.service

/**
 * Measures how many pre-EQ bands the platform DynamicsProcessing accepts. Called at most once per
 * process, on the effects worker, and never against a player's session.
 */
interface EngineProbe {
    /** Returns the accepted band count (at most [requested]). Throws when nothing could be measured. */
    fun measureMaxBands(requested: Int): Int

    companion object {
        /** Largest band count requested from the platform; matches the core's PROBE_BANDS. */
        const val PROBE_BANDS = 512
    }
}

/**
 * Builds throwaway DynamicsProcessing instances on fresh audio sessions, walking a descending ladder
 * until one constructs and reads back a band count. The session always comes from
 * generateAudioSessionId(), so no player is ever touched.
 */
class RealEngineProbe(
    private val audioManager: android.media.AudioManager,
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
) : EngineProbe {
    override fun measureMaxBands(requested: Int): Int {
        var last: Throwable? = null
        for (rung in LADDER) {
            if (rung > requested) continue
            try {
                // A rung only counts as a capacity limit when it fails again after one retry, so a
                // one-off engine hiccup does not cache a permanently lower cap.
                val got = retrying(
                    PROBE_ATTEMPTS, longArrayOf(PROBE_RETRY_MS), sleeper,
                    { attempt, t -> android.util.Log.i("EQ-SVC", "probe rung $rung retry $attempt: ${t.javaClass.name}: ${t.message}") },
                ) { tryRung(rung) }
                if (got > 0) return minOf(rung, got)
            } catch (t: Throwable) {
                android.util.Log.w("EQ-SVC", "probe rung $rung failed: ${t.javaClass.name}: ${t.message}")
                last = t
            }
        }
        throw last ?: IllegalStateException("no probe rung applicable for requested=$requested")
    }

    private fun tryRung(rung: Int): Int {
        val session = audioManager.generateAudioSessionId()
        var dp: android.media.audiofx.DynamicsProcessing? = null
        try {
            val b = android.media.audiofx.DynamicsProcessing.Config.Builder(
                android.media.audiofx.DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, rung, false, 0, false, 0, false,
            ).setPreferredFrameDuration(PROBE_FRAME_MS)
            val eq = android.media.audiofx.DynamicsProcessing.Eq(true, true, rung)
            for (i in 0 until rung) {
                eq.setBand(i, android.media.audiofx.DynamicsProcessing.EqBand(true, 20f + i * 20f, 0f))
            }
            b.setPreEqAllChannelsTo(eq)
            dp = android.media.audiofx.DynamicsProcessing(RealEffectFactory.EFFECT_PRIORITY, session, b.build())
            return dp.getPreEqByChannelIndex(0).bandCount
        } finally {
            try { dp?.release() } catch (_: Throwable) { }
        }
    }

    private companion object {
        val LADDER = intArrayOf(512, 384, 300, 256, 128, 64, 48, 32, 16, 8)
        const val PROBE_FRAME_MS = 85.3f
        const val PROBE_ATTEMPTS = 2
        const val PROBE_RETRY_MS = 100L
    }
}
