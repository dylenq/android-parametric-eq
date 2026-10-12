package com.dylenq.playerstub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/** Sends an implicit session open/close broadcast exactly like a media player would. */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra("session", -1)
        val open = intent.getIntExtra("open", 1) == 1
        val action = if (open) AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
        else AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        context.sendBroadcast(
            Intent(action)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        )
    }
}
