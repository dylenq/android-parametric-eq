package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.EqComposeRule
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The accordion band list hosted on its own against the real holder (PARAMETRIC mode). */
@RunWith(AndroidJUnit4::class)
class BandListTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val controller get() = rule.runtime.controller
    private val compose get() = rule.compose
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
    private val messages = CopyOnWriteArrayList<UiMessage>()

    @Before fun parametricMode() {
        controller.edit { it.setMode(EqMode.PARAMETRIC) }
    }

    @After fun tearDown() {
        scope.cancel()
    }

    private fun bands() = controller.snapshot().bands

    /** Undoes until nothing is left and counts the steps. */
    private fun undoDepth(): Int {
        var n = 0
        while (controller.snapshot().canUndo && n < 50) {
            holder.undo()
            n++
        }
        return n
    }

    private fun hostList(pinchHintSeen: Boolean = false) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { holder.messages.collect { messages.add(it) } }
        rule.host {
            val ui by holder.ui.collectAsStateWithLifecycle()
            val actions = androidx.compose.runtime.remember { holder.bandListActions() }
            Box(Modifier.fillMaxSize().padding(8.dp)) {
                ParametricBandList({ ui }, actions, pinchHintSeen)
            }
        }
    }

    private fun fillTo16() {
        while (bands().size < 16) holder.addBandFromList()
        // Adding selects the new band, which scrolls the list to it; the header tests want the top.
        holder.selectBand(null)
    }

    private fun tag(t: String) = compose.onNodeWithTag(t)

    private fun expandedCount() = compose.onAllNodes(hasTestTag("band-expanded-0").or(hasTestTag("band-expanded-1"))
        .or(hasTestTag("band-expanded-2")).or(hasTestTag("band-expanded-3")).or(hasTestTag("band-expanded-4"))
        .or(hasTestTag("band-expanded-5")).or(hasTestTag("band-expanded-6"))).fetchSemanticsNodes().size

    // ---- header, hint, empty state ----------------------------------------------------------------

    @Test fun headerShowsTheCountAndBothActions() {
        hostList()
        tag("band-count").assertTextEquals("7 of 16")
        compose.onNodeWithText("Bands").assertIsDisplayed()
        tag("band-remove-all").assertIsEnabled()
        tag("band-add").assertIsEnabled()
        tag("band-list-hint").assertTextEquals(BAND_LIST_HINT_WITH_PINCH)
    }

    @Test fun hintDropsThePinchSentenceOnceSeen() {
        hostList(pinchHintSeen = true)
        tag("band-list-hint").assertTextEquals("Drag a point to move it. Long-press for more.")
    }

    @Test fun emptyStateShowsTheCardAndHidesTheHeaderAddButton() {
        holder.removeAllBands()
        hostList()
        tag("band-count").assertTextEquals("0 of 16")
        compose.onNodeWithText("No bands yet").assertIsDisplayed()
        compose.onNodeWithText("Double-tap the curve to add a band, or use Add band. Each band shapes one part of the sound.").assertIsDisplayed()
        tag("band-add-empty").assertIsDisplayed()
        compose.onAllNodes(hasTestTag("band-add")).assertCountEquals(0)
        compose.onAllNodes(hasTestTag("band-list-hint")).assertCountEquals(0)
        tag("band-remove-all").assertIsNotEnabled()
    }

    @Test fun addBandFromTheEmptyStateAddsAPeakAt1kHz() {
        holder.removeAllBands()
        hostList()
        tag("band-add-empty").performClick()
        compose.waitForIdle()
        assertEquals(1, bands().size)
        assertEquals(Band(FilterKind.PEAK, 1000f, 0f, 1f, true), bands()[0])
        compose.onAllNodes(hasTestTag("band-list-empty")).assertCountEquals(0)
        tag("band-expanded-0").assertIsDisplayed()
    }

    @Test fun atSixteenBandsAddIsDisabledAndTheHintSaysSo() {
        fillTo16()
        hostList()
        tag("band-count").assertTextEquals("16 of 16")
        tag("band-add").assertIsNotEnabled()
        tag("band-list-hint").assertTextEquals("All 16 bands are in use. Remove one to add another.")
        val before = bands()
        tag("band-add").performClick()
        compose.waitForIdle()
        assertEquals(before, bands())
    }

    // ---- collapsed rows ---------------------------------------------------------------------------

    @Test fun collapsedRowShowsKindAndSummary() {
        controller.edit { it.setBand(0u, Band(FilterKind.PEAK, 63f, 5f, 0.71f, true)) }
        controller.edit { it.setBand(1u, Band(FilterKind.HIGH_PASS, 8000f, 0f, 0.71f, true)) }
        hostList()
        compose.onNodeWithText("63 Hz · +5.0 dB · Q 0.71", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("8 kHz · Q 0.71", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Low shelf", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun rowSemanticsAreOneNodeWithStateAndAClickAction() {
        controller.edit { it.setBand(2u, Band(FilterKind.PEAK, 1250f, 3.5f, 1f, true)) }
        hostList()
        val cfg = tag("band-row-2").fetchSemanticsNode().config
        assertEquals(listOf("Band 3, Peak, 1.25 kilohertz, plus 3.5 decibels, Q 1.00"), cfg[SemanticsProperties.ContentDescription])
        assertEquals("Collapsed", cfg[SemanticsProperties.StateDescription])
        assertTrue(SemanticsActions.OnClick in cfg)
        tag("band-row-2").performClick()
        compose.waitForIdle()
        assertEquals("Expanded", tag("band-row-2").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test fun switchIsSeparateAndTogglesEnabledAsOneUndoStep() {
        hostList()
        val cfg = tag("band-switch-1").fetchSemanticsNode().config
        assertEquals(listOf("Band 2 enabled"), cfg[SemanticsProperties.ContentDescription])
        tag("band-switch-1").performClick()
        compose.waitForIdle()
        assertFalse(bands()[1].enabled)
        assertEquals("tapping the switch does not select or expand", null, holder.ui.value.selectedBand)
        assertEquals(1, undoDepth())
        assertTrue(bands()[1].enabled)
    }

    @Test fun disabledRowDescriptionSaysDisabled() {
        holder.setBandEnabled(0, false)
        hostList()
        val d = tag("band-row-0").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        assertTrue(d, d.endsWith(", disabled"))
    }

    // ---- accordion --------------------------------------------------------------------------------

    @Test fun onlyTheSelectedRowIsExpanded() {
        hostList()
        assertEquals(0, expandedCount())
        tag("band-row-2").performClick()
        compose.waitForIdle()
        assertEquals(2, holder.ui.value.selectedBand)
        assertEquals(1, expandedCount())
        tag("band-expanded-2").assertExists()
        tag("band-row-4").performClick()
        compose.waitForIdle()
        assertEquals(4, holder.ui.value.selectedBand)
        assertEquals(1, expandedCount())
        tag("band-expanded-4").assertExists()
    }

    @Test fun tappingTheExpandedHeaderCollapsesAndClearsSelection() {
        hostList()
        tag("band-row-3").performClick()
        compose.waitForIdle()
        tag("band-expanded-3").assertExists()
        tag("band-row-3").performClick()
        compose.waitForIdle()
        assertNull(holder.ui.value.selectedBand)
        assertEquals(0, expandedCount())
    }

    @Test fun selectingFromTheCurveExpandsTheRowAndScrollsItIntoView() {
        fillTo16()
        hostList()
        // Row 15 starts far below the fold of the emulator window.
        holder.selectBand(15)
        compose.waitForIdle()
        tag("band-expanded-15").assertIsDisplayed()
        tag("band-row-15").assertIsDisplayed()
    }

    // ---- kind, delete, add, remove all ------------------------------------------------------------

    @Test fun kindSelectorChangesTheKindInOneUndoStepAndMarksTheCurrent() {
        hostList()
        tag("band-row-2").performClick()
        compose.waitForIdle()
        val before = bands()[2]
        assertTrue(tag("band-kind-2").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first().endsWith("Peak"))
        tag("band-kind-2").performClick()
        compose.waitForIdle()
        tag("band-kind-item-low_shelf").performClick()
        compose.waitForIdle()
        assertEquals(FilterKind.LOW_SHELF, bands()[2].kind)
        assertEquals(before.freq, bands()[2].freq, 0f)
        assertEquals(before.q, bands()[2].q, 0f)
        assertEquals(1, undoDepth())
        assertEquals(before, bands()[2])
    }

    @Test fun choosingTheCurrentKindDoesNothing() {
        hostList()
        tag("band-row-2").performClick()
        compose.waitForIdle()
        tag("band-kind-2").performClick()
        compose.waitForIdle()
        tag("band-kind-item-peak").performClick()
        compose.waitForIdle()
        assertEquals(0, undoDepth())
    }

    @Test fun deleteBandRemovesItClearsSelectionAndOffersUndo() {
        hostList()
        tag("band-row-3").performClick()
        compose.waitForIdle()
        tag("band-delete-3").performClick()
        compose.waitForIdle()
        assertEquals(6, bands().size)
        assertNull(holder.ui.value.selectedBand)
        assertEquals(UiMessage("Band 4 deleted", undoable = true), messages.last())
        tag("band-count").assertTextEquals("6 of 16")
        assertEquals(1, undoDepth())
        assertEquals(7, bands().size)
    }

    @Test fun addBandPicksAFreeFrequencySelectsAndExpandsIt() {
        hostList()
        tag("band-add").performClick()
        compose.waitForIdle()
        assertEquals(8, bands().size)
        val added = bands()[7]
        assertEquals(FilterKind.PEAK, added.kind)
        assertEquals(1f, added.q, 0f)
        assertEquals(0f, added.gainDb, 0f)
        assertEquals(7, holder.ui.value.selectedBand)
        // It is a frequency from the fixed sequence and free (no enabled band within a third of an octave of it).
        assertTrue(ParametricMath.FREE_FREQUENCIES.any { it == added.freq })
        tag("band-expanded-7").assertExists()
        assertEquals(1, undoDepth())
    }

    @Test fun removeAllEmptiesTheListInOneStep() {
        hostList()
        tag("band-remove-all").performClick()
        compose.waitForIdle()
        assertEquals(0, bands().size)
        compose.onNodeWithText("No bands yet").assertIsDisplayed()
        assertEquals(UiMessage("All bands removed", undoable = true), messages.last())
        assertEquals(1, undoDepth())
        assertEquals(7, bands().size)
    }

    @Test fun footerRendersAfterTheLastRow() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { holder.messages.collect { messages.add(it) } }
        rule.host {
            val ui by holder.ui.collectAsStateWithLifecycle()
            val actions = androidx.compose.runtime.remember { holder.bandListActions() }
            Box(Modifier.fillMaxSize()) {
                ParametricBandList({ ui }, actions, false, footer = { androidx.compose.material3.Text("FOOTER", modifier = Modifier.padding(4.dp)) })
            }
        }
        tag("band-list").performScrollToNode(hasText("FOOTER"))
        compose.onNodeWithText("FOOTER").assertIsDisplayed()
    }
}
