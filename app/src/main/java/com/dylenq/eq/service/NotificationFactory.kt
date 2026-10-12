package com.dylenq.eq.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.dylenq.eq.EqApp
import com.dylenq.eq.MainActivity
import com.dylenq.eq.R

object NotificationFactory {
    /** Status line: the notice when there is one, else bypass, else what is being equalised. */
    fun statusText(status: ServiceStatus): String = when {
        status.notice != null -> status.notice
        status.bypass -> "Bypassed"
        status.globalMode -> "On - all audio"
        status.sessionCount == 1 -> "On - 1 app"
        else -> "On - ${status.sessionCount} apps"
    }

    fun build(context: Context, status: ServiceStatus): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Explicit component and immutable: no other app can redirect or rewrite it.
        val toggle = PendingIntent.getService(
            context, 1,
            Intent(context, EqService::class.java).setAction(EqService.ACTION_TOGGLE_BYPASS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_stat_eq),
            if (status.bypass) "Enable" else "Bypass",
            toggle,
        ).build()
        val b = Notification.Builder(context, EqApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle("EQ")
            .setContentText(statusText(status))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(action)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }
}
