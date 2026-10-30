package com.dylenq.eq.service

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.testsupport.FakeEffectFactory
import com.dylenq.eq.testsupport.FakeEngineProbe
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineProbeTest {
    private lateinit var ctx: Context
    private val prefNames = ArrayList<String>()
    private val runtimes = ArrayList<EqRuntime>()

    @Before fun setUp() { ctx = InstrumentationRegistry.getInstrumentation().targetContext }

    @After fun tearDown() {
        runtimes.forEach { it.shutdown() }
        prefNames.forEach { ctx.deleteSharedPreferences(it) }
    }

    private fun newSettings(): SettingsStore {
        val n = "eq_probe_test_${System.nanoTime()}"
        prefNames.add(n)
        return SettingsStore(ctx, n)
    }

    private fun runtime(settings: SettingsStore, factory: FakeEffectFactory, probe: EngineProbe?): EqRuntime =
        EqRuntime(factory, null, settings, probe).also { runtimes.add(it) }

    private fun applyOnce(rt: EqRuntime, factory: FakeEffectFactory, session: Int = 1001) {
        rt.post(SessionIntent.Event.Open(session, "com.example.player"))
        waitFor { factory.creates().any { it.first == session } }
    }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out")
            Thread.sleep(20)
        }
    }

    @Test fun probe_runs_once_and_result_is_cached() {
        val s = newSettings()
        val probe = FakeEngineProbe(300)
        val f = FakeEffectFactory()
        val rt = runtime(s, f, probe)
        applyOnce(rt, f)
        assertEquals(1, probe.calls)
        assertEquals(300, s.engineMaxBands)
        assertEquals(Build.FINGERPRINT, s.engineProbeFingerprint)
        rt.shutdown()
        val probe2 = FakeEngineProbe(300)
        val f2 = FakeEffectFactory()
        val rt2 = runtime(s, f2, probe2)
        applyOnce(rt2, f2)
        assertEquals(0, probe2.calls)
    }

    @Test fun probe_result_caps_engine_params() {
        val s = newSettings()
        val f = FakeEffectFactory()
        val rt = runtime(s, f, FakeEngineProbe(64))
        applyOnce(rt, f)
        val p = f.creates().first { it.first == 1001 }.second
        assertTrue("got ${p.cutoffsHz.size}", p.cutoffsHz.size <= 64)
    }

    @Test fun failed_probe_caches_nothing_and_retries_next_start() {
        val s = newSettings()
        val bad = FakeEngineProbe(fault = IllegalStateException("boom"))
        val f = FakeEffectFactory()
        val rt = runtime(s, f, bad)
        applyOnce(rt, f)
        assertEquals(1, bad.calls)
        assertNull(s.engineMaxBands)
        assertNull(s.engineProbeFingerprint)
        assertTrue(f.creates().first().second.cutoffsHz.size > 64)
        rt.shutdown()
        val good = FakeEngineProbe(64)
        val f2 = FakeEffectFactory()
        val rt2 = runtime(s, f2, good)
        applyOnce(rt2, f2)
        assertEquals(1, good.calls)
        assertEquals(64, s.engineMaxBands)
    }

    @Test fun changed_fingerprint_reprobes() {
        val s = newSettings()
        s.recordEngineProbe(256, "some-other-build")
        val probe = FakeEngineProbe(128)
        val f = FakeEffectFactory()
        applyOnce(runtime(s, f, probe), f)
        assertEquals(1, probe.calls)
        assertEquals(128, s.engineMaxBands)
        assertEquals(Build.FINGERPRINT, s.engineProbeFingerprint)
    }

    @Test fun matching_fingerprint_does_not_reprobe() {
        val s = newSettings()
        s.recordEngineProbe(256, Build.FINGERPRINT)
        val probe = FakeEngineProbe(128)
        val f = FakeEffectFactory()
        applyOnce(runtime(s, f, probe), f)
        assertEquals(0, probe.calls)
        assertEquals(256, s.engineMaxBands)
    }

    @Test fun out_of_range_stored_value_is_treated_as_absent() {
        for (bad in intArrayOf(0, 9999)) {
            val s = newSettings()
            s.recordEngineProbe(bad, Build.FINGERPRINT)
            assertNull(s.engineMaxBands)
            val probe = FakeEngineProbe(64)
            val f = FakeEffectFactory()
            applyOnce(runtime(s, f, probe), f)
            assertEquals(1, probe.calls)
            assertEquals(64, s.engineMaxBands)
        }
    }

    @Test fun cached_cap_is_applied_in_constructor() {
        val s = newSettings()
        s.recordEngineProbe(64, Build.FINGERPRINT)
        val rt = runtime(s, FakeEffectFactory(), null)
        val p = rt.controller.engineParams(48000u, 85.3f)
        assertTrue("got ${p.cutoffsHz.size}", p.cutoffsHz.size <= 64)
    }

    @Test fun real_probe_on_idle_session_reads_back_requested_count() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val n = RealEngineProbe(am).measureMaxBands(EngineProbe.PROBE_BANDS)
        android.util.Log.i("EQ-SVC", "real probe accepted $n bands")
        assertEquals(512, n)
    }

    @Test fun real_probe_does_not_bind_the_cap_on_the_emulator() {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = newSettings()
        val f = FakeEffectFactory()
        val rt = runtime(s, f, RealEngineProbe(am))
        applyOnce(rt, f)
        assertNotNull(s.engineMaxBands)
        android.util.Log.i("EQ-SVC", "cached engineMaxBands=${s.engineMaxBands}")
        val capped = f.creates().first { it.first == 1001 }.second
        val plain = EqRuntime(FakeEffectFactory(), null, newSettings()).also { runtimes.add(it) }
            .controller.engineParams(
                android.media.AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).toUInt(),
                capped.frameDurationMs,
            )
        assertEquals(plain.layoutId, capped.layoutId)
        assertEquals(plain.cutoffsHz, capped.cutoffsHz)
    }
}
