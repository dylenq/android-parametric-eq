package com.dylenq.eq

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

/** Application-scoped holder of the one [EqRuntime] shared by Activity, service and receivers. */
class EqApp : Application() {
    @Volatile private var current: EqRuntime? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EQ service", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun runtime(): EqRuntime {
        current?.let { return it }
        synchronized(this) {
            return current ?: EqRuntime.create(this).also { current = it }
        }
    }

    /** Tests only: replaces the runtime (null clears it so the next [runtime] call builds a fresh one). */
    fun installForTest(r: EqRuntime?) {
        synchronized(this) { current = r }
    }

    /** Tests only: the runtime currently installed, without creating one. */
    fun peekRuntime(): EqRuntime? = current

    companion object {
        const val CHANNEL_ID = "eq_service"
    }
}
