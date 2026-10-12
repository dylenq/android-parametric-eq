package com.dylenq.eq

import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.service.EffectFactory
import com.dylenq.eq.service.EffectManager
import com.dylenq.eq.service.RealEffectFactory
import com.dylenq.eq.service.ServiceStatus
import com.dylenq.eq.service.SettingsStore
import com.dylenq.eq.service.StateStore
import com.dylenq.eq.service.StoreNotice
import com.dylenq.eq.service.SessionIntent
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    saver: ((EqCore, String) -> Unit)? = null,
) {
    private val store: StateStore? = stateDir?.let { d ->
        if (saver != null) StateStore(d, saver) else StateStore(d)
    }
    private val opened: Pair<EqCore, StoreNotice?> = store?.open() ?: (EqCore() to null)
    private val core: EqCore = opened.first

    /** The single entry point for mutating the EQ. */
    val controller: EqController = EqController(core) { markDirty() }

    private val attached = AtomicBoolean(false)
    @Volatile private var closed = false

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "eq-effects") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Conflated: any number of edits before the worker wakes collapse into one apply. */
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    private val events = Channel<SessionIntent.Event>(Channel.UNLIMITED)

    /** Fires once the trailing debounce timer expires; handled on the worker. */
    private val saveTick = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var needsSave = false
    private var saveTimer: Job? = null

    private val manager = EffectManager(factory, { currentParams() }, { publishStatus() })

    private val _status = MutableStateFlow(
        ServiceStatus(notice = when (opened.second) {
            is StoreNotice.NewerVersion -> "Saved settings were made by a newer version and were set aside"
            StoreNotice.Damaged -> "Saved settings were damaged and were set aside"
            null -> null
        })
    )
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    /** Invoked on the worker after every apply. The persistence plan fills this in. */
    @Volatile var onApplied: () -> Unit = {}

    val isAttached: Boolean get() = attached.get()

    private val job = scope.launch {
        publishStatus()
        while (isActive) {
            try {
                select<Unit> {
                    events.onReceive { e ->
                        when (e) {
                            is SessionIntent.Event.Open -> manager.onOpen(e.session, e.pkg)
                            is SessionIntent.Event.Close -> manager.onClose(e.session)
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
    fun attach() { attached.set(true) }

    fun detach() { attached.set(false) }

    /** Enqueue a validated session event. Safe from any thread. */
    fun post(event: SessionIntent.Event) {
        if (closed) return
        events.trySend(event)
    }

    /** Cheap, any thread: ask the worker to push the current EQ to every session. */
    fun markDirty() {
        if (closed) return
        needsSave = true
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

    fun setStartOnBoot(on: Boolean) { settings.startOnBoot = on }

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

    // Worker only: trailing debounce, each apply pushes the deadline out.
    private fun restartSaveTimer() {
        if (store == null) return
        saveTimer?.cancel()
        saveTimer = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
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
        } catch (t: Throwable) {
            needsSave = true
            Log.w("EQ-SVC", "state save failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    // Worker only.
    private fun publishStatus() {
        try {
            _status.value = _status.value.copy(
                sessionCount = manager.sessionCount(),
                bypass = controller.snapshot().bypass,
                sessions = manager.sessionInfos(),
            )
        } catch (t: Throwable) {
            Log.e("EQ-SVC", "publishStatus failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    // Called on the worker at apply time.
    private fun currentParams(): EngineParams {
        val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val frameMs = controller.snapshot().frameDurationMs
        return controller.engineParams(rate.toUInt(), frameMs)
    }

    companion object {
        /** Trailing debounce for state writes. */
        const val SAVE_DEBOUNCE_MS = 500L

        fun create(context: android.content.Context, factory: EffectFactory = RealEffectFactory()): EqRuntime =
            EqRuntime(factory, context.filesDir, SettingsStore(context))
    }
}
