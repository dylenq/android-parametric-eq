package com.dylenq.eq.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import java.security.MessageDigest

/**
 * Debug builds only (lives in the debug source set). Lets host scripts set and read EQ state with
 * `adb shell am broadcast -n com.dylenq.eq/com.dylenq.eq.debug.DebugCommandReceiver --es cmd ...`.
 * `dump-state` logs one EQ-STATE line. Never packaged in release.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val rt = (context.applicationContext as EqApp).runtime()
            val c = rt.controller
            when (val cmd = intent?.getStringExtra("cmd")) {
                "dump-state" -> dump(rt, intent.getStringExtra("nonce") ?: "")
                "set-graphic-gain" -> {
                    val i = intent.getIntExtra("index", 0)
                    val db = intent.getFloatExtra("db", 0f)
                    c.edit { it.setGraphicGain(i.toUInt(), db) }
                }
                "set-preamp" -> c.edit { it.setPreamp(intent.getFloatExtra("db", 0f)) }
                "set-mode" -> {
                    val m = if (intent.getStringExtra("value") == "parametric") EqMode.PARAMETRIC else EqMode.GRAPHIC
                    c.edit { it.setMode(m) }
                }
                "set-bypass" -> c.setBypass(on(intent))
                "set-global" -> rt.setGlobalMode(on(intent))
                "set-start-on-boot" -> rt.setStartOnBoot(on(intent))
                else -> Log.i(TAG, "unknown debug command: $cmd")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "debug command failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun on(i: Intent) = i.getStringExtra("value") == "on"

    private fun dump(rt: com.dylenq.eq.EqRuntime, nonce: String) {
        val s = rt.controller.snapshot()
        val canon = listOf(s.mode, s.bands, s.graphicBandCount, s.graphicGainsDb, s.preampDb, s.limiterEnabled, s.bypass, s.frameDurationMs)
            .joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val st = rt.status.value
        Log.i(
            "EQ-STATE",
            "hash=$hash bypass=${s.bypass} mode=${s.mode} preamp=${s.preampDb} g3=${s.graphicGainsDb.getOrNull(3)} " +
                "globalMode=${rt.settings.globalMode} startOnBoot=${rt.settings.startOnBoot} " +
                "sessions=${st.sessionCount} nonce=$nonce",
        )
    }

    private companion object { const val TAG = "EQ-DEBUG" }
}
