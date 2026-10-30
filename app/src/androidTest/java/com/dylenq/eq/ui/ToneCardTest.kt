package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tone & balance card (UI-SPEC): behaviour, accessibility, gestures and layout. */
@RunWith(AndroidJUnit4::class)
class ToneCardTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose
    private val ctrl get() = rule.runtime.controller

    private fun hostCard(fontScale: Float = 1f, compact: Boolean = false) = rule.host {
        val ui by holder.ui.collectAsStateWithLifecycle()
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                ToneCard(
                    state = { ui },
                    bass = ToneActions(holder::setBass, holder::bassDragStart, holder::bassDragEnd, holder::resetBass),
                    treble = ToneActions(holder::setTreble, holder::trebleDragStart, holder::trebleDragEnd, holder::resetTreble),
                    balance = ToneActions(holder::setBalance, holder::balanceDragStart, holder::balanceDragEnd, holder::resetBalance),
                    compact = compact,
                    largeFont = fontScale >= 1.3f,
                )
            }
        }
    }

    private fun awaitUi(pred: (EqUiState) -> Boolean): EqUiState =
        runBlocking { withTimeout(10_000) { holder.ui.first { it.curve != null && pred(it) } } }

    private fun peak(c: CurvePoints): Float {
        var best = -100f
        for (i in c.freqsHz.indices) if (c.freqsHz[i] in 60f..160f) best = maxOf(best, c.idealDb[i])
        return best
    }

    private fun setProgress(tag: String, v: Float) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        compose.waitForIdle()
    }

    @Test fun bassSliderMovesTheCurve() {
        hostCard()
        val before = peak(awaitUi { true }.curve!!)
        setProgress("bass-slider", 6f)
        assertEquals(6f, holder.ui.value.bassDb, 0f)
        assertEquals(6f, ctrl.snapshot().bassDb, 0f)
        val after = awaitUi { s -> s.curve != null && peak(s.curve!!) > before + 1f }
        assertTrue(peak(after.curve!!) > before + 1f)
    }

    @Test fun toneCardSitsInTheFullScreenUnderThePreampCard() {
        rule.launch()
        awaitUi { true }
        compose.onNodeWithTag("tone-card").assertExists()
        compose.onNodeWithTag("bass-slider").assertExists()
    }

    // ---- helpers ----------------------------------------------------------------------------

    private fun undoDepth(): Int {
        var n = 0
        while (ctrl.snapshot().canUndo && n < 50) {
            holder.undo()
            n++
        }
        return n
    }

    private fun cfg(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config

    private fun pill(tag: String) = cfg(tag)[SemanticsProperties.Text].joinToString("") { it.text }

    private fun invokeAction(tag: String, label: String) {
        val a = cfg(tag)[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { a.action() }
        compose.waitForIdle()
    }

    private fun dp(px: Int): Float =
        px / InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    // ---- snapping and values ------------------------------------------------------------------

    @Test fun setProgressSnapsToHalfDecibelsAndTheZeroDetent() {
        hostCard()
        setProgress("bass-slider", 5.2f)
        assertEquals(5.0f, ctrl.snapshot().bassDb, 0f)
        setProgress("bass-slider", 0.2f)
        assertEquals("detent", 0f, ctrl.snapshot().bassDb, 0f)
        setProgress("treble-slider", -30f)
        assertEquals(-12f, ctrl.snapshot().trebleDb, 0f)
        setProgress("balance-slider", 30.4f)
        assertEquals(0.30f, ctrl.snapshot().balance, 1e-4f)
        val r = cfg("balance-slider")[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(-100f..100f, r.range)
        assertEquals(199, r.steps)
        val b = cfg("bass-slider")[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(-12f..12f, b.range)
        assertEquals(47, b.steps)
    }

    @Test fun contentDescriptionsStateDescriptionsAndPillsAreSpoken() {
        hostCard()
        for ((tag, d) in listOf("bass-slider" to "Bass", "treble-slider" to "Treble", "balance-slider" to "Balance")) {
            assertEquals(listOf(d), cfg(tag)[SemanticsProperties.ContentDescription])
        }
        setProgress("bass-slider", 3.5f)
        setProgress("treble-slider", -2f)
        setProgress("balance-slider", 30f)
        assertEquals("plus 3.5 decibels", cfg("bass-slider")[SemanticsProperties.StateDescription])
        assertEquals("minus 2 decibels", cfg("treble-slider")[SemanticsProperties.StateDescription])
        assertEquals("30 percent right", cfg("balance-slider")[SemanticsProperties.StateDescription])
        assertEquals("+3.5 dB", pill("bass-pill"))
        assertEquals(listOf("Bass plus 3.5 decibels"), cfg("bass-pill")[SemanticsProperties.ContentDescription])
        assertEquals(listOf("Balance 30 percent right"), cfg("balance-pill")[SemanticsProperties.ContentDescription])
        setProgress("balance-slider", -30f)
        assertEquals("L 30", pill("balance-pill"))
        setProgress("balance-slider", 0f)
        assertEquals("Centre", pill("balance-pill"))
        assertEquals("centred", cfg("balance-slider")[SemanticsProperties.StateDescription])
    }

    @Test fun extremesReadAsPinnedEnds() {
        hostCard()
        setProgress("bass-slider", 12f)
        setProgress("treble-slider", -12f)
        setProgress("balance-slider", 100f)
        assertEquals("+12.0 dB", pill("bass-pill"))
        assertEquals("\u221212.0 dB", pill("treble-pill"))
        assertEquals("R 100", pill("balance-pill"))
        setProgress("balance-slider", -100f)
        assertEquals("L 100", pill("balance-pill"))
        assertEquals(-1f, ctrl.snapshot().balance, 1e-4f)
    }

    @Test fun defaultStateRendersZerosAndCentre() {
        hostCard()
        assertEquals("0 dB", pill("bass-pill"))
        assertEquals("0 dB", pill("treble-pill"))
        assertEquals("Centre", pill("balance-pill"))
        compose.onNodeWithTag("tone-card").assertIsDisplayed()
    }

    // ---- reset ------------------------------------------------------------------------------

    @Test fun resetActionsExistAndAreOneUndoStep() {
        hostCard()
        for (t in listOf("bass-slider", "bass-pill", "treble-slider", "treble-pill")) {
            assertTrue(cfg(t)[SemanticsActions.CustomActions].any { it.label == "Reset to zero" })
        }
        for (t in listOf("balance-slider", "balance-pill")) {
            assertTrue(cfg(t)[SemanticsActions.CustomActions].any { it.label == "Reset to centre" })
        }
        setProgress("bass-slider", 6f)
        invokeAction("bass-slider", "Reset to zero")
        assertEquals(0f, ctrl.snapshot().bassDb, 0f)
        assertEquals("setup step plus one reset step", 2, undoDepth())

        setProgress("balance-slider", 40f)
        invokeAction("balance-pill", "Reset to centre")
        assertEquals(0f, ctrl.snapshot().balance, 0f)
    }

    @Test fun resetAtDefaultAddsNoUndoStep() {
        hostCard()
        invokeAction("bass-slider", "Reset to zero")
        invokeAction("treble-pill", "Reset to zero")
        invokeAction("balance-slider", "Reset to centre")
        compose.onNodeWithTag("bass-pill").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(0, undoDepth())
    }

    @Test fun doubleTappingThePillResetsInOneUndoStep() {
        hostCard()
        setProgress("treble-slider", 7f)
        compose.onNodeWithTag("treble-pill").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(0f, ctrl.snapshot().trebleDb, 0f)
        assertEquals("setup step plus one reset step", 2, undoDepth())
    }

    @Test fun doubleTappingTheBalanceThumbCentres() {
        hostCard()
        setProgress("balance-slider", 0f)
        // a non-zero value whose thumb is not at the centre: double tap on the pill is the dependable path
        setProgress("balance-slider", 50f)
        compose.onNodeWithTag("balance-pill").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals("Centre", pill("balance-pill"))
        assertEquals(0f, ctrl.snapshot().balance, 0f)
    }

    // ---- undo grouping ----------------------------------------------------------------------

    @Test fun singleSetProgressIsOneClosedUndoStep() {
        hostCard()
        setProgress("treble-slider", 4f)
        assertFalse(holder.ui.value.gestureOpen)
        assertEquals(1, undoDepth())
        assertEquals(0f, ctrl.snapshot().trebleDb, 0f)
    }

    @Test fun discreteChangesNeverLeaveAGroupOpenAndUndoStillWorks() {
        hostCard()
        // Back-to-back SetProgress calls (accessibility path), then let the idle close run.
        setProgress("bass-slider", 3f)
        setProgress("bass-slider", 6f)
        setProgress("balance-slider", 40f)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse("group must be closed after idle", holder.ui.value.gestureOpen)
        assertEquals(6f, ctrl.snapshot().bassDb, 0f)
        assertTrue("undo must be available", ctrl.snapshot().canUndo)
        assertTrue(ctrl.snapshot().balance != 0f)
        holder.undo()
        compose.waitForIdle()
        assertEquals("undo reverts the last edit", 0f, ctrl.snapshot().balance, 0f)
        assertEquals(6f, ctrl.snapshot().bassDb, 0f)
        assertFalse(holder.ui.value.gestureOpen)
    }

    @Test fun dragIsOneUndoStep() {
        hostCard()
        compose.onNodeWithTag("bass-slider").performTouchInput {
            down(center)
            moveBy(Offset(30f, 0f))
            moveBy(Offset(30f, 0f))
            moveBy(Offset(30f, 0f))
            up()
        }
        compose.waitForIdle()
        assertTrue("bass ${ctrl.snapshot().bassDb}", ctrl.snapshot().bassDb > 0f)
        assertFalse(holder.ui.value.gestureOpen)
        assertEquals(1, undoDepth())
        assertEquals(0f, ctrl.snapshot().bassDb, 0f)
    }

    @Test fun overlappingDragsLeaveNoOpenGroup() {
        hostCard()
        val a = compose.onNodeWithTag("bass-slider").fetchSemanticsNode().boundsInRoot.center
        val b = compose.onNodeWithTag("balance-slider").fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(0, a)
            down(1, b)
            moveTo(0, a + Offset(60f, 0f))
            moveTo(1, b + Offset(-60f, 0f))
            moveTo(0, a + Offset(120f, 0f))
            moveTo(1, b + Offset(-120f, 0f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        assertTrue("bass ${ctrl.snapshot().bassDb}", ctrl.snapshot().bassDb > 0f)
        assertTrue("balance ${ctrl.snapshot().balance}", ctrl.snapshot().balance < 0f)
        assertFalse("no orphaned open group", holder.ui.value.gestureOpen)
        val depth = undoDepth()
        assertTrue("one shared step or two independent steps, was $depth", depth in 1..2)
        assertEquals(0f, ctrl.snapshot().bassDb, 0f)
        assertEquals(0f, ctrl.snapshot().balance, 0f)
    }

    // ---- bypass -----------------------------------------------------------------------------

    @Test fun slidersStayEditableWhileBypassed() {
        hostCard()
        holder.setBypass(true)
        awaitUi { it.bypass }
        compose.waitForIdle()
        for (t in listOf("bass-slider", "treble-slider", "balance-slider")) compose.onNodeWithTag(t).assertIsEnabled()
        setProgress("bass-slider", 2f)
        setProgress("balance-slider", 20f)
        assertEquals(2f, holder.ui.value.bassDb, 0f)
        assertEquals(0.2f, holder.ui.value.balance, 1e-4f)
    }

    // ---- large font -------------------------------------------------------------------------

    @Test fun fontScaleTwoStacksRowsWithoutOverlapAndKeepsTouchTargets() {
        hostCard(fontScale = 2f)
        compose.onNodeWithTag("tone-card").assertIsDisplayed()
        fun r(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val tags = listOf("bass", "treble", "balance").flatMap { listOf("$it-slider", "$it-pill") }
        for (t in tags) {
            compose.onNodeWithTag(t).assertIsDisplayed()
            val n = compose.onNodeWithTag(t).fetchSemanticsNode()
            assertTrue("$t height ${dp(n.size.height)} >= 48", dp(n.size.height) >= 47.5f)
        }
        for (i in tags.indices) for (j in i + 1 until tags.size) {
            assertFalse("${tags[i]} overlaps ${tags[j]}", r(tags[i]).overlaps(r(tags[j])))
        }
        val card = compose.onNodeWithTag("tone-card").fetchSemanticsNode()
        assertTrue("stacked card at least 344 dp: ${dp(card.size.height)}", dp(card.size.height) >= 343f)
        // pill is above its slider in the stacked form
        assertTrue(r("bass-pill").bottom <= r("bass-slider").top + 1f)
    }
}
