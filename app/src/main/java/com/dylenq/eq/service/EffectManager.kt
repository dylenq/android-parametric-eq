package com.dylenq.eq.service

import android.util.Log
import com.dylenq.eq.core.EngineParams

/**
 * Session map and effect attach/release. Worker-confined: only the "eq-effects" thread may call
 * these methods (tests go through EqRuntime.inspect).
 */
class EffectManager(
    private val factory: EffectFactory,
    private val params: () -> EngineParams,
) {
    private class Entry(val handle: EffectHandle, val pkg: String, var lastSeen: Long)

    private val sessions = LinkedHashMap<Int, Entry>()
    private var tick = 0L

    fun onOpen(session: Int, pkg: String) {
        val known = sessions[session]
        if (known != null) {
            known.lastSeen = ++tick
            return
        }
        try {
            val handle = factory.create(session, params())
            sessions[session] = Entry(handle, pkg, ++tick)
            Log.i(TAG, "attached session=$session pkg=$pkg")
        } catch (t: Throwable) {
            Log.w(TAG, "attach failed session=$session: ${t.javaClass.name}: ${t.message}")
        }
    }

    fun onClose(session: Int) {
        val e = sessions.remove(session) ?: return
        try {
            e.handle.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed session=$session: ${t.javaClass.name}: ${t.message}")
        }
        Log.i(TAG, "released session=$session")
    }

    fun releaseAll() {
        for (s in sessions.keys.toList()) onClose(s)
    }

    fun sessionCount(): Int = sessions.size

    fun handleFor(session: Int): EffectHandle? = sessions[session]?.handle

    private companion object {
        const val TAG = "EQ-SVC"
    }
}
