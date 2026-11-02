package com.dylenq.eq.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EngineParams
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.FakeEffectFactory
import com.dylenq.eq.testsupport.RuntimeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Per-app rules at the session level, seen through the parameters each fake handle was given. */
@RunWith(AndroidJUnit4::class)
class PerAppTest {
    @get:Rule
    val rt = RuntimeRule()

    private val fake get() = rt.factoryAs<FakeEffectFactory>()
    private val ctl get() = rt.runtime.controller

    private fun poll(ms: Long = 3000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun open(session: Int, pkg: String) = rt.runtime.post(SessionIntent.Event.Open(session, pkg))
    private fun close(session: Int) = rt.runtime.post(SessionIntent.Event.Close(session, "com.x"))

    private fun lastParams(session: Int): EngineParams? = fake.creates().lastOrNull { it.first == session }?.second

    private fun gainsOf(session: Int): List<Float> = fake.handle(session)!!.let { h ->
        lastParams(session)!!.gainsDb.indices.map { h.readBandGain(it) }
    }

    /** Saves a preset whose first band sits at [gain], then puts the live EQ back to flat. */
    private fun makePreset(name: String, gain: Float): String {
        ctl.edit {
            it.setMode(EqMode.PARAMETRIC)
            it.setBand(0u, Band(FilterKind.PEAK, 250f, gain, 1f, true))
        }
        val key = ctl.savePreset(name)
        ctl.resetBands()
        return key
    }

    private fun appRule(pkg: String) = rt.runtime.status.value.apps.firstOrNull { it.pkg == pkg }

    @Test
    fun two_packages_two_configs_end_to_end() {
        open(1, "com.a")
        open(2, "com.b")
        assertTrue(poll { fake.handle(1) != null && fake.handle(2) != null })
        val key = makePreset("Warm", 9f)
        assertEquals(null, rt.runtime.setAppPreset("com.a", key))
        assertTrue("A recreated on the preset", poll { fake.callCount(1, "create") == 2 })

        val live = gainsOf(2)
        val a = gainsOf(1)
        assertNotEquals("A carries the preset curve", live, a)
        assertEquals("B stays on the live curve", lastParams(2)!!.gainsDb, live)
        assertEquals(1, fake.callCount(2, "create"))
        assertEquals(1, fake.callCount(1, "release"))

        // A live edit reaches B and not A.
        val aCalls = fake.callCount(1, "setBand")
        val bCalls = fake.callCount(2, "setBand")
        ctl.edit { it.setBand(1u, Band(FilterKind.PEAK, 1000f, 7f, 1f, true)) }
        assertTrue(poll { fake.callCount(2, "setBand") > bCalls })
        Thread.sleep(200)
        assertEquals("the preset session ignores live edits", aCalls, fake.callCount(1, "setBand"))
        assertEquals(1, fake.callCount(1, "create") - 1)
    }

    @Test
    fun rename_keeps_the_preset_and_delete_returns_to_live() {
        open(1, "com.a")
        open(2, "com.b")
        assertTrue(poll { fake.handle(1) != null && fake.handle(2) != null })
        val key = makePreset("Warm", 9f)
        rt.runtime.setAppPreset("com.a", key)
        assertTrue(poll { fake.callCount(1, "create") == 2 })
        val presetGains = lastParams(1)!!.gainsDb

        val newKey = ctl.renamePreset(key, "Warmer")
        assertTrue(poll { appRule("com.a")?.presetKey == newKey })
        assertTrue(poll { fake.callCount(1, "create") >= 2 })
        assertEquals(presetGains, lastParams(1)!!.gainsDb)

        val before = fake.callCount(1, "create")
        ctl.deletePreset(newKey)
        assertTrue(poll { appRule("com.a")?.presetKey == null })
        assertTrue("A is recreated on the live gains", poll { fake.callCount(1, "create") > before })
        assertEquals(lastParams(2)!!.gainsDb, lastParams(1)!!.gainsDb)
    }

    @Test
    fun changing_the_preset_key_recreates_and_a_gain_change_of_the_same_key_diffs() {
        open(1, "com.a")
        assertTrue(poll { fake.handle(1) != null })
        val k1 = makePreset("One", 9f)
        val k2 = makePreset("Two", -9f)
        rt.runtime.setAppPreset("com.a", k1)
        assertTrue(poll { fake.callCount(1, "create") == 2 })
        rt.runtime.setAppPreset("com.a", k2)
        assertTrue("a key change goes through recreate", poll { fake.callCount(1, "create") == 3 })

        // Overwriting the same preset with other gains reaches the session by diff, not recreate.
        val sets = fake.callCount(1, "setBand")
        ctl.edit {
            it.setBand(0u, Band(FilterKind.PEAK, 250f, 4f, 1f, true))
        }
        ctl.savePreset("Two")
        ctl.resetBands()
        assertTrue(poll { fake.callCount(1, "setBand") > sets })
        assertEquals(3, fake.callCount(1, "create"))
    }

    @Test
    fun unknown_preset_key_is_refused_and_a_stale_key_never_crashes() {
        open(1, "com.a")
        assertTrue(poll { fake.handle(1) != null })
        val err = rt.runtime.setAppPreset("com.a", "user:nothing")
        assertTrue(err != null)
        assertEquals(null, appRule("com.a")?.presetKey)
    }

    private fun stateOf(session: Int) = rt.runtime.status.value.sessions.firstOrNull { it.id == session }?.state

    @Test
    fun off_releases_at_once_new_sessions_are_not_attached_and_on_attaches_again() {
        open(1, "com.a")
        assertTrue(poll { fake.handle(1) != null })
        rt.runtime.setAppEnabled("com.a", false)
        assertTrue(poll { fake.callCount(1, "release") == 1 })
        assertTrue(poll { stateOf(1) == SessionState.DISABLED })
        assertEquals(SessionState.DISABLED, appRule("com.a")?.state)
        assertEquals(false, appRule("com.a")?.enabled)

        open(2, "com.a")
        assertTrue(poll { stateOf(2) == SessionState.DISABLED })
        assertEquals("a session from an off app creates nothing", 0, fake.callCount(2, "create"))

        rt.runtime.setAppEnabled("com.a", true)
        assertTrue(poll { fake.callCount(1, "create") == 2 && fake.callCount(2, "create") == 1 })
        assertTrue(poll { stateOf(1) == SessionState.OK && stateOf(2) == SessionState.OK })
    }

    @Test
    fun disabled_sessions_count_toward_the_cap() {
        val factory = FakeEffectFactory()
        val mgr = EffectManager(
            factory, { com.dylenq.eq.core.EqCore().engineParams(48000u, 85.3f) },
            maxSessions = 2, sleeper = Sleeper { }, isEnabled = { it != "off" },
        )
        mgr.onOpen(1, "off")
        mgr.onOpen(2, "on")
        mgr.onOpen(3, "on")
        assertEquals(2, mgr.sessionCount())
        assertEquals("the least recently seen (the disabled one) is dropped", listOf(2, 3), mgr.sessionInfos().map { it.id })
        assertEquals(0, factory.callCount(2, "release"))
    }

    @Test
    fun global_mode_ignores_the_rules_and_they_apply_again_afterwards() {
        open(1, "com.a")
        assertTrue(poll { fake.handle(1) != null })
        rt.runtime.setGlobalMode(true)
        assertTrue(poll { rt.runtime.status.value.globalMode })
        assertEquals(1, fake.callCount(0, "create"))
        rt.runtime.setAppEnabled("com.a", false)
        Thread.sleep(300)
        assertEquals("per-app off has no effect in global mode", 0, fake.callCount(0, "release"))
        assertEquals(SessionState.SUPPRESSED, stateOf(1))

        rt.runtime.setGlobalMode(false)
        assertTrue(poll { !rt.runtime.status.value.globalMode })
        assertTrue(poll { stateOf(1) == SessionState.DISABLED })
        assertEquals("no new effect for the off app", 1, fake.callCount(1, "create"))
    }

    @Test
    fun turning_on_with_a_failing_create_ends_failed() {
        open(1, "com.a")
        assertTrue(poll { fake.handle(1) != null })
        rt.runtime.setAppEnabled("com.a", false)
        assertTrue(poll { stateOf(1) == SessionState.DISABLED })
        fake.failSession(1, IllegalStateException("boom"))
        rt.runtime.setAppEnabled("com.a", true)
        assertTrue(poll { stateOf(1) == SessionState.FAILED })
    }

    @Test
    fun status_apps_lists_rules_with_what_is_playing() {
        assertTrue(rt.runtime.status.value.apps.isEmpty())
        open(1, "com.a")
        assertTrue(poll { appRule("com.a") != null })
        val a = appRule("com.a")!!
        assertEquals(AppEntry("com.a", true, null, "com.a", true, SessionState.OK), a)
        close(1)
        assertTrue(poll { appRule("com.a")?.playingNow == false })
        assertEquals(null, appRule("com.a")?.state)
    }

    @Test
    fun spoofed_packages_through_the_runtime_never_erase_a_user_rule() {
        open(1, "com.keep")
        assertTrue(poll { fake.handle(1) != null })
        rt.runtime.setAppEnabled("com.keep", false)
        for (i in 0 until 150) rt.runtime.post(SessionIntent.Event.Open(100 + i, "com.spam.p$i"))
        assertTrue(poll { rt.runtime.status.value.apps.size == 64 })
        Thread.sleep(300)
        assertTrue(rt.runtime.status.value.apps.size <= 64)
        assertEquals(false, appRule("com.keep")?.enabled)
    }

    @Test
    fun rules_survive_process_death() {
        open(1, "com.a")
        open(2, "com.b")
        assertTrue(poll { fake.handle(1) != null && fake.handle(2) != null })
        val key = makePreset("Warm", 9f)
        rt.runtime.setAppEnabled("com.a", false)
        assertEquals(null, rt.runtime.setAppPreset("com.b", key))
        assertTrue(poll { appRule("com.b")?.presetKey == key })
        rt.runtime.flush()
        rt.runtime.shutdown()

        val fake2 = FakeEffectFactory()
        val rt2 = com.dylenq.eq.EqRuntime(fake2, rt.dir, rt.settings)
        try {
            assertTrue(poll { rt2.status.value.apps.size == 2 })
            val apps = rt2.status.value.apps.associateBy { it.pkg }
            assertEquals(false, apps["com.a"]!!.enabled)
            assertEquals(key, apps["com.b"]!!.presetKey)
            assertEquals(false, apps["com.b"]!!.playingNow)

            rt2.post(SessionIntent.Event.Open(1, "com.a"))
            rt2.post(SessionIntent.Event.Open(2, "com.b"))
            assertTrue(poll { rt2.status.value.sessions.size == 2 })
            assertEquals(SessionState.DISABLED, rt2.status.value.sessions.first { it.id == 1 }.state)
            assertEquals(0, fake2.callCount(1, "create"))
            val live = rt2.controller.engineParams(48000u, 85.3f).gainsDb
            assertNotEquals(live, fake2.creates().last { it.first == 2 }.second.gainsDb)
        } finally {
            rt2.shutdown()
        }
    }
}
