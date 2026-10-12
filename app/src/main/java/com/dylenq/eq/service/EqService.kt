package com.dylenq.eq.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.dylenq.eq.EqApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** specialUse START_STICKY foreground service; keeps the process alive and hosts the runtime receiver. */
class EqService : Service() {
    private var receiver: BroadcastReceiver? = null
    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        // startForeground first, before touching the runtime (deadline after startForegroundService).
        val n = NotificationFactory.build(this, ServiceStatus())
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

        // Keep the notification text in step with the runtime; StateFlow is conflated, plus a pause = about 4 updates/s.
        val nm = getSystemService(NotificationManager::class.java)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        sc.launch {
            app.runtime().status.collect { st ->
                try {
                    nm.notify(NOTIFICATION_ID, NotificationFactory.build(this@EqService, st))
                } catch (t: Throwable) {
                    Log.w("EQ-SVC", "notify failed: ${t.javaClass.name}")
                }
                delay(NOTIFY_PAUSE_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = (application as EqApp).runtime()
        // A null intent is a sticky redelivery; the runtime restored its state when it was created.
        if (intent?.action == ACTION_TOGGLE_BYPASS) {
            runtime.controller.toggleBypass()
        }
        runtime.attach()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        receiver = null
        (application as EqApp).peekRuntime()?.let {
            it.flush()
            it.detach()
        }
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE_BYPASS = "com.dylenq.eq.action.TOGGLE_BYPASS"
        private const val NOTIFY_PAUSE_MS = 250L
    }
}
