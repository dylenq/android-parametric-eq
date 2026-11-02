package com.dylenq.eq.service

/**
 * Turns successive scans of the session table into opens and closes. Pure: no Android, no clock;
 * the caller decides when to scan. A session it opened closes after being missing from
 * [MISSES_TO_CLOSE] scans in a row, so one scan that misses a row does not flap the effect.
 */
class PlayerTracker {
    data class Delta(val opens: List<TrackedRow>, val closes: List<Int>)

    /** Sessions this tracker opened, with how many scans in a row they have been missing. */
    private val opened = LinkedHashMap<Int, Int>()

    fun scan(rows: List<TrackedRow>): Delta {
        val seen = LinkedHashMap<Int, TrackedRow>()
        for (r in rows) seen.putIfAbsent(r.session, r)
        val opens = seen.values.filter { it.session !in opened }
        val closes = ArrayList<Int>()
        val it = opened.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key in seen) {
                e.setValue(0)
            } else {
                e.setValue(e.value + 1)
                if (e.value >= MISSES_TO_CLOSE) {
                    closes.add(e.key)
                    it.remove()
                }
            }
        }
        for (o in opens) opened[o.session] = 0
        return Delta(opens, closes)
    }

    /** Forgets all pending state and returns closes for everything this tracker opened. */
    fun reset(): List<Int> {
        val all = opened.keys.toList()
        opened.clear()
        return all
    }

    companion object {
        const val MISSES_TO_CLOSE = 2
    }
}
