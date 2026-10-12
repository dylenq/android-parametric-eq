package com.dylenq.eq.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Small UI flags. [rationaleAnswered] persists (the notification rationale is shown once);
 * [offHintDismissed] lives in memory only, so the "notifications are off" banner returns each launch.
 * A null [prefs] keeps everything in memory (tests).
 */
class UiPrefs(private val prefs: SharedPreferences?) {
    constructor(context: Context) :
        this(context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE))

    @Volatile private var memAnswered = false

    var rationaleAnswered: Boolean
        get() = prefs?.getBoolean(KEY_ANSWERED, false) ?: memAnswered
        set(v) { if (prefs != null) prefs.edit().putBoolean(KEY_ANSWERED, v).apply() else memAnswered = v }

    @Volatile var offHintDismissed: Boolean = false

    companion object {
        const val NAME = "eq_ui"
        private const val KEY_ANSWERED = "notification_rationale_answered"
    }
}
