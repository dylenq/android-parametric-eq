package com.dylenq.eq.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller with a fake clock and a counting dump: no sleeping, no Android services. */
class TrackingControllerTest {
    private class FakeScheduler : TrackScheduler {
        var now = 0L
        private class Item(val at: Long, val task: () -> Unit) { var live = true }
        private val items = ArrayList<Item>()
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val i = Item(now + delayMs, task)
            items.add(i)
            return { i.live = false }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = items.filter { it.live && it.at <= end }.minByOrNull { it.at } ?: break
                items.remove(next)
                now = next.at
                next.task()
            }
            now = end
        }
        fun pending() = items.count { it.live }
    }

    private class CountingDump(var result: DumpResult) : DumpSource {
        var calls = 0
        override fun read(): DumpResult { calls++; return result }
    }

    private class Sink : TrackingSink {
        val opens = ArrayList<TrackedRow>()
        val closes = ArrayList<Int>()
        var state: TrackingState? = null
        override fun postTracked(opens: List<TrackedRow>, closes: List<Int>) {
            this.opens.addAll(opens); this.closes.addAll(closes)
        }
        override fun setTrackingState(state: TrackingState) { this.state = state }
    }

    private val resolver = object : AppResolver {
        override fun launchable(pkg: String) = true
        override fun packagesForUid(uid: Int) = listOf("com.example.player")
    }
    private val row = SessionRow(77, 1, 10200, null)

    private class Rig(val clock: FakeScheduler, val dump: CountingDump, val sink: Sink, val ctl: TrackingController, var granted: Boolean)

    private fun rig(result: DumpResult = DumpResult.Sessions(listOf(row)), granted: Boolean = true): Rig {
        val clock = FakeScheduler()
        val dump = CountingDump(result)
        val sink = Sink()
        lateinit var r: Rig
        val ctl = TrackingController(clock, dump, { r.granted }, resolver, 10100, sink)
        r = Rig(clock, dump, sink, ctl, granted)
        return r
    }

    @Test fun trackingOffNeverDumps() {
        val r = rig()
        r.ctl.onPlaybackChanged(1)
        r.clock.advance(30_000)
        r.ctl.recheckPermission()
        assertEquals(0, r.dump.calls)
        assertEquals(0, r.clock.pending())
    }

    @Test fun missingPermissionNeverDumps() {
        val r = rig(granted = false)
        r.ctl.setEnabled(true)
        r.ctl.onPlaybackChanged(1)
        r.clock.advance(30_000)
        assertEquals(0, r.dump.calls)
        assertEquals(TrackingState.NeedsPermission, r.sink.state)
    }

    @Test fun changeScansAtThreeDelaysThenPeriodically() {
        val r = rig()
        r.ctl.setEnabled(true)
        r.ctl.onPlaybackChanged(1)
        r.clock.advance(249); assertEquals(0, r.dump.calls)
        r.clock.advance(1); assertEquals(1, r.dump.calls)
        r.clock.advance(750); assertEquals(2, r.dump.calls)
        r.clock.advance(2000); assertEquals(3, r.dump.calls)
        // The 5 s tick started at the change.
        r.clock.advance(2000); assertEquals(4, r.dump.calls)
        r.clock.advance(5000); assertEquals(5, r.dump.calls)
        assertEquals(TrackingState.Working(1), r.sink.state)
        assertEquals(listOf(TrackedRow(77, "com.example.player")), r.sink.opens)
    }

    @Test fun idlePhoneDoesNoWork() {
        val r = rig()
        r.ctl.setEnabled(true)
        r.ctl.onPlaybackChanged(0)
        r.clock.advance(4000)
        val after = r.dump.calls
        assertTrue(after in 1..3)
        r.clock.advance(60_000)
        assertEquals(after, r.dump.calls)
        assertEquals(0, r.clock.pending())
    }

    @Test fun emptyingPlaybackScansOnceMoreThenStops() {
        val r = rig()
        r.ctl.setEnabled(true)
        r.ctl.onPlaybackChanged(1)
        r.clock.advance(20_000)
        r.dump.result = DumpResult.Sessions(emptyList())
        r.ctl.onPlaybackChanged(0)
        r.clock.advance(4000)
        assertEquals(listOf(77), r.sink.closes) // two missed scans closed it
        val calls = r.dump.calls
        r.clock.advance(60_000)
        assertEquals(calls, r.dump.calls)
    }

    @Test fun resultsMapToState() {
        val r = rig(DumpResult.Denied)
        r.ctl.setEnabled(true); r.ctl.onPlaybackChanged(1); r.clock.advance(300)
        assertEquals(TrackingState.NeedsReboot, r.sink.state)
        r.dump.result = DumpResult.Unavailable("why")
        r.clock.advance(1000)
        assertEquals(TrackingState.Unsupported("why"), r.sink.state)
    }

    @Test fun turningOffClosesWhatItOpenedAndCancelsScans() {
        val r = rig()
        r.ctl.setEnabled(true); r.ctl.onPlaybackChanged(1); r.clock.advance(300)
        assertEquals(1, r.sink.opens.size)
        r.ctl.setEnabled(false)
        assertEquals(listOf(77), r.sink.closes)
        assertEquals(TrackingState.Off, r.sink.state)
        val calls = r.dump.calls
        r.clock.advance(60_000)
        assertEquals(calls, r.dump.calls)
        assertEquals(0, r.clock.pending())
    }

    @Test fun revokedPermissionStopsDumpingAtOnce() {
        val r = rig()
        r.ctl.setEnabled(true); r.ctl.onPlaybackChanged(1); r.clock.advance(300)
        val calls = r.dump.calls
        r.granted = false
        r.clock.advance(60_000) // no recheck call: the gate before each dump is enough
        assertEquals(calls, r.dump.calls)
        assertEquals(TrackingState.NeedsPermission, r.sink.state)
    }

    @Test fun grantAfterEnableStartsScanningOnRecheck() {
        val r = rig(granted = false)
        r.ctl.setEnabled(true); r.ctl.onPlaybackChanged(1)
        r.granted = true
        r.ctl.recheckPermission()
        r.clock.advance(300)
        assertEquals(1, r.dump.calls)
        assertEquals(TrackingState.Working(1), r.sink.state)
    }
}
