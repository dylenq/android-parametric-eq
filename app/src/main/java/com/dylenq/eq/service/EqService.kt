package com.dylenq.eq.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
    private var deviceCallback: AudioDeviceCallback? = null

    /** False once start-up failed; the service then only stops itself. */
    private var running = false

    override fun onCreate() {
        super.onCreate()
        running = try {
            startInForeground()
            startRuntimeWork()
            true
        } catch (t: Exception) {
            // Android may refuse a background start (a sticky restart while the app is not visible, for
            // example), and the core may fail to load. Neither should bring up a crash dialog: the
            // Activity starts the service again and shows its own error screen when the core is at fault.
            Log.w(TAG, "start failed: ${t.javaClass.name}: ${t.message}")
            false
        } catch (e: LinkageError) {
            Log.e(TAG, "core library failed to load: ${e.message}")
            false
        }
        if (!running) stopSelf()
    }

    // First, before touching the runtime (deadline after startForegroundService).
    private fun startInForeground() {
        val n = NotificationFactory.build(this, ServiceStatus())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun startRuntimeWork() {
        val app = application as EqApp
        app.runtime() // Builds the core now, so a failure to load it is caught by onCreate.
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

        // A new output (headphones, Bluetooth) can change the sample rate the curve is fitted to.
        val audio = getSystemService(AudioManager::class.java)
        val dc = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = app.runtime().reapply()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = app.runtime().reapply()
        }
        audio.registerAudioDeviceCallback(dc, null)
        deviceCallback = dc

        // Keep the notification text in step with the runtime; StateFlow is conflated, plus a pause = about 4 updates/s.
        val nm = getSystemService(NotificationManager::class.java)
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        sc.launch {
            app.runtime().status.collect { st ->
                try {
                    nm.notify(NOTIFICATION_ID, NotificationFactory.build(this@EqService, st))
                } catch (t: Throwable) {
                    Log.w(TAG, "notify failed: ${t.javaClass.name}")
                }
                delay(NOTIFY_PAUSE_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) return START_NOT_STICKY
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
        deviceCallback?.let { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        (application as EqApp).peekRuntime()?.let {
            it.flush()
            it.detach()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "EQ-SVC"
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE_BYPASS = "com.dylenq.eq.action.TOGGLE_BYPASS"
        private const val NOTIFY_PAUSE_MS = 250L
    }
}
