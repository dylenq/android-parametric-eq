package com.dylenq.eq.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServiceStartTest {
    private val order = ArrayList<String>()
    private val warnings = ArrayList<String>()
    private val errors = ArrayList<String>()

    private fun run(first: () -> Unit, second: () -> Unit): Boolean =
        runStartSteps(
            { order.add("first"); first() },
            { order.add("second"); second() },
            { warnings.add(it) },
            { errors.add(it) },
        )

    @Test
    fun both_steps_run_in_order_and_the_service_is_running() {
        assertTrue(run({}, {}))
        assertEquals(listOf("first", "second"), order)
        assertTrue(warnings.isEmpty() && errors.isEmpty())
    }

    @Test
    fun an_exception_from_the_first_step_stops_there_and_warns() {
        assertFalse(run({ throw IllegalStateException("not allowed") }, {}))
        assertEquals(listOf("first"), order)
        assertEquals(listOf("start failed: java.lang.IllegalStateException: not allowed"), warnings)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun an_exception_from_the_second_step_is_not_running() {
        assertFalse(run({}, { throw SecurityException("refused") }))
        assertEquals(listOf("first", "second"), order)
        assertEquals(listOf("start failed: java.lang.SecurityException: refused"), warnings)
    }

    @Test
    fun a_linkage_error_from_either_step_is_not_running_and_logged_as_an_error() {
        assertFalse(run({ throw UnsatisfiedLinkError("no libeq_core") }, {}))
        assertEquals(listOf("first"), order)
        assertEquals(listOf("core library failed to load: no libeq_core"), errors)

        order.clear(); errors.clear()
        assertFalse(run({}, { throw NoClassDefFoundError("Lcom/dylenq/eq/core/EqCore;") }))
        assertEquals(listOf("first", "second"), order)
        assertEquals(listOf("core library failed to load: Lcom/dylenq/eq/core/EqCore;"), errors)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun an_error_that_is_not_a_linkage_error_propagates_from_either_step() {
        for (where in 0..1) {
            try {
                run({ if (where == 0) throw OutOfMemoryError("x") }, { if (where == 1) throw OutOfMemoryError("x") })
                fail("expected the error to propagate from step $where")
            } catch (e: OutOfMemoryError) {
                // expected
            }
            order.clear()
        }
        assertTrue(warnings.isEmpty() && errors.isEmpty())
    }
}
