package com.dylenq.eq.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.testsupport.FakeEffectFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Manager behaviour against the recording fake. The manager is driven from the test thread (it is not shared). */
@RunWith(AndroidJUnit4::class)
class EffectManagerTest {
    private lateinit var base: EngineParams
    private lateinit var current: EngineParams
    private lateinit var fake: FakeEffectFactory
    private lateinit var mgr: EffectManager
    private var changes = 0
    private val sleeps = ArrayList<Long>()

    @Before
    fun setUp() {
        base = EqCore().engineParams(48000u, 85.3f)
        current = base
        fake = FakeEffectFactory()
        changes = 0
        sleeps.clear()
        mgr = EffectManager(fake, { current }, { changes++ }, sleeper = Sleeper { sleeps.add(it) })
    }

    private fun withGain(p: EngineParams, band: Int, delta: Float): EngineParams {
        val g = p.gainsDb.toMutableList()
        g[band] = g[band] + delta
        return p.copy(gainsDb = g)
    }

    private fun mutators(session: Int): List<String> =
        fake.calls().filter { it.session == session && it.method != "create" && it.method != "release" }.map { it.method }

    @Test
    fun open_twice_creates_once_and_refreshes() {
        mgr.onOpen(1, "com.a.b")
        mgr.onOpen(1, "com.a.b")
        assertEquals(1, fake.callCount(1, "create"))
        assertEquals(1, mgr.sessionCount())
    }

    @Test
    fun close_for_unknown_session_does_nothing() {
        mgr.onClose(77)
        assertTrue(fake.calls().isEmpty())
        assertEquals(0, mgr.sessionCount())
    }

    @Test
    fun close_releases_the_handle() {
        mgr.onOpen(1, "com.a.b")
        val h = fake.handle(1)!!
        mgr.onClose(1)
        assertTrue(h.released)
        assertNull(mgr.handleFor(1))
    }

    @Test
    fun default_cap_is_16_and_the_17th_evicts_the_least_recently_seen() {
        for (s in 1..16) mgr.onOpen(s, "com.a.b")
        assertEquals(16, mgr.sessionCount())
        val first = fake.handle(1)!!
        mgr.onOpen(17, "com.a.b")
        assertEquals(16, mgr.sessionCount())
        assertTrue("session 1 was the oldest", first.released)
        assertNull(mgr.handleFor(1))
        assertNotNull(mgr.handleFor(17))
        assertFalse(fake.handle(2)!!.released)
    }

    @Test
    fun reopening_an_old_id_protects_it_from_eviction() {
        for (s in 1..16) mgr.onOpen(s, "com.a.b")
        mgr.onOpen(1, "com.a.b")
        mgr.onOpen(17, "com.a.b")
        assertNotNull("session 1 refreshed", mgr.handleFor(1))
        assertNull("session 2 became the oldest", mgr.handleFor(2))
        assertTrue(fake.handle(2)!!.released)
        assertEquals(16, mgr.sessionCount())
    }

    @Test
    fun only_the_changed_band_is_sent() {
        mgr.onOpen(1, "com.a.b")
        current = withGain(base, 5, 0.5f)
        mgr.applyLatest()
        assertEquals(listOf("setBand"), mutators(1))
        val c = fake.calls().last { it.method == "setBand" }
        assertEquals(5, c.args[0])
        assertEquals(current.cutoffsHz[5], c.args[1] as Float, 0f)
        assertEquals(current.gainsDb[5], c.args[2] as Float, 0f)
    }

    @Test
    fun a_change_under_the_threshold_sends_nothing() {
        mgr.onOpen(1, "com.a.b")
        current = withGain(base, 5, 0.005f)
        mgr.applyLatest()
        assertTrue(mutators(1).isEmpty())
    }

    @Test
    fun preamp_only_change_sends_one_input_gain_and_no_bands() {
        mgr.onOpen(1, "com.a.b")
        current = base.copy(inputGainDb = base.inputGainDb - 3f)
        mgr.applyLatest()
        assertEquals(listOf("setInputGain"), mutators(1))
        assertEquals(current.inputGainDb, fake.handle(1)!!.readInputGain(), 0f)
    }

