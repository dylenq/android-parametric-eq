package com.dylenq.eq.testsupport

import com.dylenq.eq.service.EngineProbe
import java.util.concurrent.atomic.AtomicInteger

/** Scripted [EngineProbe]: returns min(result, requested) or throws [fault]. */
class FakeEngineProbe(@Volatile var result: Int = 512, @Volatile var fault: Throwable? = null) : EngineProbe {
    private val counter = AtomicInteger()
    val calls: Int get() = counter.get()

    override fun measureMaxBands(requested: Int): Int {
        counter.incrementAndGet()
        fault?.let { throw it }
        return minOf(result, requested)
    }
}
