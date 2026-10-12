package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.center
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The panel and menu in a 360 dp wide column with 16 dp screen margins (the spec's reference size). */
@RunWith(AndroidJUnit4::class)
class BandCountTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose

    private fun hostPanel() = rule.host {
        val ui by holder.ui.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.requiredWidth(360.dp).padding(16.dp)) {
                BandCountMenu(ui.graphicBandCount, holder::setBandCount)
                GraphicSliderPanel(
                    state = { ui },
                    onDragStart = holder::onBandDragStart,
                    onChange = holder::onBandDragChange,
                    onDragEnd = holder::onBandDragEnd,
                    onReset = holder::resetBand,
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                )
            }
        }
    }

    private fun gains() = rule.runtime.controller.snapshot().graphicGainsDb.toList()

    private fun scrollValue(): Int {
        val range = compose.onNodeWithTag("slider-row").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        return range.value().toInt()
    }

    private fun scrollMax(): Int {
        val range = compose.onNodeWithTag("slider-row").fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        return range.maxValue().toInt()
    }

    private fun pick(count: Int) {
        compose.onNodeWithTag("band-count-button").performClick()
        compose.onNodeWithTag("band-count-item-$count").performClick()
        compose.waitForIdle()
    }

    private fun setProgress(index: Int, v: Float) {
        compose.onNodeWithTag("band-$index").performSemanticsAction(SemanticsActions.SetProgress) { it(v) }
        compose.waitForIdle()
    }

    private fun readoutText(): String =
        compose.onNodeWithTag("readout").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test fun menuOffersFourCountsInOrderWithCheckOnCurrent() {
        hostPanel()
        compose.onNodeWithTag("band-count-button").assert(hasContentDescription("Number of bands, 10"))
        compose.onNodeWithTag("band-count-button").performClick()
        val texts = listOf(5, 10, 15, 31).map { n ->
            compose.onNodeWithTag("band-count-item-$n").assertIsDisplayed()
            compose.onNodeWithTag("band-count-item-$n").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        }
        assertEquals(listOf("5 bands", "10 bands", "15 bands", "31 bands"), texts)
        assertEquals(1, compose.onAllNodes(hasTestTag("band-count-check"), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNode(hasTestTag("band-count-check") and hasAnyAncestor(hasTestTag("band-count-item-10")), useUnmergedTree = true).assertExists()
        // order: tops increase
        val tops = listOf(5, 10, 15, 31).map { compose.onNodeWithTag("band-count-item-$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
    }

    @Test fun selectingACountRendersItsBandsFromTheNextSnapshot() {
        hostPanel()
        pick(15)
        assertEquals(15, rule.runtime.controller.snapshot().graphicBandCount.toInt())
        assertEquals(15, holder.ui.value.graphicGainsDb.size)
        compose.onNodeWithTag("band-14").assertExists()
        compose.onNodeWithTag("band-15").assertDoesNotExist()
        compose.onNodeWithTag("band-count-button").assert(hasContentDescription("Number of bands, 15"))
        pick(5)
        compose.onNodeWithTag("band-4").assertExists()
        compose.onNodeWithTag("band-5").assertDoesNotExist()
    }

    @Test fun tenBandsFitAt360WithoutScrolling() {
        hostPanel()
        for (i in 0..9) compose.onNodeWithTag("band-$i").assertIsDisplayed()
        assertEquals("no scroll range at 10 bands", 0, scrollMax())
    }

    @Test fun thirtyOneBandsScrollAndHorizontalSwipeLeavesGainsAlone() {
        hostPanel()
        pick(31)
        compose.onNodeWithTag("band-0").assertIsDisplayed()
        compose.onNodeWithTag("band-30").assertIsNotDisplayed()
        assertTrue(scrollMax() > 0)
        setProgress(4, 3f)
        setProgress(9, -2f)
        val before = gains()
        // A horizontal swipe that STARTS on a slider scrolls the row and does not change any gain.
        compose.onNodeWithTag("band-2").performTouchInput {
            swipe(start = center, end = center - Offset(220f, 0f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertTrue("row scrolled: ${scrollValue()}", scrollValue() > 0)
        assertEquals(before, gains())
        compose.onNodeWithTag("band-30").performScrollTo()
        compose.onNodeWithTag("band-30").assertIsDisplayed()
    }

    @Test fun verticalSwipeOnASliderInTheScrollingRowChangesGainAndDoesNotScroll() {
        hostPanel()
        pick(31)
        assertEquals(0, scrollValue())
        compose.onNodeWithTag("band-3").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue("gain ${gains()[3]}", gains()[3] > 6f)
        assertEquals("vertical drag must not scroll", 0, scrollValue())
        assertEquals(0f, gains()[2], 0f)
        assertEquals(0f, gains()[4], 0f)
    }

    @Test fun readoutShowsHintThenLastTouchedBandAndResetsOnCountChange() {
        hostPanel()
        assertEquals("Drag a slider. Double-tap to reset it.", readoutText())
        val idx = holder.ui.value.graphicCentresHz.indexOfFirst { it in 990f..1010f }
        assertTrue(idx >= 0)
        setProgress(idx, 3.5f)
        assertEquals("1 kHz   +3.5 dB", readoutText())
        pick(15)
        assertEquals("Drag a slider. Double-tap to reset it.", readoutText())
    }

    @Test fun scrollPositionResetsToZeroOnCountChange() {
        hostPanel()
        pick(31)
        compose.onNodeWithTag("band-30").performScrollTo()
        assertTrue(scrollValue() > 0)
        pick(15)
        compose.waitUntil(3_000) { scrollValue() == 0 }
    }

    @Test fun gainLabelsOnlyAtWidePitch() {
        hostPanel()
        pick(5)
        assertEquals(5, compose.onAllNodes(hasTestTag("gain-label"), useUnmergedTree = true).fetchSemanticsNodes().size)
        pick(10)
        assertEquals(0, compose.onAllNodes(hasTestTag("gain-label"), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun bypassKeepsSlidersOperable() {
        hostPanel()
        holder.setBypass(true)
        compose.waitUntil(5_000) { holder.ui.value.bypass }
        setProgress(2, 4f)
        assertEquals(4f, gains()[2], 0.01f)
        compose.onNodeWithTag("band-2").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(gains()[2] > 6f)
    }
}
