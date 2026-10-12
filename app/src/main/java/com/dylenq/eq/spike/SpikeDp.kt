package com.dylenq.eq.spike

import android.media.audiofx.DynamicsProcessing
import kotlin.math.pow

/** DynamicsProcessing config/grid helper (spike; reused by Plan 03 and Phase 3). */
object SpikeDp {
    /** Strictly increasing log-spaced cutoffs, 20 Hz .. sampleRate/2 (last value is Nyquist). */
    fun logGrid(bands: Int, sampleRate: Int): FloatArray {
        val lo = 20.0
        val hi = sampleRate / 2.0
        val out = FloatArray(bands)
        for (i in 0 until bands) {
            val t = if (bands == 1) 1.0 else i.toDouble() / (bands - 1)
            out[i] = (lo * (hi / lo).pow(t)).toFloat()
        }
        out[bands - 1] = hi.toFloat()
        return out
    }

    fun config(bands: Int, frameMs: Float, channels: Int = 2): DynamicsProcessing.Config =
        DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            channels,
            true, bands,
            false, 0,
            false, 0,
            true
        ).setPreferredFrameDuration(frameMs).build()

    fun create(
        session: Int,
        bands: Int,
        frameMs: Float = 10f,
        sampleRate: Int = 48000,
    ): DynamicsProcessing {
        val dp = DynamicsProcessing(0, session, config(bands, frameMs))
        val grid = logGrid(bands, sampleRate)
        for (b in 0 until bands) {
            dp.setPreEqBandAllChannelsTo(b, DynamicsProcessing.EqBand(true, grid[b], 0f))
        }
        dp.enabled = true
        return dp
    }

    fun setAllGainsDb(dp: DynamicsProcessing, bands: Int, gainDb: Float, cutoffs: FloatArray) {
        for (b in 0 until bands) {
            dp.setPreEqBandAllChannelsTo(b, DynamicsProcessing.EqBand(true, cutoffs[b], gainDb))
        }
    }

    fun setBandGainDb(dp: DynamicsProcessing, bandIndex: Int, cutoffHz: Float, gainDb: Float) {
        dp.setPreEqBandAllChannelsTo(bandIndex, DynamicsProcessing.EqBand(true, cutoffHz, gainDb))
    }

    fun bandIndexFor(cutoffs: FloatArray, hz: Float): Int {
        for (i in cutoffs.indices) if (cutoffs[i] >= hz) return i
        return cutoffs.size - 1
    }
}
