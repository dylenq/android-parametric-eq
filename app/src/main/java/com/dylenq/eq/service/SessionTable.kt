package com.dylenq.eq.service

/** Pure parser for the `Global session refs:` table in the audio server's dump. Never throws. */
object SessionTable {
    private const val BLOCK = "Global session refs:"
    const val NO_UID_REASON = "no app ids in this Android version"
    const val NO_TABLE_REASON = "the session table is missing"

    fun parse(lines: List<String>): DumpResult {
        try {
            if (lines.any { it.trimStart().startsWith("Permission Denial") }) return DumpResult.Denied
            val at = lines.indexOfFirst { it.trim() == BLOCK }
            if (at < 0 || at + 1 >= lines.size) return DumpResult.Unavailable(NO_TABLE_REASON)
            val header = lines[at + 1].trim().split(WS)
            val iSession = header.indexOf("session")
            val iPid = header.indexOf("pid")
            val iUid = header.indexOf("uid")
            val iName = header.indexOf("name")
            if (iSession < 0) return DumpResult.Unavailable(NO_TABLE_REASON)
            if (iUid < 0 || iPid < 0) return DumpResult.Unavailable(NO_UID_REASON)
            val need = maxOf(iSession, iPid, iUid) + 1
            val rows = ArrayList<SessionRow>()
            for (i in at + 2 until lines.size) {
                val line = lines[i].trim()
                // A blank line or a "Label: value" line ends the table (here "Hardware status: 0").
                if (line.isEmpty() || line.contains(':')) break
                val cells = line.split(WS)
                val session = cells.getOrNull(iSession)?.toIntOrNull()
                val pid = cells.getOrNull(iPid)?.toIntOrNull()
                val uid = cells.getOrNull(iUid)?.toIntOrNull()
                if (cells.size < need || session == null || pid == null || uid == null) continue
                if (session <= 0) continue
                val name = if (iName >= 0) cells.getOrNull(iName)?.takeIf { it.isNotBlank() } else null
                rows.add(SessionRow(session, pid, uid, name))
            }
            return DumpResult.Sessions(rows)
        } catch (t: Throwable) {
            return DumpResult.Unavailable("the session table could not be read")
        }
    }

    private val WS = Regex("\\s+")
}

/** Resolves uids and packages; behind an interface so the filter is testable on the JVM. */
interface AppResolver {
    /** True when the package has a launcher entry (getLaunchIntentForPackage != null). */
    fun launchable(pkg: String): Boolean
    fun packagesForUid(uid: Int): List<String>
}

data class TrackedRow(val session: Int, val pkg: String)

object TrackedApps {
    private const val PER_USER = 100000
    private const val FIRST_APP_UID = 10000

    /** Keeps third-party launchable apps of this user, never this app itself. */
    fun filter(rows: List<SessionRow>, ownUid: Int, resolver: AppResolver): List<TrackedRow> {
        val out = ArrayList<TrackedRow>()
        for (r in rows) {
            try {
                if (r.session <= 0 || r.uid % PER_USER < FIRST_APP_UID) continue
                if (r.uid / PER_USER != ownUid / PER_USER || r.uid == ownUid) continue
                val named = r.name?.takeIf { it.length <= 255 && SessionIntent.PKG.matches(it) }
                val candidates = listOfNotNull(named) + resolver.packagesForUid(r.uid)
                val pkg = candidates.firstOrNull { resolver.launchable(it) } ?: continue
                out.add(TrackedRow(r.session, pkg))
            } catch (t: Throwable) {
                // One bad row never loses the others.
            }
        }
        return out
    }
}
