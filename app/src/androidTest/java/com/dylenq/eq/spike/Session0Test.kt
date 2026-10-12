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

/**
 * SPIKE-04: probe attaching DynamicsProcessing to session 0 (global output mix).
 *
 * Both outcomes (accepted / rejected) are valid findings; this test never asserts that attach works.
 *
 * Interpretation rule: Visualizer(0) is INSERT_FIRST and a DynamicsProcessing is INSERT_LAST, so on the
 * session-0 chain the Visualizer sits BEFORE the DP and cannot see its effect. A null delta measured with
 * route=visualizer0 therefore means ACCEPTED_UNOBSERVABLE, not "inert". ACCEPTED_OBSERVED is only reported
 * if a route actually saw a level change (e.g. playback-capture).
 */
@RunWith(AndroidJUnit4::class)
class Session0Test {
    @get:Rule
    val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val tag = "SPIKE-S0"

    @Test
    fun probeSession0() {
        val args = InstrumentationRegistry.getArguments()
        val route = args.getString("route") ?: "visualizer0"
        val variant = args.getString("variant") ?: "with_modify_audio"
        val bands = 64
        val rate = ToneRig.nativeRate()
        val cutoffs = SpikeDp.logGrid(bands, rate)

        var dp: DynamicsProcessing? = null
        var ctor = "ok"
        var hasControl = "n/a"
        var enableRc = "n/a"
        var delta = "n/a"
        var outcome = "REJECTED"
        var rig: ToneRig? = null
        var v: Visualizer? = null
        try {
            try {
                dp = DynamicsProcessing(0, 0, SpikeDp.config(bands, 10f))
                Log.i(tag, "ctor returned object, preEqBandCount=${dp.config.preEqBandCount}")
            } catch (t: Throwable) {
                ctor = "exception:${t.javaClass.name}:${t.message}"
                Log.i(tag, "ctor failed $ctor")
            }
            val d = dp
            if (d != null) {
                hasControl = d.hasControl().toString()
                enableRc = d.setEnabled(true).toString()
                Log.i(tag, "hasControl=$hasControl enableRc=$enableRc enabled=${d.enabled}")
                for (b in 0 until bands) d.setPreEqBandAllChannelsTo(b, DynamicsProcessing.EqBand(true, cutoffs[b], 0f))
                outcome = "ACCEPTED_UNOBSERVABLE"
                Log.i(tag, "SPIKE-S0 attached=true variant=$variant")
                // (c) tone on its own session, no DP on it; observe with the route.
                rig = ToneRig(rate, 1000f)
                rig.start()
                v = ToneRig.openVisualizer(0)
                val flat = ToneRig.levelDb(v, "s0_flat")
                SpikeDp.setAllGainsDb(d, bands, 6f, cutoffs)
                val boosted = ToneRig.levelDb(v, "s0_plus6")
                val dd = boosted - flat
                delta = "%.2f".format(dd)
                Log.i(tag, "session0 delta_db=$delta (Visualizer(0) is ordered before a session-0 DP)")
                if (route != "visualizer0" && Math.abs(dd - 6.0) <= 1.0) outcome = "ACCEPTED_OBSERVED"
                // (d) hold for host-side dumpsys.
                Thread.sleep(8000)
            } else {
                Log.i(tag, "SPIKE-S0 attached=false variant=$variant")
                Thread.sleep(3000)
            }
        } finally {
            try { dp?.release() } catch (_: Throwable) {}
            try { v?.release() } catch (_: Throwable) {}
            rig?.release()
        }
        Log.i(
            tag,
            "SPIKE-S0 variant=$variant outcome=$outcome ctor=$ctor hasControl=$hasControl " +
                "enableRc=$enableRc measured_delta_db=$delta route=$route"
        )
        assertTrue(true) // probe ran and cleaned up; attach success is not asserted
    }
}
