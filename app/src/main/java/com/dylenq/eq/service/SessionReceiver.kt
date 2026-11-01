package com.dylenq.eq.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dylenq.eq.EqApp

/** Manifest receiver: enqueue the event, then best-effort wake the service for a new session (may be denied). */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = SessionIntent.parse(intent) ?: return
        try {
            (context.applicationContext as EqApp).runtime().post(event)
            if (event is SessionIntent.Event.Open) {
                context.startForegroundService(Intent(context, EqService::class.java))
                Log.i("EQ-SVC", "wakeup start ok")
            }
        } catch (t: Throwable) {
            // A refused background start or a core that failed to load: nothing a broadcast can report.
            Log.i("EQ-SVC", "wakeup start denied ${t.javaClass.name}")
        }
    }
}
