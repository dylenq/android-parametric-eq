package com.dylenq.eq.service

import android.util.Log
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.LimiterParams
import kotlin.math.abs

/**
 * Session map and effect attach/release. Worker-confined: only the "eq-effects" thread may call
 * these methods (tests go through EqRuntime.inspect). No call here ever throws.
 *
 * Policy: a failing handle call triggers one release-and-recreate; if the recreate fails too the
 * session is dropped and listed as FAILED. A session whose effect has no control is kept as
 * NO_CONTROL and skipped on updates.
 */
class EffectManager(
    private val factory: EffectFactory,
    private val params: () -> EngineParams,
    private val onChange: () -> Unit = {},
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    /** Called on the worker after global mode was refused and per-session effects were restored. */
    private val onGlobalRejected: (String) -> Unit = {},
    /** Pause between create attempts; injected so tests never sleep. */
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it) },
) {
    private data class Key(val layoutId: UInt, val sampleRate: UInt, val frameDurationMs: Float)
    private data class Lim(val limiter: LimiterParams, val enabled: Boolean)

    private class Entry(
        var handle: EffectHandle,
        val pkg: String,
        var lastSeen: Long,
        p: EngineParams,
        var state: SessionState,
    ) {
        val gains: FloatArray = p.gainsDb.toFloatArray()
        var inputGain: Float = p.inputGainDb
        var lim: Lim = Lim(p.limiter, p.limiterEnabled)
        var enabled: Boolean = !p.bypass
        val key: Key = Key(p.layoutId, p.sampleRate, p.frameDurationMs)
    }

    private val sessions = LinkedHashMap<Int, Entry>()
    private val failed = LinkedHashMap<Int, String>()

    /** Sessions tracked while global mode is on: package and last-seen tick, no handle. */
    private class Tracked(val pkg: String, var lastSeen: Long)
    private val suppressed = LinkedHashMap<Int, Tracked>()
    private var globalOn = false
    private var tick = 0L

    fun onOpen(session: Int, pkg: String) = guarded("open") {
        if (globalOn) {
            val t = suppressed[session]
            if (t != null) {
                t.lastSeen = ++tick
            } else {
                while (suppressed.size >= maxSessions) {
                    val victim = suppressed.entries.minByOrNull { it.value.lastSeen } ?: break
                    suppressed.remove(victim.key)
                }
                suppressed[session] = Tracked(pkg, ++tick)
            }
            return@guarded
        }
        val known = sessions[session]
        if (known != null) {
            known.lastSeen = ++tick
            return@guarded
        }
        failed.remove(session)
        evictIfFull()
        try {
            val p = params()
            sessions[session] = build(session, pkg, p, ++tick)
            Log.i(TAG, "attached session=$session pkg=$pkg")
        } catch (t: Throwable) {
            Log.w(TAG, "attach failed session=$session: ${t.javaClass.name}: ${t.message}")
            recordFailure(session, pkg)
        }
    }

    fun onClose(session: Int) = guarded("close") {
        failed.remove(session)
        if (globalOn) {
            suppressed.remove(session)
            return@guarded
        }
        val e = sessions.remove(session) ?: return@guarded
        releaseQuietly(session, e.handle)
        Log.i(TAG, "released session=$session")
    }

    /** Fetches the params once and brings every session up to date (diff, or recreate on a key change). */
    fun applyLatest() = guarded("apply") {
        val p = params()
        for ((id, e) in sessions.entries.toList()) {
            try {
                applyOne(id, e, p)
            } catch (t: Throwable) {
                Log.w(TAG, "apply failed session=$id: ${t.javaClass.name}: ${t.message}")
                dropToFailed(id, e)
            }
        }
        if (globalOn && sessions[GLOBAL_ID] == null) rejectGlobal("the effect could not be kept")
    }

    /**
     * Global mode (L-04): one effect on session 0, per-session effects suppressed. A refusal of any
     * kind (create throws, no control, enable fails) leaves the mode off and calls [onGlobalRejected].
     */
    fun setGlobalMode(on: Boolean) = guarded("global") {
        if (on == globalOn) return@guarded
        if (on) enableGlobal() else disableGlobal()
    }

    fun isGlobalOn(): Boolean = globalOn

    private fun enableGlobal() {
        for ((id, e) in sessions.entries.toList()) {
            suppressed[id] = Tracked(e.pkg, e.lastSeen)
            releaseQuietly(id, e.handle)
        }
        sessions.clear()
        for ((id, pkg) in failed) suppressed.getOrPut(id) { Tracked(pkg, ++tick) }
        failed.clear()
        try {
            val p = params()
            val g = build(GLOBAL_ID, GLOBAL_PKG, p, ++tick)
            if (g.state != SessionState.OK) {
                releaseQuietly(GLOBAL_ID, g.handle)
                throw IllegalStateException("no control of the output mix")
            }
            if (!g.handle.setEnabled(!p.bypass)) {
                releaseQuietly(GLOBAL_ID, g.handle)
                throw IllegalStateException("the effect could not be enabled")
            }
            sessions[GLOBAL_ID] = g
            globalOn = true
            Log.i(TAG, "global mode on")
        } catch (t: Throwable) {
            rejectGlobal(t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName)
        }
    }

    private fun disableGlobal() {
        globalOn = false
        sessions.remove(GLOBAL_ID)?.let { releaseQuietly(GLOBAL_ID, it.handle) }
        failed.remove(GLOBAL_ID)
        restoreSuppressed()
        Log.i(TAG, "global mode off")
    }

    private fun rejectGlobal(reason: String) {
        Log.w(TAG, "global mode rejected: $reason")
        globalOn = false
        sessions.remove(GLOBAL_ID)?.let { releaseQuietly(GLOBAL_ID, it.handle) }
        failed.remove(GLOBAL_ID)
        restoreSuppressed()
        try {
            onGlobalRejected(reason)
        } catch (t: Throwable) {
            Log.e(TAG, "onGlobalRejected failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun restoreSuppressed() {
        val tracked = suppressed.entries.toList()
        suppressed.clear()
        if (tracked.isEmpty()) return
        val p = try { params() } catch (t: Throwable) { null }
        for ((id, t) in tracked) {
            try {
                sessions[id] = build(id, t.pkg, p ?: params(), t.lastSeen)
            } catch (x: Throwable) {
                Log.w(TAG, "restore failed session=$id: ${x.javaClass.name}: ${x.message}")
                recordFailure(id, t.pkg)
            }
        }
    }

    fun releaseAll() = guarded("releaseAll") {
        for ((id, e) in sessions.entries.toList()) releaseQuietly(id, e.handle)
        sessions.clear()
        failed.clear()
        suppressed.clear()
        globalOn = false
    }

    /** Tracked app sessions (the global effect itself is not counted). */
    fun sessionCount(): Int = if (globalOn) suppressed.size else sessions.size

    fun handleFor(session: Int): EffectHandle? = sessions[session]?.handle

    fun sessionInfos(): List<SessionInfo> =
        if (globalOn) {
            suppressed.map { (id, t) -> SessionInfo(id, t.pkg, SessionState.SUPPRESSED) }
        } else {
            sessions.map { (id, e) -> SessionInfo(id, e.pkg, e.state) } +
                failed.map { (id, pkg) -> SessionInfo(id, pkg, SessionState.FAILED) }
        }

    private fun applyOne(id: Int, e: Entry, p: EngineParams) {
        val key = Key(p.layoutId, p.sampleRate, p.frameDurationMs)
        if (key != e.key || p.gainsDb.size != e.gains.size) {
            // Release first: two controllers on one session would fight over it.
            recreate(id, e, p)
            return
        }
        try {
            if (e.state == SessionState.NO_CONTROL) {
                if (e.handle.hasControl()) recreate(id, e, p)
                return
            }
            diff(e, p)
        } catch (t: Throwable) {
            Log.w(TAG, "handle call failed session=$id, recreating: ${t.javaClass.name}: ${t.message}")
            recreate(id, e, p)
        }
    }

    private fun diff(e: Entry, p: EngineParams) {
        val h = e.handle
        if (p.bypass) {
            if (e.enabled) {
                check(h.setEnabled(false)) { "setEnabled(false) refused" }
                e.enabled = false
            }
            // lastSent stays put so un-bypass pushes the accumulated difference.
            return
        }
        for (i in e.gains.indices) {
            val g = p.gainsDb[i]
            if (abs(g - e.gains[i]) > GAIN_EPSILON_DB) {
                h.setBand(i, p.cutoffsHz[i], g)
                e.gains[i] = g
            }
        }
        if (abs(p.inputGainDb - e.inputGain) > GAIN_EPSILON_DB) {
            h.setInputGain(p.inputGainDb)
            e.inputGain = p.inputGainDb
        }
        val lim = Lim(p.limiter, p.limiterEnabled)
        if (lim != e.lim) {
            h.setLimiter(p)
            e.lim = lim
        }
        if (!e.enabled) {
            check(h.setEnabled(true)) { "setEnabled(true) refused" }
            e.enabled = true
        }
    }

    /** Release the old handle, build a fresh one from [p]. A failure here drops the session. */
    private fun recreate(id: Int, e: Entry, p: EngineParams) {
        releaseQuietly(id, e.handle)
        try {
            sessions[id] = build(id, e.pkg, p, e.lastSeen)
        } catch (t: Throwable) {
            Log.w(TAG, "recreate failed session=$id: ${t.javaClass.name}: ${t.message}")
            sessions.remove(id)
            recordFailure(id, e.pkg)
        }
    }

    private fun dropToFailed(id: Int, e: Entry) {
        if (sessions[id] === e) releaseQuietly(id, e.handle)
        sessions.remove(id)
        recordFailure(id, e.pkg)
    }

    private fun build(id: Int, pkg: String, p: EngineParams, lastSeen: Long): Entry {
        val h = retrying(
            CREATE_ATTEMPTS, CREATE_BACKOFF_MS, sleeper,
            { attempt, t -> Log.i(TAG, "create retry $attempt session=$id: ${t.javaClass.name}: ${t.message}") },
        ) { factory.create(id, p) }
        val state = try {
            if (h.hasControl()) SessionState.OK else SessionState.NO_CONTROL
        } catch (t: Throwable) {
            releaseQuietly(id, h)
            throw t
        }
        return Entry(h, pkg, lastSeen, p, state)
    }

    private fun evictIfFull() {
        while (sessions.size >= maxSessions) {
            val victim = sessions.entries.minByOrNull { it.value.lastSeen } ?: return
            sessions.remove(victim.key)
            releaseQuietly(victim.key, victim.value.handle)
            Log.i(TAG, "evicted session=${victim.key} (cap $maxSessions)")
        }
    }

    private fun recordFailure(id: Int, pkg: String) {
        failed.remove(id)
        failed[id] = pkg
        while (failed.size > maxSessions) failed.remove(failed.keys.first())
    }

    private fun releaseQuietly(id: Int, h: EffectHandle) {
        try {
            h.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release failed session=$id: ${t.javaClass.name}: ${t.message}")
        }
    }

    private inline fun guarded(op: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$op failed: ${t.javaClass.name}: ${t.message}")
        }
        try {
            onChange()
        } catch (t: Throwable) {
            Log.e(TAG, "onChange failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    companion object {
        const val TAG = "EQ-SVC"

        /** Session 0 is the output mix; broadcasts can never carry it (SessionIntent rejects <= 0). */
        const val GLOBAL_ID = 0
        private const val GLOBAL_PKG = "global"
        const val DEFAULT_MAX_SESSIONS = 16

        /** Gain differences at or below this are inaudible and are not sent (Assumption A3). */
        const val GAIN_EPSILON_DB = 0.01f

        /** Total create attempts per build; transient failures right after a session opens are common. */
        const val CREATE_ATTEMPTS = 3

        /** Pause after the first and second failed attempt. */
        val CREATE_BACKOFF_MS = longArrayOf(50L, 100L)
    }
}
