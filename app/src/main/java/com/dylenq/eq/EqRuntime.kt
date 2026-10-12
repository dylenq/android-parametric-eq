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
import com.dylenq.eq.service.SessionIntent
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    /** Consumed by the persistence plan. */
    val stateDir: File? = null,
) {
    private val core: EqCore = EqCore()

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

    private val manager = EffectManager(factory, { currentParams() }, { publishStatus() })

    private val _status = MutableStateFlow(ServiceStatus())
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
                    }
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
        dirty.trySend(Unit)
    }

    /** Runs [block] on the worker and waits up to 5 s. For tests and host debugging. */
    fun <T> inspect(block: (EffectManager) -> T): T = runBlocking {
        withTimeout(5000) { withContext(dispatcher) { block(manager) } }
    }

    fun shutdown() {
        if (closed) return
        try {
            inspect { it.releaseAll() }
        } catch (_: Throwable) {
        }
        closed = true
        attached.set(false)
        job.cancel()
        dispatcher.close()
        executor.awaitTermination(2, TimeUnit.SECONDS)
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
}
