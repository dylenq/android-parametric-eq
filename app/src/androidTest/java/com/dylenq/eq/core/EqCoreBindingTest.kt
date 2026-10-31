package com.dylenq.eq.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqController
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EqCoreBindingTest {
    private fun peak(freq: Float, gain: Float) = Band(FilterKind.PEAK, freq, gain, 1f, true)

    @Test
    fun version_comes_back_from_rust() {
        EqCore().use { core ->
            val v = core.version()
            assertTrue("version was '$v'", v.isNotEmpty() && v[0].isDigit())
        }
    }

    @Test
    fun band_edit_shows_in_snapshot() {
        EqCore().use { core ->
            core.setBand(0u, peak(1000f, 6f))
            val b = core.snapshot().bands[0]
            assertEquals(FilterKind.PEAK, b.kind)
            assertEquals(1000f, b.freq, 0.001f)
            assertEquals(6f, b.gainDb, 0.001f)
        }
    }

    @Test
    fun engine_params_are_consistent() {
        EqCore().use { core ->
            core.setMode(EqMode.PARAMETRIC)
            core.setBand(0u, peak(1000f, 6f))
            val p = core.engineParams(48000u, 85.3f)
            assertEquals(p.cutoffsHz.size, p.gainsDb.size)
            assertEquals(24000f, p.cutoffsHz.last(), 0.5f)
            assertTrue(p.gainsDb.all { it >= -60f && it <= 30f })

            core.setBand(1u, peak(2000f, 3f))
            assertEquals(p.layoutId, core.engineParams(48000u, 85.3f).layoutId)
            assertNotEquals(p.layoutId, core.engineParams(44100u, 170.6f).layoutId)
        }
    }

    @Test
    fun response_curve_has_three_equal_lists_and_the_peak() {
        EqCore().use { core ->
            core.setMode(EqMode.PARAMETRIC)
            core.setBand(0u, peak(1000f, 6f))
            val c = core.responseCurve(256u, 48000u, 85.3f)
            assertEquals(256, c.freqsHz.size)
            assertEquals(256, c.idealDb.size)
            assertEquals(256, c.achievedDb.size)
            val i = c.freqsHz.indices.minByOrNull { Math.abs(c.freqsHz[it] - 1000f) }!!
            assertEquals(6f, c.idealDb[i], 0.2f)
        }
    }

    @Test
    fun graphic_mode_gain_changes_the_curve() {
        EqCore().use { core ->
            core.setMode(EqMode.GRAPHIC)
            val flat = core.responseCurve(128u, 48000u, 85.3f)
            core.setGraphicGain(5u, 8f)
            val bent = core.responseCurve(128u, 48000u, 85.3f)
            assertNotEquals(flat.idealDb, bent.idealDb)
            assertTrue(bent.idealDb.maxOrNull()!! > 4f)
        }
    }

    @Test
    fun a_drag_is_one_undo_step() {
        EqCore().use { core ->
            core.beginEdit()
            for (i in 1..10) core.setGraphicGain(2u, i.toFloat())
            core.endEdit()
            val after = core.snapshot()
            assertTrue(after.canUndo)
            assertFalse(after.canRedo)
            assertTrue(core.undo())
            val back = core.snapshot()
            assertEquals(0f, back.graphicGainsDb[2], 0f)
            assertFalse(back.canUndo)
            assertTrue(back.canRedo)
            assertTrue(core.redo())
            assertEquals(10f, core.snapshot().graphicGainsDb[2], 0f)
        }
    }

    @Test
    fun save_and_load_round_trip_in_files_dir() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val file = File(dir, "eqcore-binding-test.json")
        file.delete()
        try {
            EqCore().use { core ->
                core.setMode(EqMode.PARAMETRIC)
                core.setBand(1u, peak(2500f, -4f))
                core.setGraphicGain(3u, 5f)
                core.setPreamp(-2f)
                core.save(file.absolutePath)
                val want = core.snapshot().copy(canUndo = false, canRedo = false)
                EqCore.load(file.absolutePath).use { loaded -> assertEquals(want, loaded.snapshot()) }
            }
            file.delete()
            EqCore.loadOrDefault(file.absolutePath).use { d ->
                EqCore().use { fresh -> assertEquals(fresh.snapshot(), d.snapshot()) }
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun out_of_range_index_throws_a_typed_exception() {
        EqCore().use { core ->
            try {
                core.setBand(99u, peak(1000f, 0f))
                fail("expected EqException.OutOfRange")
            } catch (e: EqException.OutOfRange) {
                // expected
            }
        }
    }

    @Test
    fun band_response_db_returns_the_peak_and_rejects_bad_arguments() {
        EqCore().use { core ->
            core.setBand(0u, peak(1000f, 6f))
            val r = core.bandResponseDb(0u, 64u, 48000u)
            assertEquals(64, r.size)
            assertEquals(6f, r.max(), 0.05f)
            try {
                core.bandResponseDb(99u, 64u, 48000u)
                fail("expected EqException.OutOfRange")
            } catch (e: EqException.OutOfRange) {
            }
            try {
                core.bandResponseDb(0u, 1u, 48000u)
                fail("expected EqException.InvalidValue")
            } catch (e: EqException.InvalidValue) {
            }
        }
    }

    @Test
    fun reset_bands_restores_defaults_and_is_one_undo_step() {
        EqCore().use { core ->
            val first = core.snapshot().bands[0]
            core.setBand(0u, peak(5000f, 9f))
            core.resetBands()
            assertEquals(first, core.snapshot().bands[0])
            assertTrue(core.undo())
            assertEquals(peak(5000f, 9f), core.snapshot().bands[0])
        }
    }

    @Test
    fun core_limits_match_what_parametric_math_mirrors() {
        EqCore().use { core ->
            core.setBand(0u, Band(FilterKind.PEAK, 1e9f, 500f, 100f, true))
            val hi = core.snapshot().bands[0]
            assertEquals(20000f, hi.freq, 0f)
            assertEquals(24f, hi.gainDb, 0f)
            assertEquals(18f, hi.q, 0f)
            core.setBand(0u, Band(FilterKind.PEAK, 5f, -500f, 0f, true))
            val lo = core.snapshot().bands[0]
            assertEquals(20f, lo.freq, 0f)
            assertEquals(-24f, lo.gainDb, 0f)
            assertEquals(0.1f, lo.q, 1e-6f)
        }
    }

    @Test
    fun controller_band_response_is_read_only_and_reset_marks_dirty() {
        EqCore().use { core ->
            var dirty = 0
            val c = EqController(core) { dirty++ }
            c.bandResponse(0u, 64u, 48000u)
            assertEquals(0, dirty)
            c.resetBands()
            assertEquals(1, dirty)
        }
    }

    @Test
    fun snapshot_exposes_presets_and_apply_returns_boolean() {
        EqCore().use { core ->
            val snap = core.snapshot()
            assertEquals(listOf("Flat", "Bass", "Treble", "Vocal"), snap.presets.take(4).map { it.name })
            assertTrue(snap.presets.take(4).all { it.builtin })
            assertEquals(null, snap.activePresetKey)
            assertEquals(null, snap.activePresetName)
            assertFalse(snap.presetModified)
            assertTrue(core.applyPreset("builtin:Bass"))
            assertEquals("builtin:Bass", core.snapshot().activePresetKey)
            assertEquals("Bass", core.snapshot().activePresetName)
            assertFalse(core.applyPreset("builtin:Bass"))
        }
    }
}
