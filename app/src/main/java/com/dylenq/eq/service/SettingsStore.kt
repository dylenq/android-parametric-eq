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

    companion object {
        const val PREFS_NAME = "eq_settings"
        private const val KEY_GLOBAL = "global_mode"
        private const val KEY_BOOT = "start_on_boot"
    }
}
