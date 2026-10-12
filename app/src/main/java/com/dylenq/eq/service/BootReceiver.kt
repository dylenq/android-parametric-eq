package com.dylenq.eq.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the service after boot or an app update, only when the start-on-boot setting is on (it
 * defaults to OFF). Reads the settings flag only (no EqCore), uses the intent's action and nothing
 * else from it, and never throws: a denied background start is swallowed and logged.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action
            if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            if (!settingsProvider(context).startOnBoot) return
            try {
                starter(context)
                Log.i(TAG, "boot start requested ($action)")
            } catch (e: IllegalStateException) {
                Log.i(TAG, "boot start denied ${e.javaClass.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "boot receiver failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "EQ-SVC"

        /** Replaceable in tests. */
        @Volatile var starter: (Context) -> Unit = { c ->
            c.startForegroundService(Intent(c, EqService::class.java))
        }

        /** Replaceable in tests so they do not touch the real preferences. */
        @Volatile var settingsProvider: (Context) -> SettingsStore = { c -> SettingsStore(c) }
    }
}
