package com.dylenq.eq.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.height
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dylenq.eq.core.FilterKind
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The long-press menu of a node (PEQ-04). Long-press recipe from the Wave 0 probe: `down()`, advance the
 * main clock past the long-press timeout, wait for idle and assert, then `up()` in a second call.
 */
@RunWith(AndroidJUnit4::class)
class NodeMenuTest : CurveTestBase() {
    private val layer get() = rule.compose.onNodeWithTag("curve-interaction")
    private val messages = CopyOnWriteArrayList<UiMessage>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After fun stopCollecting() { scope.cancel() }

    private fun collectMessages() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { holder.messages.collect { messages.add(it) } }
    }

    private fun openMenu(index: Int) {
        val p = node(index)
        layer.performTouchInput { down(p) }
        rule.compose.mainClock.advanceTimeBy(longPressMs + 100)
        rule.compose.waitForIdle()
        rule.compose.onNodeWithTag("peq-menu").assertIsDisplayed()
    }

    private fun release() {
        layer.performTouchInput { up() }
        rule.compose.waitForIdle()
    }

    private fun menuClosed() = rule.compose.onAllNodesWithTag("peq-menu").assertCountEquals(0)

    @Test fun longPressOpensTheMenuWithItemsInOrderAndSelectsTheBand() {
        hostCard()
        openMenu(3)
        assertEquals(3, holder.ui.value.selectedBand)
        val tags = listOf(
            "peq-menu-kind-peak", "peq-menu-kind-low_shelf", "peq-menu-kind-high_shelf",
            "peq-menu-kind-low_pass", "peq-menu-kind-high_pass", "peq-menu-enable", "peq-menu-delete",
        )
        val texts = listOf("Peak", "Low shelf", "High shelf", "Low pass", "High pass", "Disable", "Delete band")
        val tops = tags.zip(texts).map { (tag, text) ->
            rule.compose.onNodeWithTag(tag).assertIsDisplayed()
            rule.compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
            rule.compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
        }
        assertEquals("items run top to bottom in the UI-SPEC order", tops.sorted(), tops)
        val h = rule.compose.onNodeWithTag("peq-menu-kind-peak").getUnclippedBoundsInRoot().height.value
        assertTrue("items are at least 48 dp tall: $h", h >= 48f - 0.5f)
        release()
        assertFalse("opening the menu is not an edit", controller.snapshot().canUndo)
    }

    @Test fun theCurrentKindHasTheCheck() {
        hostCard()
        openMenu(0) // the default band 1 is a Low shelf
        rule.compose.onAllNodesWithTag("peq-menu-check", useUnmergedTree = true).assertCountEquals(1)
        rule.compose.onNode(
            hasTestTag("peq-menu-check") and hasAnyAncestor(hasTestTag("peq-menu-kind-low_shelf")),
            useUnmergedTree = true,
        ).assertExists()
        release()
    }

    @Test fun choosingAKindChangesOnlyTheKindAsOneStepAndClosesTheMenu() {
        hostCard()
        val before = controller.snapshot().bands[3]
        openMenu(3)
        release()
        rule.compose.onNodeWithTag("peq-menu-kind-high_pass").performClick()
        rule.compose.waitForIdle()
        val after = controller.snapshot().bands[3]
        assertEquals(FilterKind.HIGH_PASS, after.kind)
        assertEquals(before.freq, after.freq, 0f)
        assertEquals(before.q, after.q, 0f)
        assertEquals(before.gainDb, after.gainDb, 0f)
        menuClosed()
        assertEquals(1, undoDepth())
    }

    @Test fun choosingTheCurrentKindMakesNoStep() {
        hostCard()
        openMenu(3)
        release()
        rule.compose.onNodeWithTag("peq-menu-kind-peak").performClick()
        rule.compose.waitForIdle()
        menuClosed()
        assertEquals(0, undoDepth())
    }

    @Test fun disableThenEnableToggleTheBandAndTheLabel() {
        hostCard()
        openMenu(2)
        release()
        rule.compose.onNodeWithTag("peq-menu-enable").performClick()
        rule.compose.waitForIdle()
        assertFalse(controller.snapshot().bands[2].enabled)
        assertEquals("a disabled band stays in the list", 7, controller.snapshot().bands.size)
        openMenu(2)
        release()
        rule.compose.onNode(hasText("Enable") and hasAnyAncestor(hasTestTag("peq-menu-enable")), useUnmergedTree = true).assertIsDisplayed()
        rule.compose.onNodeWithTag("peq-menu-enable").performClick()
        rule.compose.waitForIdle()
        assertTrue(controller.snapshot().bands[2].enabled)
        assertEquals(2, undoDepth())
    }

    @Test fun deleteRemovesTheBandAndPublishesTheUndoableMessage() {
        hostCard()
        collectMessages()
        openMenu(3)
        release()
        rule.compose.onNodeWithTag("peq-menu-delete").performClick()
        rule.compose.waitForIdle()
        assertEquals(6, controller.snapshot().bands.size)
        assertEquals(listOf(UiMessage("Band 4 deleted", undoable = true)), messages.toList())
        menuClosed()
        holder.undo()
        assertEquals(7, controller.snapshot().bands.size)
    }

    @Test fun dismissingWithBackChangesNothing() {
        hostCard()
        val before = controller.snapshot().bands
        openMenu(3)
        release()
        Espresso.pressBack()
        rule.compose.waitForIdle()
        menuClosed()
        assertEquals(before, controller.snapshot().bands)
        assertFalse(controller.snapshot().canUndo)
    }

    @Test fun theBandStaysSelectedWhileTheMenuIsOpen() {
        hostCard()
        openMenu(5)
        release()
        assertEquals(5, holder.ui.value.selectedBand)
        rule.compose.onNodeWithText("Delete band").assertIsDisplayed()
    }

    @Test fun aBandThatDisappearsClosesTheMenu() {
        hostCard()
        openMenu(3)
        release()
        holder.removeBand(3)
        rule.compose.waitForIdle()
        menuClosed()
    }
}
