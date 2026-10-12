package com.dylenq.eq.spike

import android.Manifest
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SPIKE-05 on the emulator. A: what band counts / frame durations the engine accepts.
 * B: fitted peaks versus the offline model. C: CPU snapshot. D: how one boosted band's delivered gain
 * depends on frame duration and band width. Phase 2 replaces the peaking helper with the real biquad code.
 */
@RunWith(AndroidJUnit4::class)
class BandSweepTest {
    @get:Rule
    val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val route get() = InstrumentationRegistry.getArguments().getString("route") ?: "visualizer0"

    // ---- model helpers (mirror spike-tools/band_model.py) ----
    private fun blockSize(frameMs: Double, sr: Int): Int {
        val samples = frameMs * sr / 1000.0
        var n = 8
        while (n < samples && n < 16384) n *= 2
        return n
    }

    private fun binStop(cutoff: Double, block: Int, sr: Int) = (0.5 + cutoff * block / sr).toInt()

    private fun peakingDb(f: Double, f0: Double, q: Double, gainDb: Double, sr: Int): Double {
        if (gainDb == 0.0) return 0.0
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f0 / sr
        val alpha = sin(w0) / (2 * q)
        val b0 = 1 + alpha * a; val b1 = -2 * cos(w0); val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a; val a1 = -2 * cos(w0); val a2 = 1 - alpha / a
        val w = 2 * PI * f / sr
        val nr = b0 + b1 * cos(w) + b2 * cos(2 * w); val ni = -(b1 * sin(w) + b2 * sin(2 * w))
        val dr = a0 + a1 * cos(w) + a2 * cos(2 * w); val di = -(a1 * sin(w) + a2 * sin(2 * w))
        return 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** Gain per band: RBJ magnitude at the geometric centre of the band's bin range. */
    private fun fitGains(cutoffs: DoubleArray, block: Int, sr: Int, f0: Double, q: Double, gainDb: Double): FloatArray {
        val binw = sr.toDouble() / block
        var next = 0
        val out = FloatArray(cutoffs.size)
        for (i in cutoffs.indices) {
            val stop = binStop(cutoffs[i], block, sr)
            val start = next
            next = stop + 1
            if (stop < start) { out[i] = 0f; continue }
            val top = minOf(stop, block / 2)
            val centre = sqrt(maxOf(start.toDouble(), 0.5) * binw * maxOf(top.toDouble(), 0.5) * binw)
            out[i] = peakingDb(centre, f0, q, gainDb, sr).toFloat()
        }
        return out
    }

    private fun adaptiveCutoffs(f0: Double, q: Double, block: Int, sr: Int, nSparse: Int, mult: Double): DoubleArray {
        val binw = sr.toDouble() / block
        val klo = maxOf(1, ((f0 - mult * f0 / q).coerceAtLeast(binw) / binw).toInt())
        val khi = kotlin.math.ceil((f0 + mult * f0 / q) / binw).toInt()
        val set = sortedSetOf<Double>()
        for (c in SpikeDp.logGrid(nSparse, sr)) if (c < klo * binw - binw || c > khi * binw + binw) set.add(c.toDouble())
        for (k in klo..khi) set.add(k * binw)
        set.add(sr / 2.0)
        val seen = HashSet<Int>()
        val out = ArrayList<Double>()
        for (c in set) if (seen.add(binStop(c, block, sr))) out.add(c)
        return out.toDoubleArray()
    }

    private fun createDp(session: Int, cutoffs: DoubleArray, frameMs: Float): DynamicsProcessing {
        val n = cutoffs.size
        val dp = DynamicsProcessing(0, session, SpikeDp.config(n, frameMs))
        for (b in 0 until n) dp.setPreEqBandAllChannelsTo(b, DynamicsProcessing.EqBand(true, cutoffs[b].toFloat(), 0f))
        dp.enabled = true
        return dp
    }

    private fun applyGains(dp: DynamicsProcessing, cutoffs: DoubleArray, gains: FloatArray) {
        for (b in cutoffs.indices) dp.setPreEqBandAllChannelsTo(b, DynamicsProcessing.EqBand(true, cutoffs[b].toFloat(), gains[b]))
    }

    @Test
    fun sweep() {
        val sr = ToneRig.nativeRate()
        Log.i("SPIKE-05A", "native_rate=$sr route=$route")
        engineAcceptance(sr)
        if (route == "none") {
            Log.i("SPIKE-05B", "SPIKE-05B skipped reason=route_none")
            return
        }
        confirmationCells(sr)
        cpuHold(sr)
        singleBandStudy(sr)
    }

    // ---- A ----
    private fun engineAcceptance(sr: Int) {
        val rig = ToneRig(sr, 1000f)
        try {
            for (n in listOf(16, 32, 64, 128, 256, 512, 1024, 2048)) {
                var dp: DynamicsProcessing? = null
                val t0 = SystemClock.elapsedRealtime()
                var ok = false; var count = -1; var err = "none"; var rb = ""
                try {
                    dp = SpikeDp.create(rig.sessionId, n, 10f, sr)
                    ok = true
                    count = dp.config.preEqBandCount
                    val mid = n / 2
                    val band = dp.getPreEqBandByChannelIndex(0, mid)
                    rb = " band$mid.cutoff=${band.cutoffFrequency}"
                } catch (t: Throwable) {
                    err = "${t.javaClass.simpleName}:${t.message}"
                }
                val ms = SystemClock.elapsedRealtime() - t0
                Log.i("SPIKE-05A", "bands=$n create_ms=$ms ok=$ok readback_count=$count err=$err$rb")
                try { dp?.release() } catch (_: Throwable) {}
            }
            for (fr in listOf(170.6f, 0.1f, 1000f)) {
                var dp: DynamicsProcessing? = null
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    dp = SpikeDp.create(rig.sessionId, 64, fr, sr)
                    Log.i("SPIKE-05A", "frame_ms_requested=$fr ok=true create_ms=${SystemClock.elapsedRealtime() - t0} " +
                        "readback_frame_ms=${dp.config.preferredFrameDuration} err=none")
                } catch (t: Throwable) {
                    Log.i("SPIKE-05A", "frame_ms_requested=$fr ok=false err=${t.javaClass.simpleName}:${t.message}")
                }
                try { dp?.release() } catch (_: Throwable) {}
            }
        } finally {
            rig.release()
        }
    }

