package com.dylenq.eq.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTableTest {
    private fun lines(s: String) = s.trimIndent().lines()

    // Measured on API 37 (see .planning/research/v0.3-PLAYER-TRACKING.md).
    private val api37 = """
        Some earlier section
        Global session refs:
          session  cnt     pid    uid  name
              657    1    1437  10250  com.dylenq.eq
        Hardware status: 0
    """

    private val emptyTable = """
        Global session refs:
          session  cnt     pid    uid  name
        Hardware status: 0
    """

    private val denial = "Permission Denial: can't dump AudioFlinger from pid=0, uid=10248"

    private val noBlock = """
        Clients:
          pid 12
        Hardware status: 0
    """

    // Android 9 header, written from memory of AOSP and not checked against a device.
    private val android9 = """
        Global session refs:
          session pid count
              12  345     1
    """

    @Test
    fun measured_api37_table_gives_one_row() {
        val r = SessionTable.parse(lines(api37)) as DumpResult.Sessions
        assertEquals(listOf(SessionRow(657, 1437, 10250, "com.dylenq.eq")), r.rows)
    }

    @Test
    fun header_with_no_rows_is_empty() {
        assertEquals(DumpResult.Sessions(emptyList()), SessionTable.parse(lines(emptyTable)))
    }

    @Test
    fun permission_denial_is_denied() {
        assertEquals(DumpResult.Denied, SessionTable.parse(listOf(denial)))
    }

    @Test
    fun missing_block_is_unavailable() {
        assertTrue(SessionTable.parse(lines(noBlock)) is DumpResult.Unavailable)
        assertTrue(SessionTable.parse(emptyList()) is DumpResult.Unavailable)
    }

    @Test
    fun header_without_uid_is_unavailable_with_the_exact_reason() {
        assertEquals(DumpResult.Unavailable("no app ids in this Android version"), SessionTable.parse(lines(android9)))
    }

    @Test
    fun malformed_rows_are_skipped_and_the_table_ends_at_a_label_line() {
        val text = """
            Global session refs:
              session  cnt     pid    uid  name
                  abc    1    1437  10250  com.a.b
                  12    1
                  13    1    1438  10251  com.c.d
                  -4    1    1439  10252  com.e.f
            Hardware status: 0
                  14    1    1440  10253  com.g.h
        """
        val r = SessionTable.parse(lines(text)) as DumpResult.Sessions
        assertEquals(listOf(13), r.rows.map { it.session })
    }

    @Test
    fun columns_are_found_by_header_name() {
        val text = """
            Global session refs:
              uid  name  pid  session
              10250  com.a.b  1437  657
        """
        val r = SessionTable.parse(lines(text)) as DumpResult.Sessions
        assertEquals(listOf(SessionRow(657, 1437, 10250, "com.a.b")), r.rows)
    }

    @Test
    fun a_blank_name_stays_null() {
        val text = """
            Global session refs:
              session  cnt     pid    uid  name
                  657    1    1437  10250
        """
        val r = SessionTable.parse(lines(text)) as DumpResult.Sessions
        assertNull(r.rows.single().name)
    }

    @Test
    fun garbage_never_throws() {
        val junk = listOf("Global session refs:", "\u0000\u0001 \t", "99999999999999999999 1 2 3 x", "")
        assertTrue(SessionTable.parse(junk) is DumpResult.Unavailable)
        val junkRows = listOf("Global session refs:", "session pid uid", "\u0000\u0001 \t", "99999999999999999999 1 2", "1 2")
        assertEquals(DumpResult.Sessions(emptyList()), SessionTable.parse(junkRows))
        assertTrue(SessionTable.parse(listOf("Global session refs:")) is DumpResult.Unavailable)
    }

    // ---- filter ----

    private class FakeResolver(
        val launchable: Set<String>,
        val uidPackages: Map<Int, List<String>> = emptyMap(),
    ) : AppResolver {
        override fun launchable(pkg: String) = pkg in launchable
        override fun packagesForUid(uid: Int) = uidPackages[uid] ?: emptyList()
    }

    private val own = 10250

    @Test
    fun filter_drops_system_other_user_own_and_non_launchable() {
        val rows = listOf(
            SessionRow(1, 1, 1000, "com.android.system"),
            SessionRow(2, 1, 9999, "com.low.uid"),
            SessionRow(3, 1, 1010300, "com.other.user"),
            SessionRow(4, 1, own, "com.dylenq.eq"),
            SessionRow(5, 1, 10300, "com.no.launcher"),
            SessionRow(6, 1, 10301, "com.good.app"),
        )
        val out = TrackedApps.filter(rows, own, FakeResolver(setOf("com.dylenq.eq", "com.good.app", "com.other.user")))
        assertEquals(listOf(TrackedRow(6, "com.good.app")), out)
    }

    @Test
    fun filter_falls_back_to_the_uid_package_when_the_name_is_not_a_package() {
        val rows = listOf(SessionRow(7, 1, 10301, "u0_a235"), SessionRow(8, 1, 10302, null))
        val resolver = FakeResolver(
            setOf("com.real.one", "com.real.two"),
            mapOf(10301 to listOf("com.real.one"), 10302 to listOf("com.real.two")),
        )
        assertEquals(listOf(TrackedRow(7, "com.real.one"), TrackedRow(8, "com.real.two")), TrackedApps.filter(rows, own, resolver))
    }

    @Test
    fun filter_skips_session_zero_and_negative() {
        val rows = listOf(SessionRow(0, 1, 10301, "com.a.b"), SessionRow(-1, 1, 10301, "com.a.b"))
        assertTrue(TrackedApps.filter(rows, own, FakeResolver(setOf("com.a.b"))).isEmpty())
    }

    @Test
    fun tracking_state_mapping() {
        assertEquals(TrackingState.Off, trackingStateFor(false, true, DumpResult.Denied))
        assertEquals(TrackingState.NeedsPermission, trackingStateFor(true, false, null))
        assertEquals(TrackingState.NeedsReboot, trackingStateFor(true, true, DumpResult.Denied))
        assertEquals(TrackingState.Unsupported("x"), trackingStateFor(true, true, DumpResult.Unavailable("x")))
        assertEquals(TrackingState.Working(1), trackingStateFor(true, true, DumpResult.Sessions(listOf(SessionRow(1, 1, 1, null)))))
        assertEquals(TrackingState.Working(0), trackingStateFor(true, true, null))
    }
}
