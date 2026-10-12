package com.dylenq.eq.spike

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.os.IBinder
import android.util.Log

/**
 * SPIKE-03 runtime half: a plain started service (NOT foreground) that registers a context
 * receiver for the two session actions. Spike-only; performs no audio work.
 */
class SpikeListenerService : Service() {
    private var receiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val exported = intent?.getBooleanExtra("exported", false) ?: false
        if (receiver != null) {
            try { unregisterReceiver(receiver) } catch (_: Throwable) {}
            receiver = null
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                val session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                val pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                val verdict = if (session > 0 && pkg != null) "accepted" else "rejected"
                Log.i("SPIKE-BCAST", "runtime $verdict action=${i.action} session=$session pkg=$pkg")
            }
        }
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        try {
            registerReceiver(
                r, filter,
                if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
            )
            receiver = r
            Log.i("SPIKE-BCAST", "runtime registered exported=$exported")
        } catch (t: Throwable) {
            Log.i("SPIKE-BCAST", "runtime register_failed ${t.javaClass.name}: ${t.message}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Throwable) {}
        receiver = null
        super.onDestroy()
    }
}