    // ---- B ----
    private fun confirmationCells(sr: Int) {
        val rig = ToneRig(sr, 1000f)
        var v: Visualizer? = null
        try {
            v = ToneRig.openVisualizer(0)
            rig.start()
            val cells = listOf(10.0 to 64, 85.3 to 64, 85.3 to 256, 85.3 to 512, 170.6 to 256)
            val q = 8.0; val gdb = 12.0
            for (f0 in listOf(100f, 1000f, 5000f)) {
                rig.setTone(f0)
                for ((fr, nb) in cells) runCell(rig, v, sr, fr, "log", SpikeDp.logGrid(nb, sr).map { it.toDouble() }.toDoubleArray(), f0.toDouble(), q, gdb)
                val block = blockSize(85.3, sr)
                runCell(rig, v, sr, 85.3, "adaptive", adaptiveCutoffs(f0.toDouble(), q, block, sr, 16, 1.0), f0.toDouble(), q, gdb)
            }
        } finally {
            try { v?.release() } catch (_: Throwable) {}
            rig.release()
        }
    }

    private fun runCell(rig: ToneRig, v: Visualizer, sr: Int, frameMs: Double, grid: String, cuts: DoubleArray, f0: Double, q: Double, gdb: Double) {
        var dp: DynamicsProcessing? = null
        try {
            dp = createDp(rig.sessionId, cuts, frameMs.toFloat())
            val base = ToneRig.levelDb(v, "B_flat f=$f0 fr=$frameMs n=${cuts.size}")
            applyGains(dp, cuts, fitGains(cuts, blockSize(frameMs, sr), sr, f0, q, gdb))
            val meas = ToneRig.levelDb(v, "B_fit")
            val d = meas - base
            Log.i("SPIKE-05B", "grid=$grid frame_ms=$frameMs bands=${cuts.size} f0=${f0.toInt()} q=${q.toInt()} sr=$sr " +
                "target_db=$gdb measured_db=${"%.2f".format(d)} err_db=${"%.2f".format(d - gdb)}")
        } catch (t: Throwable) {
            Log.i("SPIKE-05B", "grid=$grid frame_ms=$frameMs bands=${cuts.size} f0=${f0.toInt()} error=${t.javaClass.simpleName}:${t.message}")
        } finally {
            try { dp?.release() } catch (_: Throwable) {}
        }
    }

