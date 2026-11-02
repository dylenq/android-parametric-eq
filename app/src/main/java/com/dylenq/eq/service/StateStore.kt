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

    /**
     * The file loaded, but [count] saved entries were damaged and were dropped or reset to a default.
     * [backedUp] says whether a copy of the original file was kept.
     */
    data class Repaired(val count: UInt, val backedUp: Boolean = true) : StoreNotice
}

/** The text the runtime shows for a notice from [StateStore.open]. */
internal fun storeNoticeText(notice: StoreNotice?): String? = when (notice) {
    is StoreNotice.NewerVersion -> "Saved settings were made by a newer version and were set aside"
    StoreNotice.Damaged -> "Saved settings were damaged and were set aside"
    is StoreNotice.Repaired ->
        if (notice.backedUp) {
            "Some saved settings were damaged and were removed. A backup was kept."
        } else {
            "Some saved settings were damaged and were removed."
        }
    null -> null
}

/**
 * Owns `eq-state.json` in [dir]. [open] uses only the typed loader: an unreadable or future-version
 * file is moved aside (timestamped `.bak`, newest [KEEP_BACKUPS] kept) and defaults are used. The
 * core's permissive default-on-error loader is never called, because the next save would overwrite
 * a file written by a newer version.
 *
 * A file that loads but had damaged entries (the core counts them) is copied to a `.repaired-` backup
 * before anything can save over it; the live file stays where it is. [copier] makes that copy.
 */
class StateStore(
    private val dir: File,
    private val saver: (EqCore, String) -> Unit = { core, path -> core.save(path) },
    private val copier: (File, File) -> Unit = { from, to -> Files.copy(from.toPath(), to.toPath()) },
) {
    val file: File get() = File(dir, FILE_NAME)

    fun open(): Pair<EqCore, StoreNotice?> {
        val f = file
        if (!f.exists()) return EqCore() to null
        val core = try {
            EqCore.load(f.path)
        } catch (e: EqException.UnsupportedVersion) {
            backup(f, "v${e.v1}")
            return EqCore() to StoreNotice.NewerVersion(e.v1)
        } catch (e: EqException.Corrupt) {
            backup(f, "corrupt")
            return EqCore() to StoreNotice.Damaged
        } catch (e: EqException) {
            backup(f, "io")
            return EqCore() to StoreNotice.Damaged
        } catch (e: Throwable) {
            Log.w(TAG, "unexpected load failure: ${e.javaClass.name}: ${e.message}")
            backup(f, "io")
            return EqCore() to StoreNotice.Damaged
        }
        val repairs = core.loadRepairs()
        if (repairs == 0u) return core to null
        return core to StoreNotice.Repaired(repairs, backupCopy(f, "repaired"))
    }

    /** Writes the live file atomically (tmp + rename inside the core). Throws on failure. */
    fun save(core: EqCore) {
        dir.mkdirs()
        saver(core, file.path)
    }

    /** Moves [f] aside whole; the live file is gone afterwards. */
    private fun backup(f: File, tag: String) {
        val target = nextTarget(tag)
        try {
            Files.move(f.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "backup move failed: ${t.javaClass.name}: ${t.message}")
            f.deleteRecursively()
        }
        prune()
    }

    /** Copies [f] aside and leaves it in place. Returns whether the copy exists; a failure is logged. */
    private fun backupCopy(f: File, tag: String): Boolean {
        // A repaired file stays on disk until the first save, so each launch before then sees the same bytes.
        // Keep one copy of them: more would push older, different backups out of the newest-three limit.
        if (alreadyCopied(f, tag)) return true
        val target = nextTarget(tag)
        val copied = try {
            copier(f, target)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "backup copy failed: ${t.javaClass.name}: ${t.message}")
            target.deleteRecursively()
            false
        }
        prune()
        return copied
    }

    private fun alreadyCopied(f: File, tag: String): Boolean = try {
        val bytes = f.readBytes()
        backups().any { it.name.startsWith("$FILE_NAME.$tag-") && it.isFile && it.readBytes().contentEquals(bytes) }
    } catch (t: Throwable) {
        false
    }

    private fun nextTarget(tag: String): File {
        var millis = System.currentTimeMillis()
        // Keep the suffix strictly increasing so "newest" is well defined within one millisecond.
        val newest = backups().maxOfOrNull { suffix(it) } ?: 0L
        if (millis <= newest) millis = newest + 1
        return File(dir, "$FILE_NAME.$tag-$millis.bak")
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
