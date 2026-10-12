package com.dylenq.eq.service

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.ToneRig
import java.io.FileInputStream
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Wave 0 platform probes. Each probe prints one "P3-PROBE <id> key=value ..." line and asserts
 * nothing: the observation is the result.
 */
@RunWith(AndroidJUnit4::class)
class Phase3ProbeTest {
    private val tag = "P3-PROBE"
    private fun line(id: String, kv: String) = Log.i(tag, "$id $kv")

    private fun params(frameMs: Float): EngineParams {
        val core = EqCore()
        core.setMode(EqMode.PARAMETRIC)
        core.setBand(0u, Band(FilterKind.PEAK, 1000f, 6f, 4f, true))
        return core.engineParams(ToneRig.nativeRate().toUInt(), frameMs)
    }

    private fun nowMs() = System.nanoTime() / 1_000_000.0
    private fun ms(v: Double) = String.format("%.2f", v)

    @Test
    fun a8_timings() {
        for (frameMs in listOf(85.3f, 170.6f)) {
            val p = params(frameMs)
            val bands = p.cutoffsHz.size
            val times = ArrayList<Double>()
            var setOne = 0.0
            var push10 = 0.0
            var push50 = 0.0
            repeat(3) { run ->
                val rig = ToneRig()
                try {
                    val t0 = nowMs()
                    val h = RealEffectFactory().create(rig.sessionId, p)
                    times.add(nowMs() - t0)
                    if (run == 2) {
                        var t = nowMs()
                        h.setBand(5, p.cutoffsHz[5], 3f)
                        setOne = nowMs() - t
                        t = nowMs()
                        for (i in 10 until 20) h.setBand(i, p.cutoffsHz[i], 2f)
                        push10 = nowMs() - t
                        t = nowMs()
                        for (i in 30 until 80) h.setBand(i, p.cutoffsHz[i], 2f)
                        push50 = nowMs() - t
                    }
                    h.release()
                } finally {
                    rig.release()
                }
            }
            times.sort()
            line(
                "A8",
                "frameMs=$frameMs bands=$bands create_ms=${times.joinToString(",") { ms(it) }} " +
                    "create_median_ms=${ms(times[1])} setBand_one_ms=${ms(setOne)} " +
                    "push10_ms=${ms(push10)} push50_ms=${ms(push50)}"
            )
        }
    }

    @Test
    fun a1_orphan_effect_after_track_dies() {
        val p = params(85.3f)
        val rig = ToneRig()
        val session = rig.sessionId
        val h = RealEffectFactory().create(session, p)
        rig.start()
        Thread.sleep(500)
        val before = flingerLines(session)
        rig.release() // track dies, no CLOSE, effect not released
        Thread.sleep(3000)
        fun attempt(name: String, f: () -> Any?): String =
            try { "$name=ok(${f()})" } catch (t: Throwable) { "$name=threw(${t.javaClass.simpleName}:${t.message})" }
        val enabled = attempt("readEnabled") { h.readEnabled() }
        val set = attempt("setBand") { h.setBand(3, p.cutoffsHz[3], 1f); "done" }
        val ctl = attempt("hasControl") { h.hasControl() }
        val after = flingerLines(session)
        line("A1", "session=$session $enabled $set $ctl flinger_lines_session_before=$before flinger_lines_session_after=$after")
        try { h.release() } catch (t: Throwable) { line("A1", "release_threw=${t.javaClass.simpleName}") }
        Thread.sleep(500)
        line("A1", "session=$session flinger_lines_session_after_release=${flingerLines(session)}")
    }

    private fun flingerLines(session: Int): Int {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pfd = ui.executeShellCommand("dumpsys media.audio_flinger")
        val text = FileInputStream(pfd.fileDescriptor).bufferedReader().use { it.readText() }
        pfd.close()
        return text.lines().count { Regex("\\b$session\\b").containsMatchIn(it) }
    }

    @Test
    fun a4_fgs_start_from_instrumentation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val i = Intent(ctx, EqService::class.java)
        try {
            ctx.startForegroundService(i)
            line("A4", "startForegroundService=ok")
            Thread.sleep(1500)
        } catch (t: Throwable) {
            line("A4", "startForegroundService=threw(${t.javaClass.name})")
        }
        ctx.stopService(i)
        line("A4", "stopService=called")
    }
}
