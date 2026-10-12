package com.dylenq.eq.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dylenq.eq.EqApp

/** Manifest receiver: enqueue the event, then best-effort wake the service (may be denied). */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = SessionIntent.parse(intent) ?: return
        (context.applicationContext as EqApp).runtime().post(event)
        try {
            context.startForegroundService(Intent(context, EqService::class.java))
            Log.i("EQ-SVC", "wakeup start ok")
        } catch (e: IllegalStateException) {
            Log.i("EQ-SVC", "wakeup start denied ${e.javaClass.name}")
        } catch (e: Throwable) {
            Log.i("EQ-SVC", "wakeup start denied ${e.javaClass.name}")
        }
    }
}
