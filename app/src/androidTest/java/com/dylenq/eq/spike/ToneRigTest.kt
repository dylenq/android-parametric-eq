package com.dylenq.eq.spike

import android.Manifest
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** SPIKE-02: band gains on the tone's own session change the measured output level. */
@RunWith(AndroidJUnit4::class)
class ToneRigTest {
    @get:Rule
    val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val tag = "SPIKE-02"
    private var allOk = true

    private fun case(
        name: String, base: Double, meas: Double, expected: Double, lo: Double, hi: Double,
        diagnostic: Boolean = false,
    ) {
        val d = meas - base
        val ok = d in lo..hi
        // Diagnostic cases explain a failure; they never count toward PASS/FAIL.
        if (!ok && !diagnostic) allOk = false
        Log.i(tag, "${if (diagnostic) "diag " else ""}case=$name baseline_db=${"%.2f".format(base)} measured_db=${"%.2f".format(meas)} " +
            "delta_db=${"%.2f".format(d)} expected_db=$expected ok=$ok")
    }

    @Test
    fun bandGainsChangeMeasuredOutput() {
        val route = InstrumentationRegistry.getArguments().getString("route") ?: "visualizer0"
        val bands = 64
        val rate = ToneRig.nativeRate()
        Log.i(tag, "native_rate=$rate bands=$bands frame_ms=10 route=$route")
        val cutoffs = SpikeDp.logGrid(bands, rate)
        val rig = ToneRig(rate, 1000f)
        var dp: DynamicsProcessing? = null
        var v: Visualizer? = null
        try {
            dp = SpikeDp.create(rig.sessionId, bands, 10f, rate)
            if (route == "none") {
                // Read-back only: proves the engine accepted parameters, NOT that output changed.
                SpikeDp.setAllGainsDb(dp, bands, 6f, cutoffs)
                var good = true
                for (b in listOf(0, 7, 21, 40, 63)) {
                    val g = dp.getPreEqBandByChannelIndex(0, b).gain
                    Log.i(tag, "readback band=$b gain=$g")
                    if (abs(g - 6f) > 0.01f) good = false
                }
                Log.i(tag, "SPIKE-02 result=readback_only route=none readback_ok=$good")
                assertTrue(good)
                return
            }
            check(route == "visualizer0") { "route $route not supported by this test" }
            v = ToneRig.openVisualizer(0)
            rig.start()
            val i1k = SpikeDp.bandIndexFor(cutoffs, 1000f)
            val i8k = SpikeDp.bandIndexFor(cutoffs, 8000f)
            Log.i(tag, "band_1k=$i1k cutoff=${cutoffs[i1k]} prev=${if (i1k > 0) cutoffs[i1k - 1] else 0f}; " +
                "band_8k=$i8k cutoff=${cutoffs[i8k]} prev=${cutoffs[i8k - 1]}")

            val base = ToneRig.levelDb(v, "flat")
            SpikeDp.setAllGainsDb(dp, bands, 6f, cutoffs)
            case("all_plus6", base, ToneRig.levelDb(v, "all_plus6"), 6.0, 5.0, 7.0)
            SpikeDp.setAllGainsDb(dp, bands, -6f, cutoffs)
            case("all_minus6", base, ToneRig.levelDb(v, "all_minus6"), -6.0, -7.0, -5.0)

            SpikeDp.setAllGainsDb(dp, bands, 0f, cutoffs)
            SpikeDp.setBandGainDb(dp, i1k, cutoffs[i1k], 6f)
            case("band1k_plus6_tone1k", base, ToneRig.levelDb(v, "band1k_plus6_tone1k"), 6.0, 4.5, 7.5)

            SpikeDp.setBandGainDb(dp, i1k, cutoffs[i1k], 0f)
            SpikeDp.setBandGainDb(dp, i8k, cutoffs[i8k], 6f)
            case("band8k_plus6_tone1k", base, ToneRig.levelDb(v, "band8k_plus6_tone1k"), 0.0, -1.0, 1.0)

            // Same 8 kHz band, tone moved to 8 kHz: the band now contains the tone.
            rig.setTone(8000f)
            SpikeDp.setBandGainDb(dp, i8k, cutoffs[i8k], 0f)
            val base8 = ToneRig.levelDb(v, "flat_tone8k")
            SpikeDp.setBandGainDb(dp, i8k, cutoffs[i8k], 6f)
            case("band8k_plus6_tone8k", base8, ToneRig.levelDb(v, "band8k_plus6_tone8k"), 6.0, 4.5, 7.5)

            // Diagnostic (not counted): band 35 holds only FFT bin 11 (centre 1031.25 Hz, bin width 93.75 Hz
            // at the 512-sample block). A 1000 Hz tone straddles bins 10/11, so only part of it is in band 35.
            // Put the tone at the bin centre and boost the same single band.
            SpikeDp.setBandGainDb(dp, i8k, cutoffs[i8k], 0f)
            rig.setTone(1031f)
            val base1031 = ToneRig.levelDb(v, "flat_tone1031")
            SpikeDp.setBandGainDb(dp, i1k, cutoffs[i1k], 6f)
            case("band1k_plus6_tone1031_bincentre", base1031, ToneRig.levelDb(v, "band1k_plus6_tone1031"),
                6.0, 4.5, 7.5, diagnostic = true)
        } finally {
            try { dp?.release() } catch (_: Throwable) {}
            try { v?.release() } catch (_: Throwable) {}
            rig.release()
        }
        Log.i(tag, "SPIKE-02 result=${if (allOk) "PASS" else "FAIL"} route=$route")
        assertTrue("one or more SPIKE-02 cases outside tolerance", allOk)
    }
}
