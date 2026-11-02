package com.dylenq.eq.service

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real reader class against fake binders and a real pipe. */
@RunWith(AndroidJUnit4::class)
class AudioFlingerDumpTest {
    private val table = """
        Global session refs:
          session   pid    uid  name  count
              77  1234  10200  com.example.player  1
              78  1235  10201  com.example.other  1
        Hardware status: 0
        more stuff after the table
    """.trimIndent()

    private fun openFds() = File("/proc/self/fd").list()?.size ?: 0

    /** Writes [text] into the fd from a thread, then closes the dup unless [hold]. */
    private fun writer(text: () -> Sequence<ByteArray>, hold: MutableList<ParcelFileDescriptor>? = null) = BinderDumper { fd: FileDescriptor ->
        val dup = ParcelFileDescriptor.dup(fd)
        Thread {
            try {
                FileOutputStream(dup.fileDescriptor).use { o -> for (c in text()) { o.write(c); o.flush() } }
                // Closing is deliberate; a held dup keeps the pipe open instead.
            } catch (_: Throwable) {
            } finally {
                if (hold != null) hold.add(dup) else try { dup.close() } catch (_: Throwable) {}
            }
        }.start()
    }

    @Test fun deniedPermissionNeverCallsTheBinder() {
        var calls = 0
        val d = AudioFlingerDump(permission = { false }, dumper = { calls++; null })
        repeat(20) { assertTrue(d.read() is DumpResult.Unavailable) }
        assertEquals(0, calls)
    }

    @Test fun readsTheTable() {
        val d = AudioFlingerDump(permission = { true }, dumper = { writer({ sequenceOf(table.toByteArray()) }) })
        val r = d.read()
        assertTrue(r.toString(), r is DumpResult.Sessions)
        assertEquals(2, (r as DumpResult.Sessions).rows.size)
    }

    @Test fun nullBinderAndThrowingDumperAreValues() {
        assertTrue(AudioFlingerDump(permission = { true }, dumper = { null }).read() is DumpResult.Unavailable)
        assertTrue(AudioFlingerDump(permission = { true }, dumper = { BinderDumper { error("boom") } }).read() is DumpResult.Unavailable)
        assertTrue(AudioFlingerDump(permission = { true }, dumper = { error("no reflection") }).read() is DumpResult.Unavailable)
    }

    @Test fun missingTableIsUnavailable() {
        val d = AudioFlingerDump(permission = { true }, dumper = { writer({ sequenceOf("nothing here\n".toByteArray()) }) })
        assertTrue(d.read() is DumpResult.Unavailable)
    }

    @Test fun repeatedScansLeaveNoDescriptorsOpen() {
        val d = AudioFlingerDump(permission = { true }, dumper = { writer({ sequenceOf(table.toByteArray()) }) })
        d.read()
        Thread.sleep(200)
        val start = openFds()
        repeat(50) { d.read() }
        Thread.sleep(300) // the writer threads close their dups
        assertTrue("fd growth ${openFds() - start}", openFds() - start <= 6)
    }

    @Test fun aSourceThatNeverEndsIsCutOff() {
        val hold = ArrayList<ParcelFileDescriptor>()
        val d = AudioFlingerDump(permission = { true }, timeoutMs = 600, dumper = { writer({ sequenceOf("Global session refs:\n".toByteArray()) }, hold) })
        val t0 = System.currentTimeMillis()
        val r = d.read()
        val took = System.currentTimeMillis() - t0
        assertTrue(r.toString(), r is DumpResult.Unavailable)
        assertTrue("took $took", took in 500..2500)
        Thread.sleep(200)
        hold.forEach { it.close() }
    }

    @Test fun outputOverTheCapIsTruncated() {
        val hold = ArrayList<ParcelFileDescriptor>()
        val chunk = ByteArray(8192) { 'x'.code.toByte() }
        var written = 0L
        val big = writer({ generateSequence { chunk.also { written += it.size } }.take(40) }, hold)
        val d = AudioFlingerDump(permission = { true }, maxBytes = 64 * 1024, dumper = { big })
        val r = d.read()
        assertTrue(r is DumpResult.Unavailable) // no table inside the capped part
        Thread.sleep(200)
        hold.forEach { it.close() }
    }

    @Test fun readingStopsAfterTheTableEnds() {
        val hold = ArrayList<ParcelFileDescriptor>()
        // After the table the source keeps the pipe open; a reader that waited would hit the watchdog.
        val d = AudioFlingerDump(permission = { true }, timeoutMs = 2500, dumper = { writer({ sequenceOf(table.toByteArray()) }, hold) })
        val t0 = System.currentTimeMillis()
        val r = d.read()
        assertTrue(r.toString(), r is DumpResult.Sessions)
        assertTrue(System.currentTimeMillis() - t0 < 2000)
        hold.forEach { it.close() }
    }
}
