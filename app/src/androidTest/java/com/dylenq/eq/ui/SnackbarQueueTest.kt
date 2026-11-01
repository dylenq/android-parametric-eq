package com.dylenq.eq.ui

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.testsupport.EqComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A queued timed snackbar waits for its turn, and its timer only ever dismisses itself. */
@RunWith(AndroidJUnit4::class)
class SnackbarQueueTest {
    @get:Rule val rule = EqComposeRule()

    private fun waitFor(ms: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun aQueuedSnackbarGetsItsOwnTimeAndThenGoesAway() {
        val host = SnackbarHostState()
        rule.host { SnackbarHost(host) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            var first: Job? = null
            var second: Job? = null
            scope.launch {
                first = launch { host.showTimed("first", 400) }
                second = launch { host.showTimed("second", 400) }
            }
            waitFor(2_000, "the first snackbar") { host.currentSnackbarData?.visuals?.message == "first" }
            waitFor(2_000, "the second snackbar") { host.currentSnackbarData?.visuals?.message == "second" }
            val shownAt = System.currentTimeMillis()
            waitFor(3_000, "the second snackbar to leave") { second?.isCompleted == true }
            assertTrue("kept for its own duration", System.currentTimeMillis() - shownAt >= 250)
            assertTrue(first?.isCompleted == true)
            assertEquals(null, host.currentSnackbarData)
        } finally {
            scope.cancel()
        }
    }

    @Test fun aWithdrawnSnackbarNeverAppears() {
        val host = SnackbarHostState()
        rule.host { SnackbarHost(host) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            var acted = false
            var queued: Job? = null
            scope.launch {
                launch { host.showTimed("first", 600) }
                queued = launch {
                    host.showTimed("undo", 600, "Undo", dismissWhen = { gate.await() }) { acted = true }
                }
            }
            waitFor(2_000, "the first snackbar") { host.currentSnackbarData?.visuals?.message == "first" }
            gate.complete(Unit)
            waitFor(1_000, "the withdrawal") { queued?.isCompleted == true }
            waitFor(2_000, "the first snackbar to leave") { host.currentSnackbarData == null }
            assertEquals(false, acted)
        } finally {
            scope.cancel()
        }
    }
}
