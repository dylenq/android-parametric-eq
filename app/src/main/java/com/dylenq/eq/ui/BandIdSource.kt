package com.dylenq.eq.ui

/**
 * Hands out the ids that key the parametric band rows, so a row keeps its own state (an open Kind menu,
 * a field message, a typed draft) when the band at its index changes after a delete, undo or redo.
 *
 * The counter starts at [seed], which is the monotonic clock by default and not zero. Compose saves some
 * row state under the row's key, and a new process that counted from zero again could hand a draft saved
 * by the old process to a different band. A clock-based start keeps ids from repeating across processes,
 * so such state finds no row and is dropped.
 *
 * Ids live only in the UI layer: they are never persisted and never reach the core. The holder calls this
 * under its own lock, so it takes none.
 */
class BandIdSource(seed: Long = System.nanoTime()) {
    private var next = seed

    /** [count] new ids, none of them used before. */
    fun fresh(count: Int): List<Long> = List(count.coerceAtLeast(0)) { next++ }

    /** [ids] with one new id at the end (a band was added). */
    fun appended(ids: List<Long>): List<Long> = ids + next++

    /** [ids] without the one at [index] (a band was removed); unchanged for an index out of range. */
    fun removedAt(ids: List<Long>, index: Int): List<Long> =
        if (index in ids.indices) ids.toMutableList().also { it.removeAt(index) } else ids

    /** [ids] when it has one id per band, otherwise [count] new ones (the two have drifted apart). */
    fun reconciled(ids: List<Long>, count: Int): List<Long> = if (ids.size == count) ids else fresh(count)
}
