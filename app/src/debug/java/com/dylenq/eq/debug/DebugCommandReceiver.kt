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
                "set-bass" -> c.edit { it.setBass(intent.getFloatExtra("db", 0f)) }
                "set-treble" -> c.edit { it.setTreble(intent.getFloatExtra("db", 0f)) }
                "set-balance" -> c.edit { it.setBalance(intent.getFloatExtra("value", 0f)) }
                "set-mode" -> {
                    val m = if (intent.getStringExtra("value") == "parametric") EqMode.PARAMETRIC else EqMode.GRAPHIC
                    c.edit { it.setMode(m) }
                }
                "add-band" -> c.edit { it.addBand() }
                "set-band" -> setBand(c, intent)
                "remove-band" -> {
                    val i = intent.getIntExtra("index", -1)
                    if (i < 0 || i >= c.snapshot().bands.size) {
                        Log.i(TAG, "remove-band: index $i out of range")
                    } else {
                        c.edit { it.removeBand(i.toUInt()) }
                    }
                }
                "select-band" -> {
                    // Selection lives in the UI holder, not the core. Call after the activity is up.
                    val i = intent.getIntExtra("index", -1)
                    (context.applicationContext as EqApp).uiHolder().selectBand(if (i < 0) null else i)
                }
                "set-band-count" -> c.edit { it.setGraphicBandCount(intent.getIntExtra("count", 10).toUInt()) }
                "set-limiter" -> c.edit { it.setLimiterEnabled(on(intent)) }
                "set-bypass" -> c.setBypass(on(intent))
                "set-global" -> rt.setGlobalMode(on(intent))
                "set-max-bands" -> rt.overrideEngineBandCap(intent.getIntExtra("value", -1).takeIf { it > 0 })
                "set-start-on-boot" -> rt.setStartOnBoot(on(intent))
                // Preset keys are "builtin:<name>" or "user:<name>". These skip the UI's name checks.
                "save-preset" -> c.edit { it.savePreset(intent.getStringExtra("name") ?: "") }
                "apply-preset" -> c.edit { it.applyPreset(intent.getStringExtra("key") ?: "") }
                "delete-preset" -> c.edit { it.deletePreset(intent.getStringExtra("key") ?: "") }
                "flush" -> rt.flush()  // write pending state now, before a script force-stops the app
                else -> Log.i(TAG, "unknown debug command: $cmd")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "debug command failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /** set-band: index, kind (peak|low-shelf|high-shelf|low-pass|high-pass), freq, gain, q, enabled. Missing extras keep the band's value. */
    private fun setBand(c: com.dylenq.eq.EqController, intent: Intent) {
        val i = intent.getIntExtra("index", -1)
        val cur = c.snapshot().bands.getOrNull(i)
        if (cur == null) {
            Log.i(TAG, "set-band: index $i out of range")
            return
        }
        val kind = when (val k = intent.getStringExtra("kind")) {
            null -> cur.kind
            "peak" -> com.dylenq.eq.core.FilterKind.PEAK
            "low-shelf" -> com.dylenq.eq.core.FilterKind.LOW_SHELF
            "high-shelf" -> com.dylenq.eq.core.FilterKind.HIGH_SHELF
            "low-pass" -> com.dylenq.eq.core.FilterKind.LOW_PASS
            "high-pass" -> com.dylenq.eq.core.FilterKind.HIGH_PASS
            else -> { Log.i(TAG, "set-band: unknown kind $k"); return }
        }
        val band = com.dylenq.eq.core.Band(
            kind,
            // "hz" and "db" are the older extra names (rotation-soak.sh); both spellings work.
            if (intent.hasExtra("freq")) intent.getFloatExtra("freq", cur.freq) else intent.getFloatExtra("hz", cur.freq),
            if (intent.hasExtra("gain")) intent.getFloatExtra("gain", cur.gainDb) else intent.getFloatExtra("db", cur.gainDb),
            if (intent.hasExtra("q")) intent.getFloatExtra("q", cur.q) else cur.q,
            if (intent.hasExtra("enabled")) intent.getBooleanExtra("enabled", cur.enabled) else cur.enabled,
        )
        c.edit { it.setBand(i.toUInt(), band) }
    }

    private fun on(i: Intent) = i.getStringExtra("value") == "on"

    private fun dump(rt: com.dylenq.eq.EqRuntime, nonce: String) {
        val s = rt.controller.snapshot()
        val canon = listOf(s.mode, s.bands, s.graphicBandCount, s.graphicGainsDb, s.preampDb, s.bassDb, s.trebleDb, s.balance, s.limiterEnabled, s.bypass, s.frameDurationMs)
            .joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(canon.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val st = rt.status.value
        Log.i(
            "EQ-STATE",
            "hash=$hash bypass=${s.bypass} mode=${s.mode} preamp=${s.preampDb} g3=${s.graphicGainsDb.getOrNull(3)} " +
                "globalMode=${rt.settings.globalMode} startOnBoot=${rt.settings.startOnBoot} " +
                "maxBands=${rt.activeEngineBandCap ?: "none"} sessions=${st.sessionCount} nonce=$nonce canUndo=${s.canUndo} bands=${s.bands.size} " +
                "first=${s.bands.take(4).joinToString(";") { "${it.freq}/${it.gainDb}/${it.q}/${it.kind}/${it.enabled}" }}",
        )
    }

    private companion object { const val TAG = "EQ-DEBUG" }
}
