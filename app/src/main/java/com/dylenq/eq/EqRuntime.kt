package com.dylenq.eq

import android.media.AudioManager
import android.os.Build
import android.media.AudioTrack
import android.util.Log
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.service.EffectFactory
import com.dylenq.eq.service.EngineProbe
import com.dylenq.eq.service.RealEngineProbe
import com.dylenq.eq.service.AppEntry
import com.dylenq.eq.service.EffectManager
import com.dylenq.eq.service.RealEffectFactory
import com.dylenq.eq.service.ServiceStatus
import com.dylenq.eq.service.SettingsStore
import com.dylenq.eq.service.StateStore
import com.dylenq.eq.service.StoreNotice
import com.dylenq.eq.service.storeNoticeText
import com.dylenq.eq.service.SessionIntent
import com.dylenq.eq.service.SessionSource
import com.dylenq.eq.service.TrackedRow
import com.dylenq.eq.service.TrackingControl
import com.dylenq.eq.service.TrackingState
import com.dylenq.eq.service.usableSampleRate
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Owns the one [EqCore], the effect manager and the single "eq-effects" worker. Every effect call
 * runs on that worker; receivers and the UI only enqueue (session events) or signal (dirty).
 */
class EqRuntime(
    factory: EffectFactory = RealEffectFactory(),
    /** Directory holding eq-state.json. Null keeps the state in memory only. */
    val stateDir: File? = null,
    /** Null keeps the two flags in memory only. */
    val settings: SettingsStore = SettingsStore(null),
    /** Null means no probe: the core stays uncapped. Before [saver] so trailing-lambda callers keep working. */
    private val probe: EngineProbe? = null,
    saver: ((EqCore, String) -> Unit)? = null,
) {
    private val store: StateStore? = stateDir?.let { d ->
        if (saver != null) StateStore(d, saver) else StateStore(d)
    }
    private val opened: Pair<EqCore, StoreNotice?> = store?.open() ?: (EqCore() to null)
    private val core: EqCore = opened.first

    // Worker only after construction: the probe runs at most once per process.
    private var probed = false

    /** Debug/test override of the engine band cap. Never persisted, never a measurement. */
    @Volatile private var forcedCap: Int? = null

    /** Makes the forced-cap check and the core update one step across the worker and the debug thread. */
    private val capLock = Any()

    /** The cap the core currently applies (forced value, else the measured one), or null when uncapped. */
    val activeEngineBandCap: Int?
        get() = forcedCap ?: measuredCap()

    private fun measuredCap(): Int? =
        settings.engineMaxBands?.takeIf { settings.engineProbeFingerprint == Build.FINGERPRINT }

    init {
        // A cached cap for this OS build applies before any UI curve is computed.
        val cached = settings.engineMaxBands
        if (cached != null && settings.engineProbeFingerprint == Build.FINGERPRINT) {
            core.setEngineBandCap(cached.toUInt())
        }
    }

    /** The single entry point for mutating the EQ. */
    val controller: EqController = EqController(core) { markDirty() }.also { it.onPresetsChanged = { clearPresetCache() } }

    private data class PresetKey(
        val key: String, val rate: UInt, val frame: Float, val cap: Int?, val limiter: Boolean, val bypass: Boolean,
    )

    // Preset params by (key, rate, frame, cap, limiter flag, bypass); cleared on any preset save, rename
    // or delete. Presets are immutable by key, so live edits never touch it (D-08). The generation stops
    // a lookup that started before a clear from storing a stale result after it.
    private val presetCache = HashMap<PresetKey, EngineParams>()
    private var presetGeneration = 0L
    private val presetLock = Any()

    private fun clearPresetCache() {
        synchronized(presetLock) {
            presetCache.clear()
            presetGeneration++
        }
    }

    private val attached = AtomicBoolean(false)
    @Volatile private var closed = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "eq-effects") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Conflated: any number of edits before the worker wakes collapse into one apply. */
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private sealed interface Command {
        data class Session(val event: SessionIntent.Event) : Command
        data class SetGlobal(val on: Boolean) : Command
        data class TrackerOpen(val session: Int, val pkg: String) : Command
        data class TrackerClose(val session: Int) : Command
    }

    // Bounded because the session receiver is exported: a flood from another app drops the oldest
    // events instead of growing without limit. A lost close is reclaimed by the session cap.
    private val events = Channel<Command>(EVENT_QUEUE_CAPACITY, BufferOverflow.DROP_OLDEST)

    /** Fires once the trailing debounce timer expires; handled on the worker. */
    private val saveTick = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var needsSave = false
    private var saveTimer: Job? = null
    private var saveFailureShown = false

    private val manager = EffectManager(
        factory, { currentParams() }, { publishStatus() },
        onGlobalRejected = { reason -> onGlobalRejected(reason) },
        isEnabled = { pkg -> ruleFor(pkg)?.enabled ?: true },
        presetFor = { pkg -> ruleFor(pkg)?.presetKey },
        paramsForKey = { key -> presetParams(key) },
    )

    // Bypass is core state, so the very first status already carries the loaded value instead of
    // waiting for the worker's first publishStatus (a cold-start UI would otherwise flash "on").
    // The persisted global mode is seeded the same way; the worker applies it before its first
    // publish, and a rejection then corrects the status through onGlobalRejected.
    private val _status = MutableStateFlow(
        ServiceStatus(bypass = controller.snapshot().bypass, globalMode = settings.globalMode, engineMaxBands = measuredCap(), tracking = if (settings.playerTracking) TrackingState.Working(0) else TrackingState.Off, notice = storeNoticeText(opened.second))
    )
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    /** Invoked on the worker after every apply. The persistence plan fills this in. */
    @Volatile var onApplied: () -> Unit = {}

    val isAttached: Boolean get() = attached.get()

    private val job = scope.launch {
        // The persisted global-mode choice is applied before the first publish, so the first
        // published status reports the real state (a rejection flips the setting and sets the notice).
        try {
            if (settings.globalMode) manager.setGlobalMode(true)
        } catch (t: Throwable) {
            Log.e("EQ-SVC", "initial global mode failed: ${t.javaClass.name}: ${t.message}")
        }
        publishStatus()
        while (isActive) {
            try {
                select<Unit> {
                    events.onReceive { c ->
                        when (c) {
                            is Command.Session -> when (val e = c.event) {
                                is SessionIntent.Event.Open -> {
                                    noteApp(e.pkg)
                                    manager.onOpen(e.session, e.pkg)
                                }
                                is SessionIntent.Event.Close -> manager.onClose(e.session)
                            }
                            is Command.SetGlobal -> manager.setGlobalMode(c.on)
                            is Command.TrackerOpen -> {
                                noteApp(c.pkg)
                                manager.onOpen(c.session, c.pkg, SessionSource.TRACKED)
                            }
                            is Command.TrackerClose -> manager.onClose(c.session, SessionSource.TRACKED)
                        }
                    }
                    dirty.onReceive {
                        manager.applyLatest()
                        onApplied()
                        restartSaveTimer()
                    }
                    saveTick.onReceive { flushSave() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("EQ-SVC", "worker iteration failed: ${t.javaClass.name}: ${t.message}")
            }
            publishStatus()
        }
    }

    /** Marks the service as running. Idempotent. */
    fun attach() {
        attached.set(true)
        _status.update { it.copy(serviceRunning = true) }
    }

    fun detach() {
        attached.set(false)
        _status.update { it.copy(serviceRunning = false) }
    }

    /** Clears the current notice. A no-op when there is none; later status updates keep it cleared. */
    fun dismissNotice() {
        _status.update { if (it.notice == null) it else it.copy(notice = null) }
    }

    /** Enqueue a validated session event. Safe from any thread. */
    fun post(event: SessionIntent.Event) {
        if (closed) return
        events.trySend(Command.Session(event))
    }

    /** Enqueue what the player tracker found. Safe from any thread; handled on the worker in order. */
    fun postTracked(opens: List<TrackedRow>, closes: List<Int>) {
        if (closed) return
        for (o in opens) events.trySend(Command.TrackerOpen(o.session, o.pkg))
        for (c in closes) events.trySend(Command.TrackerClose(c))
    }

    /** Publishes the tracking state. Safe from any thread; publishStatus keeps the field. */
    fun setTrackingState(state: TrackingState) {
        _status.update { if (it.tracking == state) it else it.copy(tracking = state) }
    }

    /**
     * Test hook: force the engine band cap to [n] (coerced into 8..512). Null clears the override and
     * restores the measured cap (uncapped when none). Never written to settings; the worker re-applies.
     */
    fun overrideEngineBandCap(n: Int?) {
        synchronized(capLock) {
            forcedCap = n?.coerceIn(8, 512)
            val target = forcedCap ?: measuredCap()
            core.setEngineBandCap(target?.toUInt())
        }
        markDirty()
    }

    /** Cheap, any thread: ask the worker to push the current EQ to every session. */
    fun markDirty() {
        if (closed) return
        needsSave = true
        dirty.trySend(Unit)
    }

    /** Re-applies the EQ without marking it unsaved (the output device, and with it the rate, may have changed). */
    fun reapply() {
        if (closed) return
        dirty.trySend(Unit)
    }

    /** Runs [block] on the worker and waits up to 5 s. For tests and host debugging. */
    fun <T> inspect(block: (EffectManager) -> T): T = runBlocking {
        withTimeout(5000) { withContext(dispatcher) { block(manager) } }
    }

    /** Writes pending state now (service stop). Safe from any thread. */
    fun flush() {
        if (closed) return
        try {
            inspect { flushSave() }
        } catch (t: Throwable) {
            Log.w("EQ-SVC", "flush failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /** Switches an app on or off. Saved through the debounce; the worker re-evaluates its sessions. */
    fun setAppEnabled(pkg: String, on: Boolean) {
        if (closed) return
        controller.setAppEnabled(pkg, on)
    }

    /** Gives an app a preset, or null for the live EQ. Returns an error text, or null on success. */
    fun setAppPreset(pkg: String, key: String?): String? {
        if (closed) return null
        return try {
            controller.setAppPreset(pkg, key)
            null
        } catch (t: Throwable) {
            Log.w("EQ-SVC", "set app preset failed: ${t.javaClass.name}: ${t.message}")
            "That preset no longer exists."
        }
    }

    fun setStartOnBoot(on: Boolean) { settings.startOnBoot = on }

    /** Set by the service while it runs; null otherwise. */
    @Volatile var trackingControl: TrackingControl? = null

    /** Persists the advanced player tracking switch and tells the service's controller. */
    fun setPlayerTracking(on: Boolean) {
        if (closed) return
        settings.playerTracking = on
        val c = trackingControl
        if (c != null) c.setEnabled(on) else setTrackingState(if (on) TrackingState.NeedsPermission else TrackingState.Off)
    }

    /** The DUMP permission may have changed (resume, a grant); the controller checks again. */
    fun recheckTracking() { trackingControl?.recheckPermission() }

    /** Persists the choice and asks the worker to apply it. Turning it on clears an old notice. */
    fun setGlobalMode(on: Boolean) {
        if (closed) return
        settings.globalMode = on
        if (on) _status.update { it.copy(notice = null) }
        events.trySend(Command.SetGlobal(on))
    }

    fun shutdown() {
        if (closed) return
        try {
            inspect {
                it.releaseAll()
                flushSave()
            }
        } catch (_: Throwable) {
        }
        closed = true
        attached.set(false)
        job.cancel()
        dispatcher.close()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }

    // Worker only: global mode was refused; turn the setting off and tell the user.
    private fun onGlobalRejected(reason: String) {
        settings.globalMode = false
        // globalMode goes false in the same update, so a seeded "on" never shows next to the notice.
        _status.update { it.copy(globalMode = false, notice = "Global mode turned off: this phone rejected it ($reason)") }
    }

    // Worker only: trailing debounce, each apply pushes the deadline out.
    private fun restartSaveTimer(delayMs: Long = SAVE_DEBOUNCE_MS) {
        if (store == null) return
        saveTimer?.cancel()
        saveTimer = scope.launch {
            delay(delayMs)
            saveTick.trySend(Unit)
        }
    }

    // Worker (or flush/shutdown through inspect): never throws.
    private fun flushSave() {
        saveTimer?.cancel()
        saveTimer = null
        val st = store ?: return
        if (!needsSave) return
        needsSave = false
        try {
            st.save(core)
            saveFailureShown = false
            _status.update { if (it.notice == SAVE_FAILED_NOTICE) it.copy(notice = null) else it }
        } catch (t: Throwable) {
            needsSave = true
            Log.w("EQ-SVC", "state save failed: ${t.javaClass.name}: ${t.message}")
            // Once per run of failures, so a dismissed notice does not come back with every retry.
            if (!saveFailureShown) _status.update { it.copy(notice = SAVE_FAILED_NOTICE) }
            saveFailureShown = true
            restartSaveTimer(SAVE_RETRY_MS)
        }
    }

    // Worker only. A new package is saved through the debounce without an apply (D-12).
    private fun noteApp(pkg: String) {
        try {
            if (controller.noteApp(pkg)) {
                needsSave = true
                restartSaveTimer()
            }
        } catch (t: Throwable) {
            Log.w("EQ-SVC", "noteApp failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun ruleFor(pkg: String) = controller.appRules().firstOrNull { it.pkg == pkg }

    // Worker only.
    private fun publishStatus() {
        try {
            val count = manager.sessionCount()
            val bypass = controller.snapshot().bypass
            val global = manager.isGlobalOn()
            val sessions = manager.sessionInfos()
            val apps = controller.appRules().map { r ->
                val live = sessions.firstOrNull { s -> s.pkg == r.pkg }
                AppEntry(r.pkg, r.enabled, r.presetKey, r.pkg, live != null, live?.state, r.lastSeen.toLong())
            }
            _status.update {
                it.copy(
                    sessionCount = count, bypass = bypass, globalMode = global, sessions = sessions, apps = apps,
                    engineMaxBands = measuredCap(),
                )
            }
        } catch (t: Throwable) {
            Log.e("EQ-SVC", "publishStatus failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    // Called on the worker at apply time.
    private fun currentParams(): EngineParams {
        ensureProbed()
        val rate = usableSampleRate(AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC))
        val frameMs = controller.snapshot().frameDurationMs
        return controller.engineParams(rate, frameMs)
    }

    // Called on the worker. Throws when the key does not resolve; the manager then uses the live params.
    private fun presetParams(key: String): EngineParams {
        ensureProbed()
        val rate = usableSampleRate(AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC))
        val snap = controller.snapshot()
        val ck = PresetKey(key, rate, snap.frameDurationMs, activeEngineBandCap, snap.limiterEnabled, snap.bypass)
        val gen: Long
        synchronized(presetLock) {
            presetCache[ck]?.let { return it }
            gen = presetGeneration
        }
        val p = controller.engineParamsForPreset(key, rate, snap.frameDurationMs)
        synchronized(presetLock) { if (gen == presetGeneration) presetCache[ck] = p }
        return p
    }

    // Worker only. Not an edit: no markDirty, no undo step.
    private fun ensureProbed() {
        val p = probe ?: return
        if (probed) return
        probed = true
        if (settings.engineMaxBands != null && settings.engineProbeFingerprint == Build.FINGERPRINT) return
        try {
            val n = minOf(p.measureMaxBands(EngineProbe.PROBE_BANDS), EngineProbe.PROBE_BANDS)
            settings.recordEngineProbe(n, Build.FINGERPRINT)
            synchronized(capLock) { if (forcedCap == null) core.setEngineBandCap(n.toUInt()) }
            Log.i("EQ-SVC", "engine probe: $n bands accepted")
            publishStatus()
        } catch (t: Throwable) {
            Log.w("EQ-SVC", "engine probe failed, staying uncapped: ${t.javaClass.name}: ${t.message}")
        }
    }

    companion object {
        /** Trailing debounce for state writes. */
        const val SAVE_DEBOUNCE_MS = 500L

        /** Pause before a failed state write is tried again. */
        const val SAVE_RETRY_MS = 5_000L

        const val SAVE_FAILED_NOTICE = "Your changes could not be saved. Free up some storage on the phone."

        private const val EVENT_QUEUE_CAPACITY = 256

        fun create(context: android.content.Context, factory: EffectFactory = RealEffectFactory()): EqRuntime =
            EqRuntime(
                factory, context.filesDir, SettingsStore(context),
                probe = RealEngineProbe(context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager),
            )
    }
}
