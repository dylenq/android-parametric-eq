package com.dylenq.eq.shizuku

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrantCommandTest {
    @Test fun ownPackageGivesTheExactArgv() {
        val argv = GrantCommand.argv("com.dylenq.eq")
        assertEquals(
            listOf("/system/bin/cmd", "package", "grant", "--user", "0", "com.dylenq.eq", "android.permission.DUMP"),
            argv,
        )
        assertEquals("/system/bin/cmd package grant --user 0 com.dylenq.eq android.permission.DUMP", argv!!.joinToString(" "))
    }

    @Test fun shellLineMatchesTheArgvAndTheProofScript() {
        assertEquals(GrantCommand.argv(GrantCommand.APP_ID)!!.joinToString(" "), GrantCommand.SHELL_LINE)
        val script = listOf("../spike-tools/phase11-grant-command.sh", "spike-tools/phase11-grant-command.sh")
            .map(::File).first { it.isFile }.readText()
        assertTrue("script must run the same command", script.contains(GrantCommand.SHELL_LINE))
    }

    @Test fun anyOtherPackageOrOddStringIsRefused() {
        for (bad in listOf("", "com.other.app", "com.dylenq.eq ", "com.dylenq.eq;id", "com.dylenq.eq\nid", "\$(id)", "com.dylenq.eqx", "x")) {
            assertNull("refused: '$bad'", GrantCommand.argv(bad))
        }
        assertNull(GrantCommand.argv("com.other.app", ownId = "com.dylenq.eq"))
        assertNull(GrantCommand.argv("com.dylenq.eq", ownId = "com.other.app"))
    }

    @Test fun grantTargetsTheUserTheAppRunsIn() {
        val argv = GrantCommand.argv("com.dylenq.eq", user = 10)!!
        assertEquals("10", argv[argv.indexOf("--user") + 1])
        assertNull(GrantCommand.argv("com.dylenq.eq", user = -1))
        assertEquals(10, GrantCommand.userOf(1010250))
        assertEquals(0, GrantCommand.userOf(10250))
    }

    @Test fun replyDecoding() {
        assertEquals(GrantResult.Granted, GrantCommand.decode(GrantCommand.encode(0, "")))
        assertEquals(GrantResult.Failed("Unknown permission"), GrantCommand.decode(GrantCommand.encode(1, " Unknown permission\n")))
        assertEquals(GrantResult.Failed("no answer from the Shizuku service"), GrantCommand.decode(null))
        assertEquals(GrantResult.Failed("garbled"), GrantCommand.decode("garbled"))
    }

    private class Fake(
        var state: ShizukuState,
        val accept: Boolean = true,
        val after: ShizukuState = ShizukuState.Ready,
        val result: GrantResult = GrantResult.Granted,
    ) : ShizukuGateway {
        var requests = 0
        var grants = 0
        override fun state() = state
        override suspend fun request(): Boolean { requests++; if (accept) state = after; return accept }
        override suspend fun grant(): GrantResult { grants++; return result }
    }

    private fun run(g: Fake) = runBlocking { ShizukuGrantFlow.run(g) }

    @Test fun notInstalledAndNotRunningNeverGrant() {
        val a = Fake(ShizukuState.NotInstalled)
        assertEquals(GrantResult.NotInstalled, run(a)); assertEquals(0, a.grants); assertEquals(0, a.requests)
        val b = Fake(ShizukuState.NotRunning)
        assertEquals(GrantResult.NotRunning, run(b)); assertEquals(0, b.grants); assertEquals(0, b.requests)
    }

    @Test fun refusedPermissionDoesNotGrant() {
        val g = Fake(ShizukuState.NoPermission, accept = false)
        assertEquals(GrantResult.Refused, run(g))
        assertEquals(1, g.requests); assertEquals(0, g.grants)
    }

    @Test fun acceptedPermissionThenGrants() {
        val g = Fake(ShizukuState.NoPermission)
        assertEquals(GrantResult.Granted, run(g))
        assertEquals(1, g.requests); assertEquals(1, g.grants)
    }

    @Test fun serverGoneWhileAskingIsNotRunning() {
        val g = Fake(ShizukuState.NoPermission, after = ShizukuState.NotRunning)
        assertEquals(GrantResult.NotRunning, run(g))
        assertEquals(0, g.grants)
    }

    @Test fun readyGrantsAndFailureKeepsTheOutput() {
        val ok = Fake(ShizukuState.Ready)
        assertEquals(GrantResult.Granted, run(ok)); assertEquals(0, ok.requests)
        val bad = Fake(ShizukuState.Ready, result = GrantResult.Failed("boom"))
        assertEquals(GrantResult.Failed("boom"), run(bad))
    }
}
