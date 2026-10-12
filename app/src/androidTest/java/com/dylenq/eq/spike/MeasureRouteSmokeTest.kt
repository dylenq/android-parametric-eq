package com.dylenq.eq.spike

import android.Manifest
import android.media.audiofx.Visualizer
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Decides the measurement route. Absence of a route is a recorded result, never a failure;
 * only harness errors (exceptions, permission) fail this test.
 */
@RunWith(AndroidJUnit4::class)
class MeasureRouteSmokeTest {
    @get:Rule
    val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun decideRoute() {
        val args = InstrumentationRegistry.getArguments()
        val emuAudio = args.getString("emu_audio") ?: "off"
        val bands = 64
        val rate = ToneRig.nativeRate()
        Log.i("SPIKE-ROUTE", "native_rate=$rate")
        val cutoffs = SpikeDp.logGrid(bands, rate)
        val rig = ToneRig(rate, 1000f)
        var dp: android.media.audiofx.DynamicsProcessing? = null
        var v0: Visualizer? = null
        var vs: Visualizer? = null
        try {
            v0 = ToneRig.openVisualizer(0)
            vs = ToneRig.openVisualizer(rig.sessionId)

            val silence = ToneRig.measureMb(v0, "silence_v0")

            dp = SpikeDp.create(rig.sessionId, bands, 10f, rate)
            rig.start()
            val toneFlat = ToneRig.measureMb(v0, "tone_flat_v0")
            val sessFlat = ToneRig.measureMb(vs, "tone_flat_vsess")

            SpikeDp.setAllGainsDb(dp, bands, 6f, cutoffs)
            val toneBoost = ToneRig.measureMb(v0, "tone_plus6_v0")
            val sessBoost = ToneRig.measureMb(vs, "tone_plus6_vsess")

            val v0Delta = toneBoost - toneFlat
            val sessDelta = sessBoost - sessFlat
            val routeOk = silence <= -6000 && toneFlat > -6000 && v0Delta in 500..700
            val decision = if (routeOk) "visualizer0" else "none"
            Log.i(
                "SPIKE-ROUTE",
                "SPIKE-ROUTE decision=$decision silence_mb=$silence tone_flat_mb=$toneFlat " +
                    "v0_delta_mb=$v0Delta same_session_delta_mb=$sessDelta emu_audio=$emuAudio"
            )
        } finally {
            try { dp?.release() } catch (_: Throwable) {}
            try { v0?.release() } catch (_: Throwable) {}
            try { vs?.release() } catch (_: Throwable) {}
            rig.release()
        }
    }
}
