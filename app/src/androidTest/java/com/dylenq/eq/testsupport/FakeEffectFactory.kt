package com.dylenq.eq.testsupport

import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.service.EffectFactory
import com.dylenq.eq.service.EffectHandle
import java.util.concurrent.ConcurrentHashMap

/** In-memory [EffectFactory] that records every call and supports injected faults. Thread-safe. */
class FakeEffectFactory : EffectFactory {
    data class Call(val session: Int, val method: String, val args: List<Any?> = emptyList())

    private val lock = Any()
    private val log = ArrayList<Call>()
    private val handles = ConcurrentHashMap<Int, FakeHandle>()
    private val created = ArrayList<Pair<Int, EngineParams>>()
    private var createFaults = 0
    private var createFault: Throwable? = null
    private val sessionFaults = ConcurrentHashMap<Int, Throwable>()
    private val failAfterCalls = ConcurrentHashMap<Int, Int>()
    private val lost = ConcurrentHashMap.newKeySet<Int>()
    private val enableRejected = ConcurrentHashMap.newKeySet<Int>()

    fun failNextCreate(t: Throwable) = failNextCreates(1, t)
    /** The next [n] create calls throw [t]. */
    fun failNextCreates(n: Int, t: Throwable) { synchronized(lock) { createFaults = n; createFault = t } }
    fun failSession(session: Int, t: Throwable) { sessionFaults[session] = t }
    /** Every handle created after this call throws once it has served [calls] mutator calls. */
    @Volatile var failAfter: Int? = null
        private set
    fun failAfter(calls: Int) { failAfter = calls }
    fun controlLost(session: Int) { lost.add(session) }
    /** setEnabled on this session returns false (platform status < 0). */
    fun rejectEnable(session: Int) { enableRejected.add(session) }

    fun calls(): List<Call> = synchronized(lock) { ArrayList(log) }
    fun callCount(session: Int, method: String): Int =
        synchronized(lock) { log.count { it.session == session && it.method == method } }
    fun creates(): List<Pair<Int, EngineParams>> = synchronized(lock) { ArrayList(created) }
    fun handle(session: Int): FakeHandle? = handles[session]

    override fun create(session: Int, params: EngineParams): EffectHandle {
        synchronized(lock) {
            created.add(session to params)
            log.add(Call(session, "create", listOf(params)))
            if (createFaults > 0) { createFaults--; throw createFault!! }
        }
        sessionFaults[session]?.let { throw it }
        val h = FakeHandle(session, params, failAfter)
        handles[session] = h
        return h
    }

    inner class FakeHandle(val session: Int, params: EngineParams, private val failAfterN: Int?) : EffectHandle {
        private val gains = params.gainsDb.toMutableList()
        private val cutoffs = params.cutoffsHz.toMutableList()
        private var inputGain = params.inputGainDb
        private var enabled = !params.bypass
        private var limThr = params.limiter.thresholdDb
        private var limOn = params.limiterEnabled
        @Volatile var released = false
            private set
        private var served = 0

        private fun record(method: String, vararg args: Any?) {
            synchronized(lock) {
                log.add(Call(session, method, args.toList()))
                if (method != "release") {
                    served++
                    failAfterN?.let { if (served > it) throw IllegalStateException("injected fault after $it calls") }
                }
            }
        }

        override fun setBand(index: Int, cutoffHz: Float, gainDb: Float) {
            record("setBand", index, cutoffHz, gainDb); cutoffs[index] = cutoffHz; gains[index] = gainDb
        }
        override fun setInputGain(db: Float) { record("setInputGain", db); inputGain = db }
        override fun setLimiter(params: EngineParams) {
            record("setLimiter", params.limiter.thresholdDb, params.limiterEnabled)
            limThr = params.limiter.thresholdDb; limOn = params.limiterEnabled
        }
        override fun setEnabled(on: Boolean): Boolean {
            record("setEnabled", on)
            if (session in enableRejected) return false
            enabled = on
            return true
        }
        override fun hasControl(): Boolean = session !in lost
        override fun release() { record("release"); released = true }

        override fun readBandGain(i: Int): Float = gains[i]
        override fun readBandCutoff(i: Int): Float = cutoffs[i]
        override fun readInputGain(): Float = inputGain
        override fun readEnabled(): Boolean = enabled
        override fun readLimiterThresholdDb(): Float = limThr
        override fun readLimiterEnabled(): Boolean = limOn
    }
}
