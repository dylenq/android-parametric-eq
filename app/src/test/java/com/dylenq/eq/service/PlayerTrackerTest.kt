package com.dylenq.eq.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTrackerTest {
    private fun row(s: Int) = TrackedRow(s, "com.a.b")

    @Test
    fun a_new_session_opens_once() {
        val t = PlayerTracker()
        assertEquals(listOf(row(1)), t.scan(listOf(row(1))).opens)
        assertTrue(t.scan(listOf(row(1))).opens.isEmpty())
    }

    @Test
    fun closes_after_two_scans_without_the_session() {
        val t = PlayerTracker()
        t.scan(listOf(row(1)))
        assertTrue(t.scan(emptyList()).closes.isEmpty())
        assertEquals(listOf(1), t.scan(emptyList()).closes)
        assertTrue(t.scan(emptyList()).closes.isEmpty())
    }

    @Test
    fun reappearing_cancels_the_pending_close() {
        val t = PlayerTracker()
        t.scan(listOf(row(1)))
        t.scan(emptyList())
        val d = t.scan(listOf(row(1)))
        assertTrue(d.opens.isEmpty() && d.closes.isEmpty())
        assertTrue(t.scan(emptyList()).closes.isEmpty())
        assertEquals(listOf(1), t.scan(emptyList()).closes)
    }

    @Test
    fun a_closed_session_can_open_again() {
        val t = PlayerTracker()
        t.scan(listOf(row(1)))
        t.scan(emptyList()); t.scan(emptyList())
        assertEquals(listOf(row(1)), t.scan(listOf(row(1))).opens)
    }

    @Test
    fun duplicate_rows_open_once() {
        assertEquals(1, PlayerTracker().scan(listOf(row(1), row(1))).opens.size)
    }

    @Test
    fun reset_closes_everything_it_opened() {
        val t = PlayerTracker()
        t.scan(listOf(row(1), row(2)))
        t.scan(listOf(row(1)))
        assertEquals(listOf(1, 2), t.reset())
        assertTrue(t.reset().isEmpty())
        assertEquals(listOf(row(1)), t.scan(listOf(row(1))).opens)
    }
}
