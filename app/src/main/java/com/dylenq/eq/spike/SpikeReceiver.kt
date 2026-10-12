package com.dylenq.eq.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log

/** Logging stub for SPIKE-03. Extras come from untrusted apps, so they are validated. */
class SpikeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        val verdict = if (session > 0 && pkg != null) "accepted" else "rejected"
        Log.i("SPIKE-BCAST", "manifest $verdict action=${intent.action} session=$session pkg=$pkg")
    }
}
