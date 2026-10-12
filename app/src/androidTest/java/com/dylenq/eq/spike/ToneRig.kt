package com.dylenq.eq.spike

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/** Looping stereo sine at -20 dBFS on its own audio session, plus Visualizer level measurement. */
class ToneRig(val sampleRate: Int = 48000, private var toneHz: Float = 1000f) {
    val sessionId: Int
    private var track: AudioTrack? = null
    private val frames get() = sampleRate // 1 s buffer; integer cycles for 1 kHz-ish tones

    init {
        val am = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sessionId = am.generateAudioSessionId()
        build()
    }

    private fun pcm(): ShortArray {
        val amp = 0.1 * 32767 // -20 dBFS
        val n = frames
        val out = ShortArray(n * 2)
        // choose whole number of cycles in the buffer so the loop point is seamless
        val cycles = Math.max(1, Math.round(toneHz.toDouble() * n / sampleRate).toInt())
        for (i in 0 until n) {
            val s = (amp * sin(2.0 * PI * cycles * i / n)).toInt().toShort()
            out[2 * i] = s
            out[2 * i + 1] = s
        }
        return out
    }

    private fun build() {
        val data = pcm()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setSessionId(sessionId)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        t.setLoopPoints(0, frames, -1)
        track = t
    }

    fun start() { track?.play() }
    fun stop() { track?.stop() }

    fun setTone(hz: Float) {
        val wasPlaying = track?.playState == AudioTrack.PLAYSTATE_PLAYING
        track?.release()
        toneHz = hz
        build()
        if (wasPlaying) start()
    }

    fun release() {
        try { track?.stop() } catch (_: Throwable) {}
        track?.release()
        track = null
    }

    companion object {
        /** Visualizer in PEAK_RMS measurement mode on [session] (0 = output mix). */
        fun openVisualizer(session: Int): Visualizer {
            val v = Visualizer(session)
            v.enabled = false
            v.measurementMode = Visualizer.MEASUREMENT_MODE_PEAK_RMS
            v.enabled = true
            return v
        }

        private fun readRms(v: Visualizer): Int {
            val m = Visualizer.MeasurementPeakRms()
            v.getMeasurementPeakRms(m)
            return m.mRms
        }

        /**
         * Settle 700 ms, discard one read, median of 3 reads 150 ms apart. Returns rms in millibels.
         */
        fun measureMb(v: Visualizer, label: String): Int {
            Thread.sleep(700)
            readRms(v)
            val reads = IntArray(3)
            for (i in 0 until 3) {
                Thread.sleep(150)
                reads[i] = readRms(v)
            }
            reads.sort()
            val med = reads[1]
            Log.i("SPIKE-ROUTE", "$label rms_mb=$med (reads=${reads.joinToString(",")})")
            return med
        }

        fun levelDb(v: Visualizer, label: String): Double = measureMb(v, label) / 100.0

        fun nativeRate(): Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
    }
}