    @Test
    fun limiter_change_sends_only_the_limiter() {
        mgr.onOpen(1, "com.a.b")
        current = base.copy(limiter = base.limiter.copy(thresholdDb = base.limiter.thresholdDb - 2f))
        mgr.applyLatest()
        assertEquals(listOf("setLimiter"), mutators(1))
        assertEquals(current.limiter.thresholdDb, fake.handle(1)!!.readLimiterThresholdDb(), 0f)

        current = current.copy(limiterEnabled = !current.limiterEnabled)
        mgr.applyLatest()
        assertEquals(listOf("setLimiter", "setLimiter"), mutators(1))
    }

    private fun assertRecreatedAll(change: (EngineParams) -> EngineParams) {
        mgr.onOpen(1, "com.a.b")
        mgr.onOpen(2, "com.c.d")
        val h1 = fake.handle(1)!!
        val h2 = fake.handle(2)!!
        current = change(base)
        mgr.applyLatest()
        assertTrue(h1.released)
        assertTrue(h2.released)
        assertEquals(2, fake.callCount(1, "create"))
        assertEquals(2, fake.callCount(2, "create"))
        val last = fake.creates().last { it.first == 1 }.second
        assertEquals(current.layoutId, last.layoutId)
        assertEquals(current.sampleRate, last.sampleRate)
        assertEquals(current.frameDurationMs, last.frameDurationMs, 0f)
        assertEquals(2, mgr.sessionCount())
        // Release comes before the second create on the same session.
        val order = fake.calls().filter { it.session == 1 }.map { it.method }
        assertEquals(listOf("create", "release", "create"), order)
    }

    @Test fun layout_change_releases_and_recreates_every_effect() =
        assertRecreatedAll { it.copy(layoutId = it.layoutId + 1u) }

    @Test fun sample_rate_change_releases_and_recreates_every_effect() =
        assertRecreatedAll { it.copy(sampleRate = 44100u) }

    @Test fun frame_duration_change_releases_and_recreates_every_effect() =
        assertRecreatedAll { it.copy(frameDurationMs = 170.6f) }

    @Test
    fun bypass_disables_without_band_pushes_and_catches_up_on_reenable() {
        mgr.onOpen(1, "com.a.b")
        current = base.copy(bypass = true)
        mgr.applyLatest()
        assertEquals(listOf("setEnabled"), mutators(1))
        assertFalse(fake.handle(1)!!.readEnabled())

        current = withGain(base.copy(bypass = true), 5, 1.0f)
        mgr.applyLatest()
        assertEquals("no pushes while bypassed", listOf("setEnabled"), mutators(1))

        current = current.copy(bypass = false)
        mgr.applyLatest()
        assertEquals(listOf("setEnabled", "setBand", "setEnabled"), mutators(1))
        assertTrue(fake.handle(1)!!.readEnabled())
        assertEquals(current.gainsDb[5], fake.handle(1)!!.readBandGain(5), 0f)
    }

    @Test
    fun a_failing_handle_call_recreates_once_and_the_session_stays() {
        fake.failAfter(0)
        mgr.onOpen(1, "com.a.b")
        val first = fake.handle(1)!!
        current = withGain(base, 5, 1f)
        mgr.applyLatest()
        assertTrue(first.released)
        assertEquals(2, fake.callCount(1, "create"))
        assertEquals(1, mgr.sessionCount())
        assertEquals(SessionState.OK, mgr.sessionInfos().single().state)
        assertEquals(current.gainsDb[5], fake.handle(1)!!.readBandGain(5), 0f)
    }

    @Test
    fun a_failed_recreate_drops_the_session_and_lists_it_failed() {
        fake.failAfter(0)
        mgr.onOpen(1, "com.a.b")
        fake.failSession(1, IllegalStateException("gone"))
        current = withGain(base, 5, 1f)
        mgr.applyLatest()
        assertEquals(0, mgr.sessionCount())
        val info = mgr.sessionInfos().single()
        assertEquals(1, info.id)
        assertEquals("com.a.b", info.pkg)
        assertEquals(SessionState.FAILED, info.state)
        assertTrue(changes > 0)
    }

    @Test
    fun a_session_without_control_is_tracked_as_no_control_and_not_written() {
        fake.controlLost(1)
        mgr.onOpen(1, "com.a.b")
        assertEquals(1, mgr.sessionCount())
        assertEquals(SessionState.NO_CONTROL, mgr.sessionInfos().single().state)
        current = withGain(base, 5, 1f)
        mgr.applyLatest()
        assertTrue(mutators(1).isEmpty())
        assertEquals(1, fake.callCount(1, "create"))
    }

