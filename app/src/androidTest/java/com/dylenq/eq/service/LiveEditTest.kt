package com.dylenq.eq.service

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.testsupport.FakeEffectFactory
import com.dylenq.eq.testsupport.RuntimeRule
import com.dylenq.eq.testsupport.ToneRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Edits through [com.dylenq.eq.EqController] reach every attached session. */
@RunWith(AndroidJUnit4::class)
class LiveEditTest {
    @get:Rule
    val rt = RuntimeRule({ RealEffectFactory() })

    private fun poll(ms: Long, step: Long = 20, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(step)
        }
        return cond()
    }

    private fun params(runtime: EqRuntime, frameMs: Float? = null): EngineParams {
        val c = runtime.controller
        return c.engineParams(ToneRig.nativeRate().toUInt(), frameMs ?: c.snapshot().frameDurationMs)
    }

    private fun post(runtime: EqRuntime, session: Int) =
        runtime.post(SessionIntent.Event.Open(session, "com.example.player"))

    @Test
    fun an_edit_reaches_two_real_sessions_within_500_ms() {
        val runtime = rt.runtime
        val rigs = listOf(ToneRig(), ToneRig())
        try {
            rigs.forEach { it.start(); post(runtime, it.sessionId) }
            assertTrue("sessions not tracked", poll(3000) { runtime.inspect { it.sessionCount() } == 2 })

            val before = params(runtime)
            val t0 = System.nanoTime()
            runtime.controller.edit { it.setGraphicGain(3u, 6f) }
            val after = params(runtime)
            val idx = after.gainsDb.indices.maxByOrNull { Math.abs(after.gainsDb[it] - before.gainsDb[it]) }!!
            assertTrue(
                "the edit must change some engine band by more than 0.5 dB",
                Math.abs(after.gainsDb[idx] - before.gainsDb[idx]) > 0.5f,
            )

            fun both() = runtime.inspect { m ->
                rigs.all { Math.abs(m.handleFor(it.sessionId)!!.readBandGain(idx) - after.gainsDb[idx]) < 0.01f }
            }
            val ok = poll(2000, 10) { both() }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            Log.i("EQ-LIVE", "edit-to-readback on 2 real sessions: $elapsedMs ms (band $idx ${before.gainsDb[idx]} -> ${after.gainsDb[idx]})")
            assertTrue("read-back never matched", ok)
            assertTrue("edit took $elapsedMs ms, bound 500", elapsedMs < 500)
        } finally {
            rigs.forEach { it.release() }
        }
    }

    @Test
    fun fifty_rapid_edits_end_on_the_last_value_with_far_fewer_band_writes() {
        val fake = FakeEffectFactory()
        val runtime = EqRuntime(fake)
        try {
            val sessions = listOf(9001, 9002)
            sessions.forEach { post(runtime, it) }
            assertTrue(poll(3000) { runtime.inspect { it.sessionCount() } == 2 })

            val graphicCount = runtime.controller.snapshot().graphicGainsDb.size
            val bandCount = params(runtime).gainsDb.size
            for (i in 0 until 50) {
                val v = ((i % 9) - 4) * 1.5f + if (i == 49) 2.25f else 0f
                runtime.controller.edit { it.setGraphicGain((i % graphicCount).toUInt(), v) }
            }
            val last = params(runtime)

            fun converged() = sessions.all { s ->
                val h = fake.handle(s)!!
                last.gainsDb.indices.all { Math.abs(h.readBandGain(it) - last.gainsDb[it]) <= 0.01f }
            }
            assertTrue("sessions never converged on the last value", poll(3000) { converged() })
            Thread.sleep(300)
            assertTrue(converged())

            val writes = sessions.sumOf { fake.callCount(it, "setBand") }
            val naive = 50 * bandCount * sessions.size
            Log.i("EQ-LIVE", "burst: setBand writes=$writes naive=$naive bands=$bandCount sessions=${sessions.size}")
            assertTrue("writes=$writes not far below naive=$naive", writes * 4 < naive)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun a_frame_duration_change_recreates_both_effects_with_the_new_params() {
        val runtime = rt.runtime
        val rigs = listOf(ToneRig(), ToneRig())
        try {
            rigs.forEach { it.start(); post(runtime, it.sessionId) }
            assertTrue(poll(3000) { runtime.inspect { it.sessionCount() } == 2 })
            val old = rigs.map { r -> runtime.inspect { it.handleFor(r.sessionId) } }

            runtime.controller.edit { it.setFrameDurationMs(170.6f) }
            val expected = params(runtime, 170.6f)
            assertEquals(170.6f, runtime.controller.snapshot().frameDurationMs, 0.001f)

            val replaced = poll(3000) {
                runtime.inspect { m -> rigs.indices.all { m.handleFor(rigs[it].sessionId) !== old[it] } }
            }
            assertTrue("handles were not recreated", replaced)
            val n = expected.cutoffsHz.size
            runtime.inspect { m ->
                for (r in rigs) {
                    val h = m.handleFor(r.sessionId)!!
                    assertNotSame(old[rigs.indexOf(r)], h)
                    for (i in listOf(0, n / 3, n / 2, n - 1)) {
                        assertEquals("gain $i", expected.gainsDb[i], h.readBandGain(i), 0.01f)
                        assertEquals("cutoff $i", expected.cutoffsHz[i], h.readBandCutoff(i), 0.5f)
                    }
                    assertTrue(h.readEnabled())
                }
            }
            assertEquals(2, runtime.status.value.sessionCount)
        } finally {
            rigs.forEach { it.release() }
        }
    }
}
