package com.dylenq.eq.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BandIdSourceTest {
    private val source = BandIdSource(seed = 100L)

    @Test
    fun fresh_gives_distinct_ids() {
        val ids = source.fresh(7)
        assertEquals(7, ids.size)
        assertEquals(7, ids.toSet().size)
    }

    @Test
    fun fresh_zero_is_empty_and_negative_does_not_throw() {
        assertTrue(source.fresh(0).isEmpty())
        assertTrue(source.fresh(-3).isEmpty())
    }

    @Test
    fun appended_adds_exactly_one_new_id_at_the_end() {
        val ids = source.fresh(3)
        val more = source.appended(ids)
        assertEquals(4, more.size)
        assertEquals(ids, more.take(3))
        assertTrue(more.last() !in ids)
    }

    @Test
    fun appended_to_an_empty_list_gives_one_id() {
        assertEquals(1, source.appended(emptyList()).size)
    }

    @Test
    fun removedAt_drops_that_position_and_keeps_the_order() {
        val ids = source.fresh(5)
        assertEquals(listOf(ids[0], ids[1], ids[3], ids[4]), source.removedAt(ids, 2))
        assertEquals(ids.drop(1), source.removedAt(ids, 0))
        assertEquals(ids.dropLast(1), source.removedAt(ids, 4))
    }

    @Test
    fun removedAt_an_index_out_of_range_returns_the_list_unchanged() {
        val ids = source.fresh(3)
        assertSame(ids, source.removedAt(ids, 3))
        assertSame(ids, source.removedAt(ids, -1))
        assertTrue(source.removedAt(emptyList(), 0).isEmpty())
    }

    @Test
    fun reconciled_returns_an_equal_size_list_untouched() {
        val ids = source.fresh(4)
        assertSame(ids, source.reconciled(ids, 4))
    }

    @Test
    fun reconciled_gives_a_new_list_of_the_right_size_when_the_sizes_differ() {
        val ids = source.fresh(4)
        val bigger = source.reconciled(ids, 6)
        assertEquals(6, bigger.size)
        assertTrue(bigger.none { it in ids })
        val smaller = source.reconciled(ids, 2)
        assertEquals(2, smaller.size)
        assertTrue(smaller.none { it in ids })
        assertTrue(source.reconciled(ids, 0).isEmpty())
    }

    @Test
    fun ids_stay_distinct_across_every_method() {
        val all = ArrayList<Long>()
        var ids = source.fresh(3).also { all += it }
        ids = source.appended(ids).also { all += it.last() }
        ids = source.removedAt(ids, 1)
        ids = source.appended(ids).also { all += it.last() }
        val regenerated = source.reconciled(ids, 9).also { all += it }
        all += source.fresh(2)
        assertEquals(9, regenerated.size)
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun sources_started_at_different_times_do_not_share_ids() {
        // Ids that restarted at the same value in a new process could meet draft state saved by the old one.
        val first = BandIdSource().fresh(16)
        Thread.sleep(2)
        val second = BandIdSource().fresh(16)
        assertTrue(first.none { it in second })
    }
}
