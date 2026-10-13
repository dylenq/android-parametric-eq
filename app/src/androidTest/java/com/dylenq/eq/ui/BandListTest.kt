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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.layout.boundsInRoot
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

    // ---- numeric fields ---------------------------------------------------------------------------

    private fun fieldText(tag: String): String =
        tag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    private fun type(tag: String, text: String) {
        tag(tag).performClick()
        tag(tag).performTextReplacement(text)
        compose.waitForIdle()
    }

    private fun ime(tag: String) {
        tag(tag).performImeAction()
        compose.waitForIdle()
    }

    private var setupSteps = 0

    /** Test setup through the controller; it is an undo step of its own, which [undoSteps] discounts. */
    private fun setBand(i: Int, b: Band) {
        controller.edit { it.setBand(i.toUInt(), b) }
        setupSteps++
    }

    /** Undo steps made by the UI under test (setup steps excluded). Undoes everything. */
    private fun undoSteps(): Int = undoDepth() - setupSteps

    private fun openRow(i: Int) {
        holder.selectBand(i)
        compose.waitForIdle()
        tag("band-expanded-$i").assertExists()
    }

    private fun awaitOverlayPeakNear(hz: Float) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val c = holder.ui.value.curve
            val o = c?.selectedBandDb
            if (o != null) {
                var k = 0
                for (j in o.indices) if (o[j] > o[k]) k = j
                if (kotlin.math.abs(c.freqsHz[k] / hz - 1f) < 0.1f) return
            }
            Thread.sleep(50)
        }
        throw AssertionError("the selected band's overlay never peaked near $hz Hz")
    }

    @Test fun typedFrequencyUpdatesTheCurveAndMovesFocusOnImeNext() {
        setBand(2, Band(FilterKind.PEAK, 1000f, 6f, 1f, true))
        hostList()
        openRow(2)
        type("band-field-freq-2", "4000")
        assertEquals(4000f, bands()[2].freq, 0f)
        awaitOverlayPeakNear(4000f)
        ime("band-field-freq-2")
        tag("band-field-gain-2").assertIsFocused()
        assertEquals("4000", fieldText("band-field-freq-2"))
    }

    @Test fun validTextAppliesLiveBeforeAnyCommit() {
        hostList()
        openRow(2)
        type("band-field-freq-2", "1.5k")
        assertEquals(1500f, bands()[2].freq, 0f)
        type("band-field-gain-2", "+3 dB")
        assertEquals(3f, bands()[2].gainDb, 0f)
        type("band-field-q-2", ".71")
        assertEquals(0.71f, bands()[2].q, 0f)
        type("band-field-gain-2", "\u22122,5")
        assertEquals(-2.5f, bands()[2].gainDb, 0f)
        type("band-field-freq-2", "1,5 kHz")
        assertEquals(1500f, bands()[2].freq, 0f)
    }

    @Test fun clampedFrequencyShowsTheLimitTheMessageAndAnErrorThatClearsOnTheNextKey() {
        hostList()
        openRow(2)
        val before = bands()[2].freq
        type("band-field-freq-2", "99999")
        assertEquals("out of range text is not applied before commit", before, bands()[2].freq, 0f)
        ime("band-field-freq-2")
        assertEquals(20000f, bands()[2].freq, 0f)
        assertEquals("20000", fieldText("band-field-freq-2"))
        tag("band-field-message-2").assertTextEquals("Frequency limited to 20 Hz to 20 kHz.")
        val cfg = tag("band-field-freq-2").fetchSemanticsNode().config
        assertEquals("Frequency limited to 20 Hz to 20 kHz.", cfg[SemanticsProperties.Error])
        assertEquals(androidx.compose.ui.semantics.LiveRegionMode.Polite, tag("band-field-message-2").fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        type("band-field-freq-2", "2000")
        compose.onAllNodes(hasTestTag("band-field-message-2")).assertCountEquals(0)
        assertFalse(SemanticsProperties.Error in tag("band-field-freq-2").fetchSemanticsNode().config)
    }

    @Test fun gainAndQClampWithTheirOwnMessages() {
        hostList()
        openRow(1)
        type("band-field-gain-1", "-99")
        ime("band-field-gain-1")
        assertEquals(-24f, bands()[1].gainDb, 0f)
        assertEquals("-24", fieldText("band-field-gain-1"))
        tag("band-field-message-1").assertTextEquals("Gain limited to \u221224 dB to +24 dB.")
        type("band-field-q-1", "50")
        ime("band-field-q-1")
        assertEquals(18f, bands()[1].q, 0f)
        assertEquals("18.00", fieldText("band-field-q-1"))
        tag("band-field-message-1").assertTextEquals("Q limited to 0.1 to 18.")
    }

    @Test fun unparsableTextRevertsAndSaysEnterANumber() {
        hostList()
        openRow(2)
        val before = bands()[2]
        val shown = fieldText("band-field-freq-2")
        type("band-field-freq-2", "abc")
        assertEquals(before, bands()[2])
        ime("band-field-freq-2")
        assertEquals(before, bands()[2])
        assertEquals(shown, fieldText("band-field-freq-2"))
        tag("band-field-message-2").assertTextEquals("Enter a number.")
        assertEquals(0, undoDepth())
    }

    @Test fun hostileStringsAreNeverApplied() {
        hostList()
        openRow(2)
        val before = bands()[2]
        for (s in listOf("NaN", "Infinity", "0x1p3", "1e400", "1.5f")) {
            type("band-field-q-2", s)
            assertEquals("'$s' must not reach the core", before, bands()[2])
        }
    }

    @Test fun overLongTextIsRejectedByTheField() {
        hostList()
        openRow(2)
        type("band-field-freq-2", "1".repeat(16))
        val ok = fieldText("band-field-freq-2")
        type("band-field-freq-2", "1".repeat(17))
        assertEquals("a 17th character is refused", ok, fieldText("band-field-freq-2"))
    }

    @Test fun focusAndBlurWithUnchangedTextMakesNoStepAndNeverRewritesTheStoredValue() {
        setBand(2, Band(FilterKind.PEAK, 1234.5f, 1.25f, 1.2345f, true))
        hostList()
        openRow(2)
        val stored = bands()[2]
        assertEquals("1.23", fieldText("band-field-q-2"))
        tag("band-field-q-2").performClick()
        compose.waitForIdle()
        ime("band-field-q-2")
        tag("band-field-freq-2").performClick()
        compose.waitForIdle()
        ime("band-field-freq-2")
        ime("band-field-gain-2")
        ime("band-field-q-2")
        compose.waitForIdle()
        assertEquals("nothing was rewritten to its displayed precision", stored, bands()[2])
        assertFalse(holder.ui.value.gestureOpen)
        assertEquals(0, undoSteps())
    }

    @Test fun oneUndoStepPerFocusSessionAcrossFields() {
        hostList()
        openRow(2)
        val before = bands()[2]
        type("band-field-freq-2", "2500")
        assertTrue("undo is inert while a field session is open", holder.ui.value.gestureOpen)
        ime("band-field-freq-2")
        compose.onNodeWithTag("band-field-gain-2").performTextReplacement("4.5")
        compose.waitForIdle()
        ime("band-field-gain-2")
        compose.onNodeWithTag("band-field-q-2").performTextReplacement("2")
        compose.waitForIdle()
        ime("band-field-q-2")
        compose.waitForIdle()
        val after = bands()[2]
        assertEquals(Band(before.kind, 2500f, 4.5f, 2f, true), after)
        assertFalse(holder.ui.value.gestureOpen)
        assertEquals("three fields, one session, one step", 1, undoDepth())
        assertEquals(before, bands()[2])
    }

    @Test fun twoSeparateSessionsAreTwoSteps() {
        hostList()
        openRow(2)
        type("band-field-freq-2", "2500")
        ime("band-field-freq-2")
        tag("band-field-gain-2").assertIsFocused()
        // Leave the row's fields entirely: focus cleared, then a second session.
        compose.runOnUiThread { }
        ime("band-field-gain-2")
        ime("band-field-q-2")
        compose.waitForIdle()
        type("band-field-q-2", "3")
        ime("band-field-q-2")
        compose.waitForIdle()
        assertEquals(2, undoDepth())
    }

    @Test fun collapsingTheRowCommitsAClampedDraftInTheSameStep() {
        hostList()
        openRow(2)
        type("band-field-freq-2", "99999")
        tag("band-row-2").performClick()
        compose.waitForIdle()
        assertEquals(20000f, bands()[2].freq, 0f)
        assertFalse(holder.ui.value.gestureOpen)
        assertEquals(1, undoDepth())
    }

    @Test fun typedTextIsNotOverwrittenWhileFocusedButFollowsTheModelOtherwise() {
        hostList()
        openRow(2)
        tag("band-field-freq-2").performClick()
        compose.onNodeWithTag("band-field-freq-2").performTextReplacement("1.5")
        compose.waitForIdle()
        holder.nudgeBandGain(2, 0.5f)
        compose.waitForIdle()
        assertEquals("the typed text wins while the field is focused", "1.5", fieldText("band-field-freq-2"))
        assertEquals(NumericField.formatGain(bands()[2].gainDb), fieldText("band-field-gain-2"))
        ime("band-field-freq-2")
        compose.waitForIdle()
        // Now unfocused: the model value is shown.
        val moved = bands()[2].freq
        assertEquals(NumericField.formatFrequency(moved), fieldText("band-field-freq-2"))
    }

    @Test fun passKindGainFieldIsDisabledAndSaysWhy() {
        setBand(2, Band(FilterKind.HIGH_PASS, 80f, 0f, 0.71f, true))
        hostList()
        openRow(2)
        tag("band-field-gain-2").assertIsNotEnabled()
        assertEquals("-", fieldText("band-field-gain-2"))
        assertEquals("No gain for this filter type", tag("band-field-gain-2").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        tag("band-sign-2").assertIsNotEnabled()
        // Next from Frequency skips the disabled Gain field.
        tag("band-field-freq-2").performClick()
        ime("band-field-freq-2")
        tag("band-field-q-2").assertIsFocused()
    }

    @Test fun signButtonFlipsTheGainOneStepEachAndIgnoresZero() {
        setBand(2, Band(FilterKind.PEAK, 1000f, 3.5f, 1f, true))
        hostList()
        openRow(2)
        tag("band-sign-2").assertIsEnabled()
        assertEquals(listOf("Change sign of gain"), tag("band-sign-2").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        tag("band-sign-2").performClick()
        compose.waitForIdle()
        assertEquals(-3.5f, bands()[2].gainDb, 0f)
        assertEquals("-3.5", fieldText("band-field-gain-2"))
        tag("band-sign-2").performClick()
        compose.waitForIdle()
        assertEquals(3.5f, bands()[2].gainDb, 0f)
        assertEquals(2, undoSteps())
        setupSteps = 0 // undoSteps() undid everything, setup included
        // Exactly 0 dB has no sign to flip: the holder writes nothing (no extra undo step).
        setBand(2, Band(FilterKind.PEAK, 1000f, 0f, 1f, true))
        holder.flipBandGainSign(2)
        assertEquals(0f, bands()[2].gainDb, 0f)
        assertEquals(0, undoSteps())
    }

    @Test fun imeActionsAndDescriptionsMatchTheSpec() {
        hostList()
        openRow(2)
        assertEquals(androidx.compose.ui.text.input.ImeAction.Next, tag("band-field-freq-2").fetchSemanticsNode().config[SemanticsProperties.ImeAction])
        assertEquals(androidx.compose.ui.text.input.ImeAction.Next, tag("band-field-gain-2").fetchSemanticsNode().config[SemanticsProperties.ImeAction])
        assertEquals(androidx.compose.ui.text.input.ImeAction.Done, tag("band-field-q-2").fetchSemanticsNode().config[SemanticsProperties.ImeAction])
        assertEquals(listOf("Band 3 frequency, hertz"), tag("band-field-freq-2").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals(listOf("Band 3 gain, decibels"), tag("band-field-gain-2").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
        assertEquals(listOf("Band 3 Q"), tag("band-field-q-2").fetchSemanticsNode().config[SemanticsProperties.ContentDescription])
    }

    @Test fun doneOnQClearsFocus() {
        hostList()
        openRow(2)
        tag("band-field-q-2").performClick()
        tag("band-field-q-2").assertIsFocused()
        ime("band-field-q-2")
        tag("band-field-q-2").assertIsNotFocused()
    }

    @Test fun fieldsStackAtLargeFontScale() {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { holder.messages.collect { messages.add(it) } }
        rule.host {
            val ui by holder.ui.collectAsStateWithLifecycle()
            val actions = androidx.compose.runtime.remember { holder.bandListActions() }
            val d = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.5f),
            ) {
                Box(Modifier.fillMaxSize().padding(8.dp)) { ParametricBandList({ ui }, actions, false) }
            }
        }
        openRow(2)
        val f = tag("band-field-freq-2").fetchSemanticsNode().boundsInRoot
        val g = tag("band-field-gain-2").fetchSemanticsNode().boundsInRoot
        val q = tag("band-field-q-2").fetchSemanticsNode().boundsInRoot
        assertEquals("stacked: same left edge", f.left, g.left, 1f)
        assertEquals(g.left, q.left, 1f)
        assertTrue("stacked: gain below frequency, Q below gain", g.top >= f.bottom - 1f && q.top >= g.bottom - 1f)
    }

    @Test fun fieldsSitSideBySideAtNormalFontScale() {
        hostList()
        openRow(2)
        val f = tag("band-field-freq-2").fetchSemanticsNode().boundsInRoot
        val g = tag("band-field-gain-2").fetchSemanticsNode().boundsInRoot
        val q = tag("band-field-q-2").fetchSemanticsNode().boundsInRoot
        assertEquals(f.top, g.top, 1f)
        assertEquals(g.top, q.top, 1f)
        assertTrue(f.right <= g.left && g.right <= q.left)
        assertEquals("36 / 36 / 28 split", 0.36f / 0.28f, f.width / q.width, 0.05f)
    }

    @Test fun addedBandsTypedFrequencyShowsOnTheCurve() {
        hostList()
        tag("band-add").performClick()
        compose.waitForIdle()
        val i = bands().size - 1
        type("band-field-gain-$i", "9")
        type("band-field-freq-$i", "5000")
        assertEquals(5000f, bands()[i].freq, 0f)
        assertEquals(9f, bands()[i].gainDb, 0f)
        awaitOverlayPeakNear(5000f)
    }
}