    // ---- C ----
    private fun shell(cmd: String): String {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = ui.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun cpuHold(sr: Int) {
        val rig = ToneRig(sr, 1000f)
        var dp: DynamicsProcessing? = null
        try {
            val cuts = SpikeDp.logGrid(512, sr).map { it.toDouble() }.toDoubleArray()
            dp = createDp(rig.sessionId, cuts, 85.3f)
            rig.start()
            Log.i("SPIKE-05C", "hold start bands=512 frame_ms=85.3")
            Thread.sleep(3000)
            val top = shell("top -b -n 1 -m 25")
            val fl = shell("dumpsys media.audio_flinger")
            Thread.sleep(3000)
            val idx = fl.indexOf("Effect")
            Log.i("SPIKE-05C", "hold done")
            // CPU evidence written to the app's cache; the host pulls it from logcat lines below.
            for (l in top.lines().take(30)) Log.i("SPIKE-05C", "top| $l")
            for (l in fl.lines().filter { it.contains("Dynamics") || it.contains("dynproc", true) || it.contains("Effect Chain", true) }.take(40))
                Log.i("SPIKE-05C", "flinger| $l")
            Log.i("SPIKE-05C", "flinger_effect_section_index=$idx")
        } finally {
            try { dp?.release() } catch (_: Throwable) {}
            rig.release()
        }
    }

    // ---- D ----
    private fun singleBandStudy(sr: Int) {
        val rig = ToneRig(sr, 1031f)
        var v: Visualizer? = null
        try {
            v = ToneRig.openVisualizer(0)
            rig.start()
            // (a1) 64-band log grid, band containing 1 kHz +6 dB, tone 1000 Hz, across frame durations
            rig.setTone(1000f)
            val cut64 = SpikeDp.logGrid(64, sr).map { it.toDouble() }.toDoubleArray()
            val i1k = cut64.indexOfFirst { it >= 1000.0 }
            for (fr in listOf(10.0, 21.3, 42.6, 85.3, 170.6)) singleBand("D_a1_log64", rig, v, sr, fr, cut64, i1k, 1000.0)
            // (a2) fixed 100 Hz wide band centred on the tone (1031.25 Hz), across frame durations
            rig.setTone(1031f)
            for (fr in listOf(10.0, 21.3, 42.6, 85.3, 170.6)) {
                val block = blockSize(fr, sr); val binw = sr.toDouble() / block
                val a = Math.round((1031.25 - 50) / binw).toInt(); val b = Math.round((1031.25 + 50) / binw).toInt()
                val cuts = doubleArrayOf((a - 1) * binw, b * binw, sr / 2.0)
                singleBand("D_a2_100Hzwide", rig, v, sr, fr, cuts, 1, 1031.0, "bins=${b - a + 1}")
            }
            // (b) 10 ms frame, tone at bin centre, band width w bins
            val binw = sr.toDouble() / blockSize(10.0, sr)
            val kc = Math.round(1031.25 / binw).toInt()
            for (w in listOf(1, 3, 5, 9, 17)) {
                val h = w / 2
                val cuts = doubleArrayOf((kc - h - 1) * binw, (kc + h) * binw, sr / 2.0)
                singleBand("D_b_width", rig, v, sr, 10.0, cuts, 1, 1031.0, "bins=$w")
            }
        } finally {
            try { v?.release() } catch (_: Throwable) {}
            rig.release()
        }
    }

    private fun singleBand(tag: String, rig: ToneRig, v: Visualizer, sr: Int, frameMs: Double, cuts: DoubleArray, boosted: Int, toneHz: Double, extra: String = "") {
        var dp: DynamicsProcessing? = null
        try {
            dp = createDp(rig.sessionId, cuts, frameMs.toFloat())
            val base = ToneRig.levelDb(v, "$tag flat")
            val g = FloatArray(cuts.size); g[boosted] = 6f
            applyGains(dp, cuts, g)
            val d = ToneRig.levelDb(v, "$tag boosted") - base
            Log.i("SPIKE-05D", "case=$tag frame_ms=$frameMs block=${blockSize(frameMs, sr)} bands=${cuts.size} tone=$toneHz $extra target_db=6 delta_db=${"%.2f".format(d)}")
        } catch (t: Throwable) {
            Log.i("SPIKE-05D", "case=$tag frame_ms=$frameMs error=${t.javaClass.simpleName}:${t.message}")
        } finally {
            try { dp?.release() } catch (_: Throwable) {}
        }
    }
}
