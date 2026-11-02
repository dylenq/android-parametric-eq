package com.dylenq.eq.service

/** Runs delayed tasks on one thread. Injected so tests never sleep. */
interface TrackScheduler {
    /** Runs [task] after [delayMs]; the returned function cancels it. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** Where scan results go (the runtime in the app, a fake in tests). */
interface TrackingSink {
    fun postTracked(opens: List<TrackedRow>, closes: List<Int>)
    fun setTrackingState(state: TrackingState)
}

/** The real scheduler: one thread named "eq-tracker", never the effect worker. */
class ThreadTrackScheduler : TrackScheduler {
    private val exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "eq-tracker").apply { isDaemon = true }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val f = try {
            exec.schedule({ try { task() } catch (_: Throwable) {} }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return {}
        }
        return { f.cancel(false) }
    }

    fun shutdown() { exec.shutdownNow() }
}

/**
 * Decides when to read the session table. A change in playback schedules scans at +250 ms, +1 s and
 * +3 s (the row can appear late); while something plays it also scans every 5 s. An idle phone
 * schedules nothing. The permission is re-checked before every dump.
 */
class TrackingController(
    private val scheduler: TrackScheduler,
    private val source: DumpSource,
    private val permission: () -> Boolean,
    private val resolver: AppResolver,
    private val ownUid: Int,
    private val sink: TrackingSink,
) {
    private val lock = Any()
    private val tracker = PlayerTracker()
    private var enabled = false
    private var active = 0
    private var cancels = ArrayList<() -> Unit>()
    private var periodic: (() -> Unit)? = null

    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            if (on == enabled) return
            enabled = on
            if (!on) {
                stopAll()
                closeAll()
                sink.setTrackingState(TrackingState.Off)
                return
            }
            if (!permission()) {
                sink.setTrackingState(TrackingState.NeedsPermission)
                return
            }
            sink.setTrackingState(TrackingState.Working(0))
            burst()
            if (active > 0) startPeriodic()
        }
    }

    /** Playback started, stopped or changed; [activeCount] is the number of active players. */
    fun onPlaybackChanged(activeCount: Int) {
        synchronized(lock) {
            active = activeCount
            if (!enabled || !permission()) return
            burst()
            if (active > 0) startPeriodic() else stopPeriodic()
        }
    }

    /** Call when the permission may have changed (resume, a grant, a revoke). */
    fun recheckPermission() {
        synchronized(lock) {
            if (!enabled) return
            if (!permission()) {
                stopAll()
                closeAll()
                sink.setTrackingState(TrackingState.NeedsPermission)
            } else if (cancels.isEmpty() && periodic == null) {
                sink.setTrackingState(TrackingState.Working(0))
                burst()
                if (active > 0) startPeriodic()
            }
        }
    }

    fun stop() = setEnabled(false)

    private fun burst() {
        for (c in cancels) c()
        cancels = ArrayList()
        for (d in BURST_MS) cancels.add(scheduler.schedule(d) { scan() })
    }

    private fun startPeriodic() {
        if (periodic != null) return
        periodic = scheduler.schedule(PERIOD_MS) { periodicTick() }
    }

    private fun stopPeriodic() {
        periodic?.invoke()
        periodic = null
    }

    private fun stopAll() {
        for (c in cancels) c()
        cancels = ArrayList()
        stopPeriodic()
    }

    private fun periodicTick() {
        synchronized(lock) {
            periodic = null
            if (!enabled || active <= 0) return
        }
        scan()
        synchronized(lock) {
            if (enabled && active > 0) startPeriodic()
        }
    }

    private fun closeAll() {
        val closes = tracker.reset()
        if (closes.isNotEmpty()) sink.postTracked(emptyList(), closes)
    }

    /** One scan. The dump itself runs outside the lock; the gate is checked first, every time. */
    private fun scan() {
        synchronized(lock) {
            if (!enabled) return
            if (!permission()) {
                sink.setTrackingState(TrackingState.NeedsPermission)
                return
            }
        }
        val result = try { source.read() } catch (t: Throwable) { DumpResult.Unavailable("the audio server could not be read") }
        synchronized(lock) {
            if (!enabled) return
            when (result) {
                is DumpResult.Sessions -> {
                    val kept = TrackedApps.filter(result.rows, ownUid, resolver)
                    val d = tracker.scan(kept)
                    if (d.opens.isNotEmpty() || d.closes.isNotEmpty()) sink.postTracked(d.opens, d.closes)
                    sink.setTrackingState(TrackingState.Working(kept.size))
                }
                is DumpResult.Denied -> sink.setTrackingState(TrackingState.NeedsReboot)
                is DumpResult.Unavailable -> sink.setTrackingState(TrackingState.Unsupported(result.reason))
            }
        }
    }

    companion object {
        val BURST_MS = longArrayOf(250, 1000, 3000)
        const val PERIOD_MS = 5000L
    }
}
