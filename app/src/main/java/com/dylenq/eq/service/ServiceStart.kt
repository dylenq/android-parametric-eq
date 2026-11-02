package com.dylenq.eq.service

import android.util.Log

/**
 * Runs the two start-up steps of [EqService] in order and says whether the service is running.
 *
 * Android may refuse a background start (a sticky restart while the app is not visible, for
 * example), and the core may fail to load. Neither should bring up a crash dialog: the Activity
 * starts the service again and shows its own error screen when the core is at fault. Any other
 * Error is not ours to swallow and propagates.
 */
fun runStartSteps(
    startInForeground: () -> Unit,
    startRuntimeWork: () -> Unit,
    warn: (String) -> Unit = { Log.w(SERVICE_TAG, it) },
    error: (String) -> Unit = { Log.e(SERVICE_TAG, it) },
): Boolean =
    try {
        startInForeground()
        startRuntimeWork()
        true
    } catch (t: Exception) {
        warn("start failed: ${t.javaClass.name}: ${t.message}")
        false
    } catch (e: LinkageError) {
        error("core library failed to load: ${e.message}")
        false
    }

internal const val SERVICE_TAG = "EQ-SVC"
