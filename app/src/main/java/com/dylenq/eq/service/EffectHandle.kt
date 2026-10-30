package com.dylenq.eq.service

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import com.dylenq.eq.core.EngineParams

interface EffectHandle {
    fun setBand(index: Int, cutoffHz: Float, gainDb: Float)
    /** Per-channel input gain; [CH_LEFT] / [CH_RIGHT]. */
    fun setInputGain(channel: Int, db: Float)
    fun setLimiter(params: EngineParams)
    /** True when the platform accepted the change. */
    fun setEnabled(on: Boolean): Boolean
    fun hasControl(): Boolean
    fun release()

    fun readBandGain(i: Int): Float
    fun readBandCutoff(i: Int): Float
    fun readInputGain(channel: Int): Float
    fun readEnabled(): Boolean
    fun readLimiterThresholdDb(): Float
    fun readLimiterEnabled(): Boolean
}

/** DynamicsProcessing channel indices; confirmed on the emulator by BalanceMeasuredTest. */
const val CH_LEFT = 0
const val CH_RIGHT = 1

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
        b.setInputGainByChannelIndex(CH_LEFT, params.inputGainLeftDb)
        b.setInputGainByChannelIndex(CH_RIGHT, params.inputGainRightDb)
        b.setLimiterAllChannelsTo(limiterOf(params))
        val dp = DynamicsProcessing(EFFECT_PRIORITY, session, b.build())
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
        override fun setInputGain(channel: Int, db: Float) { dp.setInputGainbyChannel(channel, db) }
        override fun setLimiter(params: EngineParams) { dp.setLimiterAllChannelsTo(limiterOf(params)) }
        override fun setEnabled(on: Boolean): Boolean = dp.setEnabled(on) == AudioEffect.SUCCESS
        override fun hasControl(): Boolean = dp.hasControl()
        override fun release() { dp.release() }

        override fun readBandGain(i: Int): Float = dp.getPreEqBandByChannelIndex(0, i).gain
        override fun readBandCutoff(i: Int): Float = dp.getPreEqBandByChannelIndex(0, i).cutoffFrequency
        override fun readInputGain(channel: Int): Float = dp.getInputGainByChannelIndex(channel)
        override fun readEnabled(): Boolean = dp.enabled
        override fun readLimiterThresholdDb(): Float = dp.getLimiterByChannelIndex(0).threshold
        override fun readLimiterEnabled(): Boolean = dp.getLimiterByChannelIndex(0).isEnabled
    }

    companion object {
        /**
         * Effect priority passed to DynamicsProcessing. A higher value (Poweramp uses 1337) takes
         * control from other effects on the same session, including the player's own EQ. There is
         * no emulator evidence that a higher value helps, so the app stays cooperative at 0 until a
         * physical-phone test says otherwise.
         */
        internal const val EFFECT_PRIORITY = 0

        private const val CHANNELS = 2
    }
}
