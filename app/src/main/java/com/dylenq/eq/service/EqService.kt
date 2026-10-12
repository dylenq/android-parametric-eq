package com.dylenq.eq.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import com.dylenq.eq.EqApp

/** specialUse START_STICKY foreground service; keeps the process alive and hosts the runtime receiver. */
class EqService : Service() {
    private var receiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // startForeground first, before touching the runtime (deadline after startForegroundService).
        val n = NotificationFactory.build(this, "On")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        val app = application as EqApp
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                SessionIntent.parse(intent)?.let { app.runtime().post(it) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(r, filter)
        }
        receiver = r
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        (application as EqApp).runtime().attach()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        receiver = null
        (application as EqApp).peekRuntime()?.detach()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
    }
}
