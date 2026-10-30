package com.dylenq.eq.service

/** Injectable pause so retry tests never sleep. */
fun interface Sleeper {
    fun sleep(ms: Long)
}

/**
 * Runs [block] up to [attempts] times. After a failed attempt that is not the last, [onRetry] is
 * called and the thread pauses for backoffMs[attempt] (the last entry is reused if the array is
 * short). The last failure is rethrown. An interrupted sleep restores the interrupt flag and stops
 * retrying, rethrowing the failure that preceded it.
 */
fun <T> retrying(
    attempts: Int,
    backoffMs: LongArray,
    sleeper: Sleeper,
    onRetry: (attempt: Int, t: Throwable) -> Unit,
    block: () -> T,
): T {
    require(attempts >= 1) { "attempts must be at least 1" }
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (t: Throwable) {
            if (attempt >= attempts - 1) throw t
            onRetry(attempt + 1, t)
            val ms = if (backoffMs.isEmpty()) 0L else backoffMs[minOf(attempt, backoffMs.size - 1)]
            try {
                sleeper.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw t
            }
            attempt++
        }
    }
}
