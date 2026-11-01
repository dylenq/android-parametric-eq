package com.dylenq.eq.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.testsupport.FakeEffectFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A failed state write must tell the user and retry on its own, not wait for the next edit. */
@RunWith(AndroidJUnit4::class)
class SaveFailureTest {
    private val dir: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "save-fail").also { it.mkdirs() }

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun failure_shows_a_notice_then_a_retry_saves_and_clears_it() {
        val full = AtomicBoolean(true)
        val attempts = AtomicInteger()
        val saver: (EqCore, String) -> Unit = { core, path ->
            attempts.incrementAndGet()
            if (full.get()) throw IOException("no space left") else core.save(path)
        }
        val rt = EqRuntime(FakeEffectFactory(), dir, saver = saver)
        try {
            rt.controller.setBypass(true)
            waitFor(3_000, "the notice") { rt.status.value.notice == EqRuntime.SAVE_FAILED_NOTICE }

            // A dismissed notice stays dismissed across the next failed retry.
            rt.dismissNotice()
            val seen = attempts.get()
            waitFor(EqRuntime.SAVE_RETRY_MS + 3_000, "a retry with no further edit") { attempts.get() > seen }
            assertEquals(null, rt.status.value.notice)

            full.set(false)
            val before = attempts.get()
            waitFor(EqRuntime.SAVE_RETRY_MS + 3_000, "the retry that succeeds") { attempts.get() > before }
            waitFor(1_000, "the saved file") { File(dir, StateStore.FILE_NAME).isFile }
            assertTrue(EqCore.load(File(dir, StateStore.FILE_NAME).path).snapshot().bypass)
        } finally {
            rt.shutdown()
            dir.deleteRecursively()
        }
    }

    @Test
    fun a_new_failure_after_a_success_shows_the_notice_again() {
        val full = AtomicBoolean(true)
        val saver: (EqCore, String) -> Unit = { core, path ->
            if (full.get()) throw IOException("no space left") else core.save(path)
        }
        val rt = EqRuntime(FakeEffectFactory(), dir, saver = saver)
        try {
            rt.controller.setBypass(true)
            waitFor(3_000, "the first notice") { rt.status.value.notice == EqRuntime.SAVE_FAILED_NOTICE }
            full.set(false)
            waitFor(EqRuntime.SAVE_RETRY_MS + 3_000, "the notice to clear") { rt.status.value.notice == null }
            full.set(true)
            rt.controller.setBypass(false)
            waitFor(3_000, "the second notice") { rt.status.value.notice == EqRuntime.SAVE_FAILED_NOTICE }
        } finally {
            full.set(false)
            rt.shutdown()
            dir.deleteRecursively()
        }
    }
}
