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

    @Volatile private var memPinchSeen = false

    /** True once the user has pinched a node, after which the band-list hint drops the pinch sentence. */
    var pinchHintSeen: Boolean
        get() = prefs?.getBoolean(KEY_PINCH_SEEN, false) ?: memPinchSeen
        set(v) { if (prefs != null) prefs.edit().putBoolean(KEY_PINCH_SEEN, v).apply() else memPinchSeen = v }

    @Volatile var offHintDismissed: Boolean = false

    companion object {
        const val NAME = "eq_ui"
        private const val KEY_ANSWERED = "notification_rationale_answered"
        private const val KEY_PINCH_SEEN = "pinch_hint_seen"
    }
}
