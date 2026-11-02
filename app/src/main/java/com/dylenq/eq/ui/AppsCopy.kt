package com.dylenq.eq.ui

import com.dylenq.eq.service.AppEntry

/** Pure copy, ordering and label rules for the Settings "Apps" section. */
object AppsCopy {
    const val DEFAULT_PRESET = "Live EQ (default)"
    const val EMPTY_TITLE = "No apps yet"
    const val EMPTY_BODY = "The list fills as apps start playing audio."
    const val FOLLOW_NOTE = "Apps with their own preset do not follow the live EQ."
    const val GLOBAL_NOTE = "Global mode is on, so these per-app settings are ignored."

    fun countText(n: Int): String = if (n == 1) "1 app" else "$n apps"

    /** Apps playing now first, then most recently seen first, ties by shown name. */
    fun sorted(apps: List<AppEntry>, label: (String) -> String): List<AppEntry> =
        apps.sortedWith(
            compareByDescending<AppEntry> { it.playingNow }
                .thenByDescending { it.lastSeen }
                .thenBy { label(it.pkg).lowercase() }
                .thenBy { it.pkg },
        )

    /**
     * The preset name for a row. A missing key, or one that no longer resolves in [names]
     * (key to display name), reads as the live EQ.
     */
    fun presetText(key: String?, names: Map<String, String>): String =
        if (key == null) DEFAULT_PRESET else names[key] ?: DEFAULT_PRESET

    /** Plain row title: the resolved label when there is one, else the package text, else "Unknown app". */
    fun title(label: String): String = SessionCopy.rowTitle(label)
}

/**
 * Caches app labels per package. [resolver] returns the PackageManager label or null; a throwing or blank
 * result gives the plain package text. The result is only ever shown as text, never a link or action.
 */
class AppLabels(private val resolver: (String) -> String?) {
    private val cache = HashMap<String, String>()

    fun label(pkg: String): String = synchronized(cache) {
        cache.getOrPut(pkg) {
            val found = try {
                resolver(pkg)
            } catch (_: Exception) {
                null
            }
            if (found.isNullOrBlank()) pkg else found
        }
    }
}
