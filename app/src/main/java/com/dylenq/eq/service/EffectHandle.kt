package com.dylenq.eq.service

import android.media.audiofx.DynamicsProcessing
import com.dylenq.eq.core.EngineParams

interface EffectHandle {
    fun setBand(index: Int, cutoffHz: Float, gainDb: Float)
    fun setInputGain(db: Float)
    fun setLimiter(params: EngineParams)
    fun setEnabled(on: Boolean)
    fun hasControl(): Boolean
    fun release()

    fun readBandGain(i: Int): Float
    fun readBandCutoff(i: Int): Float
    fun readInputGain(): Float
    fun readEnabled(): Boolean
    fun readLimiterThresholdDb(): Float
    fun readLimiterEnabled(): Boolean
}

interface EffectFactory {
    /** Throws on failure. */
    fun create(session: Int, params: EngineParams): EffectHandle
}

class RealEffectFactory : EffectFactory {
    override fun create(session: Int, params: EngineParams): EffectHandle {
        val n = params.cutoffsHz.size
        val b = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
            true, n, false, 0, false, 0, true,
        ).setPreferredFrameDuration(params.frameDurationMs)
        val eq = DynamicsProcessing.Eq(true, true, n)
        for (i in 0 until n) {
            eq.setBand(i, DynamicsProcessing.EqBand(true, params.cutoffsHz[i], params.gainsDb[i]))
        }
        b.setPreEqAllChannelsTo(eq)
        b.setInputGainAllChannelsTo(params.inputGainDb)
        b.setLimiterAllChannelsTo(limiterOf(params))
        val dp = DynamicsProcessing(0, session, b.build())
        try {
            dp.enabled = !params.bypass
        } catch (t: Throwable) {
            dp.release()
            throw t
        }
        return RealHandle(dp)
    }

    internal fun limiterOf(p: EngineParams) = DynamicsProcessing.Limiter(
        true, p.limiterEnabled, p.limiter.linkGroup.toInt(),
        p.limiter.attackMs, p.limiter.releaseMs, p.limiter.ratio,
        p.limiter.thresholdDb, p.limiter.postGainDb,
    )

    private inner class RealHandle(private val dp: DynamicsProcessing) : EffectHandle {
        override fun setBand(index: Int, cutoffHz: Float, gainDb: Float) {
            dp.setPreEqBandAllChannelsTo(index, DynamicsProcessing.EqBand(true, cutoffHz, gainDb))
        }
        override fun setInputGain(db: Float) { dp.setInputGainAllChannelsTo(db) }
        override fun setLimiter(params: EngineParams) { dp.setLimiterAllChannelsTo(limiterOf(params)) }
        override fun setEnabled(on: Boolean) { dp.enabled = on }
        override fun hasControl(): Boolean = dp.hasControl()
        override fun release() { dp.release() }

        override fun readBandGain(i: Int): Float = dp.getPreEqBandByChannelIndex(0, i).gain
        override fun readBandCutoff(i: Int): Float = dp.getPreEqBandByChannelIndex(0, i).cutoffFrequency
        override fun readInputGain(): Float = dp.getInputGainByChannelIndex(0)
        override fun readEnabled(): Boolean = dp.enabled
        override fun readLimiterThresholdDb(): Float = dp.getLimiterByChannelIndex(0).threshold
        override fun readLimiterEnabled(): Boolean = dp.getLimiterByChannelIndex(0).isEnabled
    }

    private companion object {
        const val CHANNELS = 2
    }
}
