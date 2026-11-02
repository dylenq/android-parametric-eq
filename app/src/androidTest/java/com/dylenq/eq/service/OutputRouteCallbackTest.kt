package com.dylenq.eq.service

import android.media.AudioDeviceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.testsupport.FakeEffectFactory
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A route change re-applies the EQ through the effect manager without writing the state. */
@RunWith(AndroidJUnit4::class)
class OutputRouteCallbackTest {
    private val dir: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "route-cb").also { it.mkdirs() }

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun both_callbacks_call_the_function_once_each() {
        val calls = AtomicInteger()
        val cb = OutputRouteCallback { calls.incrementAndGet() }
        cb.onAudioDevicesAdded(emptyArray<AudioDeviceInfo>())
        assertEquals(1, calls.get())
        cb.onAudioDevicesRemoved(emptyArray<AudioDeviceInfo>())
        assertEquals(2, calls.get())
    }

    @Test
    fun a_route_change_reapplies_without_a_state_write() {
        val saves = AtomicInteger()
        val applies = AtomicInteger()
        val factory = FakeEffectFactory()
        val rt = EqRuntime(factory, dir, saver = { core: EqCore, path: String -> saves.incrementAndGet(); core.save(path) })
        try {
            rt.post(SessionIntent.Event.Open(41, "com.example.player"))
            waitFor(3_000, "the session") { factory.creates().size == 1 }
            // Let the open's own apply and anything it queued settle before counting.
            rt.flush()
            val creates = factory.creates().size
            val savesBefore = saves.get()

            rt.onApplied = { applies.incrementAndGet() }
            val cb = OutputRouteCallback { rt.reapply() }

            cb.onAudioDevicesAdded(emptyArray<AudioDeviceInfo>())
            waitFor(3_000, "the apply after a device was added") { applies.get() >= 1 }
            val afterAdd = applies.get()
            cb.onAudioDevicesRemoved(emptyArray<AudioDeviceInfo>())
            waitFor(3_000, "the apply after a device was removed") { applies.get() > afterAdd }

            Thread.sleep(EqRuntime.SAVE_DEBOUNCE_MS + 500)
            rt.flush()
            assertEquals("the session is not recreated", creates, factory.creates().size)
            assertEquals("no state write follows a re-apply", savesBefore, saves.get())

            // Control: an ordinary edit does reach the saver.
            rt.controller.setBypass(true)
            waitFor(EqRuntime.SAVE_DEBOUNCE_MS + 3_000, "the save after an edit") { saves.get() > savesBefore }
            assertTrue(saves.get() > savesBefore)
        } finally {
            rt.shutdown()
            dir.deleteRecursively()
        }
    }
}
