package com.dylenq.eq.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StateStoreTest {
    private lateinit var dir: File
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        dir = File(ctx.cacheDir, "ss-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun baks() = dir.listFiles { f -> f.name.endsWith(".bak") }!!.toList()

    @Test
    fun save_then_open_returns_an_equal_snapshot() {
        val store = StateStore(dir)
        val c = EqCore()
        c.setMode(EqMode.PARAMETRIC)
        c.setBand(0u, Band(FilterKind.PEAK, 1234f, 4.5f, 2f, true))
        c.setPreamp(-3f)
        c.setBypass(true)
        c.setGraphicGain(2u, 5f)
        store.save(c)
        val (opened, notice) = store.open()
        assertNull(notice)
        val a = c.snapshot()
        val b = opened.snapshot()
        assertEquals(a.mode, b.mode)
        assertEquals(a.bands, b.bands)
        assertEquals(a.graphicGainsDb, b.graphicGainsDb)
        assertEquals(a.preampDb, b.preampDb, 0f)
        assertEquals(a.limiterEnabled, b.limiterEnabled)
        assertEquals(a.bypass, b.bypass)
        assertEquals(a.frameDurationMs, b.frameDurationMs, 0f)
        assertTrue(b.bypass)
    }

    @Test
    fun missing_file_gives_defaults_and_no_notice() {
        val (c, notice) = StateStore(dir).open()
        assertNull(notice)
        assertEquals(EqCore().snapshot().mode, c.snapshot().mode)
        assertTrue(baks().isEmpty())
    }

    @Test
    fun future_version_is_backed_up_byte_for_byte_and_never_overwritten() {
        val store = StateStore(dir)
        val bytes = "{\"version\":99}".toByteArray()
        store.file.writeBytes(bytes)
        val (c, notice) = store.open()
        assertEquals(StoreNotice.NewerVersion(99u), notice)
        val bak = baks().single()
        assertTrue(bak.name.startsWith("eq-state.json.v99-"))
        assertArrayEquals(bytes, bak.readBytes())
        assertFalse("live file moved away", store.file.exists())

        store.save(c)
        assertTrue(store.file.readText().contains("\"version\": 1"))
        assertArrayEquals("backup untouched by the later save", bytes, bak.readBytes())
    }

    @Test
    fun garbage_text_goes_to_a_corrupt_backup() {
        val store = StateStore(dir)
        store.file.writeText("this is not json")
        val (_, notice) = store.open()
        assertEquals(StoreNotice.Damaged, notice)
        assertTrue(baks().single().name.startsWith("eq-state.json.corrupt-"))
    }

    // Process-kill hazards at restart: open() must never throw, whatever prefix of a state file is on
    // disk, and a leftover half-written .tmp from an interrupted save must neither be read nor block a save.
    @Test
    fun open_never_throws_for_any_truncation_of_a_saved_file() {
        val store = StateStore(dir)
        val c = EqCore()
        c.setMode(EqMode.PARAMETRIC)
        c.setGraphicGain(2u, 5f)
        store.save(c)
        val full = store.file.readBytes()
        val cuts = (0 until full.size step 7).toList() + (full.size - 1)
        for (n in cuts) {
            store.file.writeBytes(full.copyOf(n))
            val (core, notice) = store.open() // must not throw
            if (notice == null) core.snapshot() else assertEquals(StoreNotice.Damaged, notice)
            assertFalse("live file moved aside or replaced for n=$n", notice != null && store.file.exists())
        }
    }

    @Test
    fun leftover_tmp_from_an_interrupted_save_is_ignored_and_overwritten() {
        val store = StateStore(dir)
        val c = EqCore()
        c.setGraphicGain(1u, 3f)
        store.save(c)
        val tmp = File(dir, "eq-state.json.tmp")
        tmp.writeText("{partial")
        val (opened, notice) = store.open()
        assertNull(notice)
        assertEquals(c.snapshot().graphicGainsDb, opened.snapshot().graphicGainsDb)
        c.setGraphicGain(1u, -3f)
        store.save(c)
        assertEquals(-3f, store.open().first.snapshot().graphicGainsDb[1], 0f)
    }

    @Test
    fun a_directory_at_the_state_path_is_backed_up() {
        val store = StateStore(dir)
        assertTrue(store.file.mkdir())
        val (_, notice) = store.open()
        assertEquals(StoreNotice.Damaged, notice)
        assertEquals(1, baks().size)
        assertFalse(store.file.exists())
    }

    @Test
    fun only_the_newest_three_backups_survive_five_failures() {
        val store = StateStore(dir)
        val kept = ArrayList<String>()
        for (i in 1..5) {
            store.file.writeText("garbage $i")
            store.open()
            Thread.sleep(2)
        }
        val left = baks()
        assertEquals(3, left.size)
        // the newest three hold garbage 3, 4 and 5
        val texts = left.map { it.readText() }.sorted()
        assertEquals(listOf("garbage 3", "garbage 4", "garbage 5"), texts)
        kept.clear()
    }

    @Test
    fun twenty_marks_in_200_ms_write_at_most_twice_and_state_lands_on_disk() {
        val writes = AtomicInteger()
        val rt = EqRuntime(com.dylenq.eq.testsupport.FakeEffectFactory(), dir, SettingsStore(null)) { core, path ->
            writes.incrementAndGet()
            core.save(path)
        }
        try {
            for (i in 0 until 20) {
                rt.controller.edit { it.setPreamp(i * 0.1f) }
                Thread.sleep(10)
            }
            val end = System.currentTimeMillis() + 5000
            while (writes.get() == 0 && System.currentTimeMillis() < end) Thread.sleep(25)
            Thread.sleep(1000) // let any further (unwanted) write show up
            assertTrue("writes=${writes.get()}", writes.get() in 1..2)
        } finally {
            rt.shutdown()
        }
        val restored = StateStore(dir).open().first
        assertEquals(1.9f, restored.snapshot().preampDb, 0.001f)
    }

    @Test
    fun shutdown_flushes_a_pending_write() {
        val writes = AtomicInteger()
        val rt = EqRuntime(com.dylenq.eq.testsupport.FakeEffectFactory(), dir, SettingsStore(null)) { core, path ->
            writes.incrementAndGet()
            core.save(path)
        }
        rt.controller.edit { it.setPreamp(-6f) }
        rt.shutdown() // well inside the 500 ms debounce
        assertEquals(1, writes.get())
        assertEquals(-6f, StateStore(dir).open().first.snapshot().preampDb, 0.001f)
    }

    @Test
    fun a_new_runtime_restores_saved_state_and_publishes_a_notice_for_a_set_aside_file() {
        val rt = EqRuntime(com.dylenq.eq.testsupport.FakeEffectFactory(), dir)
        rt.controller.edit { it.setPreamp(-4f) }
        rt.shutdown()
        val rt2 = EqRuntime(com.dylenq.eq.testsupport.FakeEffectFactory(), dir)
        try {
            assertEquals(-4f, rt2.controller.snapshot().preampDb, 0.001f)
            assertNull(rt2.status.value.notice)
        } finally {
            rt2.shutdown()
        }
        File(dir, StateStore.FILE_NAME).writeText("{\"version\":99}")
        val rt3 = EqRuntime(com.dylenq.eq.testsupport.FakeEffectFactory(), dir)
        try {
            assertTrue(rt3.status.value.notice!!.contains("newer version"))
        } finally {
            rt3.shutdown()
        }
    }

    @Test
    fun settings_default_off_and_persist_across_instances() {
        val name = "eq_settings_ss_${System.nanoTime()}"
        try {
            val s = SettingsStore(ctx, name)
            assertFalse(s.globalMode)
            assertFalse("start on boot defaults to OFF (L-05)", s.startOnBoot)
            s.globalMode = true
            s.startOnBoot = true
            val s2 = SettingsStore(ctx, name)
            assertTrue(s2.globalMode)
            assertTrue(s2.startOnBoot)
        } finally {
            ctx.deleteSharedPreferences(name)
        }
    }
}
