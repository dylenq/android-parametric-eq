package com.dylenq.eq.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryTest {
    private val backoff = longArrayOf(50L, 100L)
    private val sleeps = ArrayList<Long>()
    private val sleeper = Sleeper { sleeps.add(it) }

    private fun failing(times: Int): () -> String {
        var calls = 0
        return {
            calls++
            if (calls <= times) throw RuntimeException("x")
            "ok"
        }
    }

    @Test
    fun first_try_success_does_not_sleep() {
        assertEquals("ok", retrying(3, backoff, sleeper, { _, _ -> }, failing(0)))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun one_failure_sleeps_50() {
        assertEquals("ok", retrying(3, backoff, sleeper, { _, _ -> }, failing(1)))
        assertEquals(listOf(50L), sleeps)
    }

    @Test
    fun two_failures_sleep_50_then_100() {
        val retries = ArrayList<Int>()
        assertEquals("ok", retrying(3, backoff, sleeper, { a, _ -> retries.add(a) }, failing(2)))
        assertEquals(listOf(50L, 100L), sleeps)
        assertEquals(listOf(1, 2), retries)
    }

    @Test
    fun three_failures_throw_the_last_exception() {
        var calls = 0
        val last = RuntimeException("third")
        try {
            retrying(3, backoff, sleeper, { _, _ -> }) {
                calls++
                throw if (calls == 3) last else RuntimeException("early")
            }
            throw AssertionError("expected throw")
        } catch (t: RuntimeException) {
            assertSame(last, t)
        }
        assertEquals(3, calls)
        assertEquals(listOf(50L, 100L), sleeps)
    }

    @Test
    fun interrupted_sleep_stops_early_and_sets_the_flag() {
        var calls = 0
        val first = RuntimeException("first")
        try {
            retrying(3, backoff, { throw InterruptedException() }, { _, _ -> }) {
                calls++
                throw first
            }
            throw AssertionError("expected throw")
        } catch (t: RuntimeException) {
            assertSame(first, t)
        }
        try {
            assertEquals(1, calls)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun non_transient_failures_are_rethrown_without_retry_or_sleep() {
        for (fault in listOf<Throwable>(IllegalArgumentException("bad"), OutOfMemoryError())) {
            var calls = 0
            try {
                retrying(3, backoff, sleeper, { _, _ -> }) {
                    calls++
                    throw fault
                }
                throw AssertionError("expected throw")
            } catch (t: Throwable) {
                assertSame(fault, t)
            }
            assertEquals(1, calls)
        }
        assertTrue(sleeps.isEmpty())
    }
}
