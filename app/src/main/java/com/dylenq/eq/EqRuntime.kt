package com.dylenq.eq

import android.media.AudioManager
import android.media.AudioTrack
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.service.EffectFactory
import com.dylenq.eq.service.EffectManager
import com.dylenq.eq.service.RealEffectFactory
import com.dylenq.eq.service.SessionIntent
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the one [EqCore], the effect manager and the single worker thread. Every effect call runs
 * on that thread; receivers only enqueue.
 */
class EqRuntime(
    factory: EffectFactory = RealEffectFactory(),
    /** Consumed by the persistence plan. */
    val stateDir: File? = null,
) {
    val core: EqCore = EqCore()

    private val attached = AtomicBoolean(false)
    @Volatile private var closed = false

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "eq-effects")
    }

    private val manager = EffectManager(factory, { currentParams() })

    val isAttached: Boolean get() = attached.get()

    /** Marks the service as running. Idempotent. */
    fun attach() { attached.set(true) }

    fun detach() { attached.set(false) }

    /** Enqueue a validated session event; starts the worker lazily. */
    fun post(event: SessionIntent.Event) {
        if (closed) return
        try {
            worker.execute {
                when (event) {
                    is SessionIntent.Event.Open -> manager.onOpen(event.session, event.pkg)
                    is SessionIntent.Event.Close -> manager.onClose(event.session)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    /** Runs [block] on the worker and waits up to 5 s. For tests and host debugging. */
    fun <T> inspect(block: (EffectManager) -> T): T =
        worker.submit(Callable { block(manager) }).get(5, TimeUnit.SECONDS)

    fun shutdown() {
        if (closed) return
        try {
            inspect { it.releaseAll() }
        } catch (_: Throwable) {
        }
        closed = true
        attached.set(false)
        worker.shutdownNow()
    }

    // Called on the worker at apply time.
    private fun currentParams(): EngineParams {
        val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        val frameMs = core.snapshot().frameDurationMs
        return core.engineParams(rate.toUInt(), frameMs)
    }
}
