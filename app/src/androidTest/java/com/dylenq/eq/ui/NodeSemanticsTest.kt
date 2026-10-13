package com.dylenq.eq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
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
 * The non-gesture routes (UI-SPEC Accessibility): one semantics child per band and the custom actions.
 * This drives the semantics tree the way TalkBack's action menu does; a real TalkBack run is unverified.
 */
@RunWith(AndroidJUnit4::class)
class NodeSemanticsTest : CurveTestBase() {
    private val messages = CopyOnWriteArrayList<UiMessage>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @After fun stopCollecting() { scope.cancel() }

    private fun nodeAt(i: Int) = rule.compose.onNodeWithTag("peq-node-$i")

    private fun SemanticsNodeInteraction.labels(): List<String> =
        fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label }

    private fun SemanticsNodeInteraction.run(label: String): Boolean {
        val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        var result = false
        rule.compose.runOnUiThread { result = action.action() }
        rule.compose.waitForIdle()
        return result
    }

    private fun SemanticsNodeInteraction.state() =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test fun oneChildPerBandInIndexOrderWithSpokenDescriptions() {
        hostCard()
        rule.compose.onAllNodesWithTag("peq-node-0").assertCountEquals(1)
        val bands = controller.snapshot().bands
        for (i in bands.indices) {
            rule.compose.onAllNodesWithTag("peq-node-$i").assertCountEquals(1)
            val cfg = nodeAt(i).fetchSemanticsNode().config
            assertEquals(NodeSpeech.contentDescription(i, bands[i]), cfg[SemanticsProperties.ContentDescription].single())
            assertEquals(Role.Button, cfg[SemanticsProperties.Role])
        }
        rule.compose.onAllNodesWithTag("peq-node-${bands.size}").assertCountEquals(0)
        assertEquals("Band 1, Low shelf", nodeAt(0).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        assertEquals("Band 4, Peak", nodeAt(3).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
    }

    @Test fun nodeBoundsAre48DpCentredOnTheNode() {
        hostCard()
        for (i in listOf(0, 3, 6)) {
            val b = nodeAt(i).getBoundsInRoot()
            assertEquals(48f, b.width.value, 0.6f)
            assertEquals(48f, b.height.value, 0.6f)
            val centre = nodeAt(i).fetchSemanticsNode().boundsInRoot.center
            val plotNode = rule.compose.onNodeWithTag("curve-interaction").fetchSemanticsNode().boundsInRoot
            val expected = node(i) + Offset(plotNode.left, plotNode.top)
            assertEquals("band $i x", expected.x, centre.x, 2f)
            assertEquals("band $i y", expected.y, centre.y, 2f)
        }
    }

    @Test fun stateDescriptionFollowsSelectionAndEdits() {
        hostCard()
        assertEquals("1 kilohertz, 0 decibels, Q 1.00", nodeAt(3).state())
        holder.selectBand(3)
        rule.compose.waitForIdle()
        assertEquals("1 kilohertz, 0 decibels, Q 1.00, selected", nodeAt(3).state())
        nodeAt(3).run("Increase gain")
        assertEquals("1 kilohertz, plus 0.5 decibels, Q 1.00, selected", nodeAt(3).state())
        holder.setBandEnabled(3, false)
        rule.compose.waitForIdle()
        assertEquals("1 kilohertz, plus 0.5 decibels, Q 1.00, disabled, selected", nodeAt(3).state())
    }

    @Test fun clickSelectsAndLongClickOpensTheMenu() {
        hostCard()
        nodeAt(2).performSemanticsAction(SemanticsActions.OnClick)
        rule.compose.waitForIdle()
        assertEquals(2, holder.ui.value.selectedBand)
        assertFalse("selecting is not an edit", controller.snapshot().canUndo)
        nodeAt(4).performSemanticsAction(SemanticsActions.OnLongClick)
        rule.compose.waitForIdle()
        rule.compose.onNodeWithTag("peq-menu").assertExists()
        assertEquals(4, holder.ui.value.selectedBand)
    }

    @Test fun customActionsForGainAndPassBands() {
        hostCard()
        val expected = listOf(
            "Increase gain", "Decrease gain", "Move up in frequency", "Move down in frequency",
            "Increase Q", "Decrease Q", "Change type", "Disable", "Delete band",
        )
        assertEquals(expected, nodeAt(3).labels())
        holder.setBandKind(3, FilterKind.HIGH_PASS)
        rule.compose.waitForIdle()
        assertEquals(expected.filterNot { it.endsWith("gain") }, nodeAt(3).labels())
        holder.setBandEnabled(3, false)
        rule.compose.waitForIdle()
        assertTrue("Enable" in nodeAt(3).labels() && "Disable" !in nodeAt(3).labels())
    }

    @Test fun gainActionsStepHalfDbEachAsOneUndoStep() {
        hostCard()
        assertTrue(nodeAt(3).run("Increase gain"))
        assertEquals(0.5f, controller.snapshot().bands[3].gainDb, 0f)
        assertTrue(nodeAt(3).run("Decrease gain"))
        assertTrue(nodeAt(3).run("Decrease gain"))
        assertEquals(-0.5f, controller.snapshot().bands[3].gainDb, 0f)
        assertEquals(3, undoDepth())
    }

    @Test fun frequencyActionsMoveByATwelfthOfAnOctave() {
        hostCard()
        nodeAt(3).run("Move up in frequency")
        assertEquals(1060f, controller.snapshot().bands[3].freq, 0f)
        assertEquals(1, undoDepth())
        nodeAt(3).run("Move down in frequency")
        assertEquals(944f, controller.snapshot().bands[3].freq, 0f)
        assertEquals(1, undoDepth())
    }

    @Test fun qActionsScaleByOnePointOneTwo() {
        hostCard()
        nodeAt(3).run("Increase Q")
        assertEquals(1.12f, controller.snapshot().bands[3].q, 0.001f)
        assertEquals(1, undoDepth())
        nodeAt(3).run("Decrease Q")
        assertEquals(0.89f, controller.snapshot().bands[3].q, 0.001f)
        assertEquals(1, undoDepth())
    }

    @Test fun changeTypeOpensTheMenuAndDisableEnableAndDeleteWork() {
        hostCard()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { holder.messages.collect { messages.add(it) } }
        nodeAt(1).run("Change type")
        rule.compose.onNodeWithTag("peq-menu").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        rule.compose.waitForIdle()
        nodeAt(1).run("Disable")
        assertFalse(controller.snapshot().bands[1].enabled)
        nodeAt(1).run("Enable")
        assertTrue(controller.snapshot().bands[1].enabled)
        assertEquals(2, undoDepth())
        nodeAt(1).run("Delete band")
        assertEquals(6, controller.snapshot().bands.size)
        assertEquals(listOf(UiMessage("Band 2 deleted", undoable = true)), messages.toList())
        rule.compose.onAllNodesWithTag("peq-node-6").assertCountEquals(0)
        assertEquals(1, undoDepth())
    }

    @Test fun plotAddBandActionAddsAtTheFreeFrequencyAndStopsAtSixteen() {
        hostCard()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { holder.messages.collect { messages.add(it) } }
        val plot = rule.compose.onNodeWithTag("peq-plot")
        val add = plot.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Add band" }
        rule.compose.runOnUiThread { assertTrue(add.action()) }
        rule.compose.waitForIdle()
        assertEquals(8, controller.snapshot().bands.size)
        assertEquals(7, holder.ui.value.selectedBand)
        rule.compose.onAllNodesWithTag("peq-node-7").assertCountEquals(1)
        while (controller.snapshot().bands.size < 16) holder.addBandFromList()
        rule.compose.waitForIdle()
        messages.clear()
        rule.compose.runOnUiThread { add.action() }
        rule.compose.waitForIdle()
        assertEquals(16, controller.snapshot().bands.size)
        assertEquals("All 16 bands are in use. Remove one to add another.", messages.single().text)
    }

    @Test fun theOverlayDoesNotSwallowTouches() {
        hostCard()
        val p = node(3)
        rule.compose.onNodeWithTag("curve-interaction").performTouchInput { down(p); up() }
        rule.compose.waitForIdle()
        assertEquals("a touch on a node reaches the gesture machine through the overlay", 3, holder.ui.value.selectedBand)
    }
}
