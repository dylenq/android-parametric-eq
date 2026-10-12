package com.dylenq.eq

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dylenq.eq.ui.UiStateHolder

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
        synchronized(this) {
            holder?.close()
            holder = null
            current = r
        }
    }

    @Volatile private var holder: UiStateHolder? = null

    /** The UI state holder bound to the current runtime; rebuilt when the runtime is replaced. */
    fun uiHolder(): UiStateHolder {
        val rt = runtime()
        holder?.takeIf { it.runtime === rt }?.let { return it }
        synchronized(this) {
            holder?.takeIf { it.runtime === rt }?.let { return it }
            holder?.close()
            return UiStateHolder(rt).also { holder = it }
        }
    }

    /** Tests only: the runtime currently installed, without creating one. */
    fun peekRuntime(): EqRuntime? = current

    companion object {
        const val CHANNEL_ID = "eq_service"
    }
}