    @Test
    fun a_throwable_from_the_factory_never_escapes() {
        fake.failNextCreates(3, Error("boom"))
        mgr.onOpen(1, "com.a.b")
        assertEquals(0, mgr.sessionCount())
        assertEquals(SessionState.FAILED, mgr.sessionInfos().single().state)

        mgr.onOpen(2, "com.a.b")
        assertEquals(1, mgr.sessionCount())

        // A throwing params supplier must not escape either.
        val bad = EffectManager(fake, { throw IllegalStateException("no params") })
        bad.onOpen(3, "com.a.b")
        bad.applyLatest()
        bad.onClose(3)
        bad.releaseAll()
        assertEquals(0, bad.sessionCount())
    }

    @Test
    fun a_later_successful_open_clears_the_failed_entry() {
        fake.failNextCreates(3, RuntimeException("once"))
        mgr.onOpen(1, "com.a.b")
        assertEquals(SessionState.FAILED, mgr.sessionInfos().single().state)
        mgr.onOpen(1, "com.a.b")
        assertEquals(SessionState.OK, mgr.sessionInfos().single().state)
        assertEquals(1, mgr.sessionCount())
    }

    @Test
    fun create_retries_transient_failure_and_session_is_ok() {
        fake.failNextCreates(2, RuntimeException("transient"))
        mgr.onOpen(1, "com.a.b")
        assertEquals(SessionState.OK, mgr.sessionInfos().single().state)
        assertEquals(3, fake.callCount(1, "create"))
        assertEquals(listOf(50L, 100L), sleeps)
    }

    @Test
    fun create_fails_after_three_attempts() {
        val m = EffectManager(fake, { current }, {}, sleeper = Sleeper { sleeps.add(it) })
        fake.failNextCreates(3, RuntimeException("permanent"))
        m.onOpen(1, "com.a.b")
        assertEquals(3, fake.callCount(1, "create"))
        assertEquals(listOf(50L, 100L), sleeps)
        assertEquals(SessionState.FAILED, m.sessionInfos().single().state)
        assertEquals(0, m.sessionCount())
    }

    @Test
    fun no_failed_state_is_observable_before_the_last_attempt() {
        val seen = ArrayList<SessionState>()
        lateinit var m: EffectManager
        m = EffectManager(fake, { current }, {}, sleeper = Sleeper { _ ->
            seen.addAll(m.sessionInfos().map { it.state })
        })
        fake.failNextCreates(2, RuntimeException("transient"))
        m.onOpen(1, "com.a.b")
        assertTrue("no session visible mid-retry: $seen", seen.isEmpty())
        assertEquals(SessionState.OK, m.sessionInfos().single().state)
    }

    @Test
    fun no_control_is_not_retried() {
        fake.controlLost(1)
        mgr.onOpen(1, "com.a.b")
        assertEquals(SessionState.NO_CONTROL, mgr.sessionInfos().single().state)
        assertEquals(1, fake.callCount(1, "create"))
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun recreate_uses_retry() {
        mgr.onOpen(1, "com.a.b")
        fake.failNextCreates(2, RuntimeException("transient"))
        current = base.copy(sampleRate = 44100u)
        mgr.applyLatest()
        assertEquals(SessionState.OK, mgr.sessionInfos().single().state)
        assertEquals(4, fake.callCount(1, "create"))
        assertEquals(listOf(50L, 100L), sleeps)
    }

    @Test
    fun global_mode_uses_retry() {
        var rejected: String? = null
        val m = EffectManager(fake, { current }, {}, onGlobalRejected = { rejected = it }, sleeper = Sleeper { sleeps.add(it) })
        fake.failNextCreates(1, RuntimeException("transient"))
        m.setGlobalMode(true)
        assertTrue("rejected: $rejected", m.isGlobalOn())
        assertEquals(2, fake.callCount(EffectManager.GLOBAL_ID, "create"))
        assertEquals(listOf(50L), sleeps)
    }

    @Test
    fun release_all_releases_every_handle() {
        mgr.onOpen(1, "com.a.b")
        mgr.onOpen(2, "com.a.b")
        val h1 = fake.handle(1)!!
        val h2 = fake.handle(2)!!
        mgr.releaseAll()
        assertTrue(h1.released && h2.released)
        assertEquals(0, mgr.sessionCount())
        assertSame(null, mgr.handleFor(1))
    }
}
