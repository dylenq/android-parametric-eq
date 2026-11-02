package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A band row keeps its own state (the open Kind menu, the field message) and never hands it to the band
 * that takes its place after a delete, an undo or a redo. The band list is hosted on its own against the
 * real holder, in Parametric mode.
 */
@RunWith(AndroidJUnit4::class)
class BandRowIdentityTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val controller get() = rule.runtime.controller
    private val compose get() = rule.compose

    @Before fun parametricMode() {
        controller.edit { it.setMode(EqMode.PARAMETRIC) }
    }

    private fun hostList() {
        rule.host {
            val ui by holder.ui.collectAsStateWithLifecycle()
            val actions = androidx.compose.runtime.remember { holder.bandListActions() }
            Box(Modifier.fillMaxSize().padding(8.dp)) {
                ParametricBandList({ ui }, actions, pinchHintSeen = false)
            }
        }
    }

    private fun tag(t: String) = compose.onNodeWithTag(t)

    /** Matches by prefix: hasTestTag matches exactly, and the menu tag carries the row index. */
    private val kindMenu = SemanticsMatcher("test tag starts with band-kind-menu-") { node ->
        node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("band-kind-menu-") == true
    }

    private fun openKindMenus() = compose.onAllNodes(kindMenu).fetchSemanticsNodes().size

    private fun select(i: Int) {
        holder.selectBand(i)
        compose.waitForIdle()
        tag("band-expanded-$i").assertExists()
    }

    private fun openKindMenu(i: Int) {
        select(i)
        tag("band-kind-$i").performClick()
        compose.waitForIdle()
        assertEquals("the Kind menu of row $i should be open", 1, openKindMenus())
    }

    /**
     * Runs [change] with the main clock held and lets two frames pass, so the 200 ms accordion exit it may
     * start cannot finish. [whileHeld] then looks at those first frames: a menu still there is stuck open on
     * a row that has not finished leaving.
     */
    private fun inTheFirstFramesAfter(change: () -> Unit, whileHeld: () -> Unit) {
        compose.mainClock.autoAdvance = false
        try {
            change()
            // The state flow reaches Compose through the main looper; let that land before counting frames.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            repeat(2) { compose.mainClock.advanceTimeByFrame() }
            // Proof that the change reached the tree, so "no menu" below cannot mean "nothing happened yet".
            tag("band-count").assertTextEquals("${holder.ui.value.bands.size} of 16")
            whileHeld()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun deletingTheBandWhoseKindMenuIsOpenLeavesNoMenuOpen() {
        hostList()
        openKindMenu(2)
        inTheFirstFramesAfter({ holder.removeBand(2) }) {
            assertEquals("a Kind menu is open on the first frames after the delete", 0, openKindMenus())
        }
        compose.waitForIdle()
        assertEquals("a Kind menu is open after the animations settled", 0, openKindMenus())
        select(2)
        assertEquals("the band that took the place has its menu open", 0, openKindMenus())
        val own = holder.ui.value.bands[2].kind.label()
        val shown = tag("band-kind-2").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].first()
        assertEquals("Band 3 filter type, $own", shown)
    }

    @Test fun deletingAnEarlierBandLeavesNoMenuOpenOnTheRowsThatMovedUp() {
        hostList()
        openKindMenu(3)
        inTheFirstFramesAfter({ holder.removeBand(1) }) {
            assertEquals("a Kind menu is open on the first frames after the delete", 0, openKindMenus())
        }
        compose.waitForIdle()
        assertEquals("a Kind menu is open after the animations settled", 0, openKindMenus())
    }

    @Test fun anUndoThatBringsABandBackLeavesNoMenuOnTheBandNowAtThatIndex() {
        hostList()
        holder.removeBand(0)
        compose.waitForIdle()
        openKindMenu(1)
        holder.undo()
        compose.waitForIdle()
        assertEquals(7, holder.ui.value.bands.size)
        assertEquals("a Kind menu is open on a different band after the undo", 0, openKindMenus())
    }

    @Test fun aFieldMessageDoesNotMoveToTheBandThatTakesItsPlace() {
        hostList()
        select(3)
        tag("band-field-freq-3").performClick()
        tag("band-field-freq-3").performTextReplacement("99999")
        compose.waitForIdle()
        tag("band-field-freq-3").performImeAction()
        compose.waitForIdle()
        tag("band-field-message-3").assertExists()
        holder.removeBand(3)
        compose.waitForIdle()
        select(3)
        compose.onAllNodes(hasTestTag("band-field-message-3")).assertCountEquals(0)
        val own = NumericField.formatFrequency(holder.ui.value.bands[3].freq)
        val shown = tag("band-field-freq-3").fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(own, shown)
    }
}
