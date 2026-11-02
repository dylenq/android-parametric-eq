package com.dylenq.eq.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Reads the audio server's session table. Behind an interface so tests can count calls. */
interface DumpSource {
    fun read(): DumpResult
}

/** Asks one service to write its dump into a file descriptor (IBinder.dumpAsync in the real one). */
fun interface BinderDumper {
    fun dump(fd: FileDescriptor)
}

/**
 * The real reader. Never touches the binder unless [permission] says DUMP is granted (an early refusal
 * is cached by the audio server until it restarts). Bounded in time and size, closes both pipe ends
 * every time, and turns every failure into a value.
 */
class AudioFlingerDump(
    private val permission: () -> Boolean,
    private val dumper: () -> BinderDumper? = ::audioFlingerDumper,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val maxBytes: Int = MAX_BYTES,
) : DumpSource {

    override fun read(): DumpResult {
        if (!permission()) return DumpResult.Unavailable("the dump permission is not granted")
        var rd: ParcelFileDescriptor? = null
        var wr: ParcelFileDescriptor? = null
        var guard: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            val d = dumper() ?: return DumpResult.Unavailable("the audio server was not found")
            val pipe = ParcelFileDescriptor.createPipe()
            rd = pipe[0]
            wr = pipe[1]
            val input = ParcelFileDescriptor.AutoCloseInputStream(rd)
            val timedOut = AtomicBoolean(false)
            // Closing the read end unblocks a read that would otherwise wait for a server that never ends.
            guard = watchdog.schedule({
                timedOut.set(true)
                try { input.close() } catch (_: Throwable) {}
            }, timeoutMs, TimeUnit.MILLISECONDS)
            d.dump(wr.fileDescriptor)
            // Our write end must go, or the read never sees the end of the data.
            wr.close()
            wr = null
            val lines = readTable(input)
            if (timedOut.get()) return DumpResult.Unavailable("the audio server did not answer in time")
            return SessionTable.parse(lines)
        } catch (t: Throwable) {
            return DumpResult.Unavailable("the audio server could not be read")
        } finally {
            guard?.cancel(false)
            try { wr?.close() } catch (_: Throwable) {}
            try { rd?.close() } catch (_: Throwable) {}
        }
    }

    /** Reads lines until the table has ended, the data ends, or the size cap is hit. */
    private fun readTable(raw: InputStream): List<String> {
        val lines = ArrayList<String>()
        val capped = object : InputStream() {
            var left = maxBytes
            override fun read(): Int {
                if (left <= 0) return -1
                val b = raw.read()
                if (b >= 0) left--
                return b
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = raw.read(b, off, minOf(len, left))
                if (n > 0) left -= n
                return n
            }
        }
        var start = -1
        val r = capped.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = r.readLine() ?: break
            lines.add(line)
            val t = line.trim()
            if (start < 0) {
                if (t == BLOCK) start = lines.size - 1
            } else if (lines.size > start + 2 && (t.isEmpty() || t.contains(':'))) {
                break
            }
        }
        return lines
    }

    companion object {
        const val TIMEOUT_MS = 3000L
        const val MAX_BYTES = 2 * 1024 * 1024
        private const val BLOCK = "Global session refs:"

        /** Checks the real DUMP permission. */
        fun create(context: Context): AudioFlingerDump {
            val app = context.applicationContext
            return AudioFlingerDump(permission = { hasDump(app) })
        }

        fun hasDump(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

        private val watchdog: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "eq-tracker-watchdog").apply { isDaemon = true } }
        }
    }
}

/** Reflection on the hidden ServiceManager; null on any failure (a future Android may block it). */
internal fun audioFlingerDumper(): BinderDumper? = try {
    val sm = Class.forName("android.os.ServiceManager")
    val binder = sm.getMethod("getService", String::class.java).invoke(null, "media.audio_flinger") as? IBinder
    binder?.let { b -> BinderDumper { fd -> b.dumpAsync(fd, arrayOf()) } }
} catch (t: Throwable) {
    null
}
