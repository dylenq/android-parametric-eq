package com.dylenq.eq.service

import android.content.Context
import android.content.SharedPreferences

/**
 * The two service flags. Cheap to construct (no EqCore), so BootReceiver can read them. A null
 * [prefs] keeps the values in memory only (tests, runtimes without a Context). Setters use commit()
 * because these are rare edits that must survive an immediate process kill.
 */
class SettingsStore(private val prefs: SharedPreferences?) {
    constructor(context: Context, name: String = PREFS_NAME) :
        this(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

    @Volatile private var memGlobal = false
    @Volatile private var memBoot = false

    var globalMode: Boolean
        get() = prefs?.getBoolean(KEY_GLOBAL, false) ?: memGlobal
        set(v) { if (prefs != null) prefs.edit().putBoolean(KEY_GLOBAL, v).commit() else memGlobal = v }

    /** Defaults to OFF (L-05). */
    var startOnBoot: Boolean
        get() = prefs?.getBoolean(KEY_BOOT, false) ?: memBoot
        set(v) { if (prefs != null) prefs.edit().putBoolean(KEY_BOOT, v).commit() else memBoot = v }

    @Volatile private var memEngineBands = -1
    @Volatile private var memEngineFp: String? = null

    /** Measured DynamicsProcessing band count; null when absent or outside 8..512. */
    val engineMaxBands: Int?
        get() {
            val v = prefs?.getInt(KEY_ENGINE_BANDS, -1) ?: memEngineBands
            return if (v in MIN_ENGINE_BANDS..MAX_ENGINE_BANDS) v else null
        }

    /** The OS build fingerprint the band count was measured on. */
    val engineProbeFingerprint: String?
        get() = prefs?.getString(KEY_ENGINE_FP, null) ?: memEngineFp

    /** Stores the count and its fingerprint together in one commit. */
    fun recordEngineProbe(maxBands: Int, fingerprint: String) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_ENGINE_BANDS, maxBands).putString(KEY_ENGINE_FP, fingerprint).commit()
        } else {
            memEngineBands = maxBands
            memEngineFp = fingerprint
        }
    }

    companion object {
        private const val MIN_ENGINE_BANDS = 8
        private const val MAX_ENGINE_BANDS = 512
        private const val KEY_ENGINE_BANDS = "engine_max_bands"
        private const val KEY_ENGINE_FP = "engine_probe_fp"
        const val PREFS_NAME = "eq_settings"
        private const val KEY_GLOBAL = "global_mode"
        private const val KEY_BOOT = "start_on_boot"
    }
}
