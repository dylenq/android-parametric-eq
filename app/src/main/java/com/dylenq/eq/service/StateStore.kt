package com.dylenq.eq.service

import android.util.Log
import com.dylenq.eq.core.EqCore
import com.dylenq.eq.core.EqException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Why the saved state was set aside at open. */
sealed interface StoreNotice {
    data class NewerVersion(val version: UInt) : StoreNotice
    data object Damaged : StoreNotice
}

/**
 * Owns `eq-state.json` in [dir]. [open] uses only the typed loader: an unreadable or future-version
 * file is moved aside (timestamped `.bak`, newest [KEEP_BACKUPS] kept) and defaults are used. The
 * core's permissive default-on-error loader is never called, because the next save would overwrite
 * a file written by a newer version.
 */
class StateStore(
    private val dir: File,
    private val saver: (EqCore, String) -> Unit = { core, path -> core.save(path) },
) {
    val file: File get() = File(dir, FILE_NAME)

    fun open(): Pair<EqCore, StoreNotice?> {
        val f = file
        if (!f.exists()) return EqCore() to null
        return try {
            EqCore.load(f.path) to null
        } catch (e: EqException.UnsupportedVersion) {
            backup(f, "v${e.v1}")
            EqCore() to StoreNotice.NewerVersion(e.v1)
        } catch (e: EqException.Corrupt) {
            backup(f, "corrupt")
            EqCore() to StoreNotice.Damaged
        } catch (e: EqException) {
            backup(f, "io")
            EqCore() to StoreNotice.Damaged
        } catch (e: Throwable) {
            Log.w(TAG, "unexpected load failure: ${e.javaClass.name}: ${e.message}")
            backup(f, "io")
            EqCore() to StoreNotice.Damaged
        }
    }

    /** Writes the live file atomically (tmp + rename inside the core). Throws on failure. */
    fun save(core: EqCore) {
        dir.mkdirs()
        saver(core, file.path)
    }

    private fun backup(f: File, tag: String) {
        var millis = System.currentTimeMillis()
        // Keep the suffix strictly increasing so "newest" is well defined within one millisecond.
        val newest = backups().maxOfOrNull { suffix(it) } ?: 0L
        if (millis <= newest) millis = newest + 1
        val target = File(dir, "$FILE_NAME.$tag-$millis.bak")
        try {
            Files.move(f.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "backup move failed: ${t.javaClass.name}: ${t.message}")
            f.deleteRecursively()
        }
        prune()
    }

    private fun backups(): List<File> =
        dir.listFiles { x -> x.name.startsWith("$FILE_NAME.") && x.name.endsWith(".bak") }?.toList().orEmpty()

    private fun suffix(f: File): Long =
        f.name.removeSuffix(".bak").substringAfterLast('-').toLongOrNull() ?: 0L

    private fun prune() {
        backups().sortedByDescending { suffix(it) }.drop(KEEP_BACKUPS).forEach { it.deleteRecursively() }
    }

    companion object {
        const val FILE_NAME = "eq-state.json"
        const val KEEP_BACKUPS = 3
        private const val TAG = "EQ-SVC"
    }
}
