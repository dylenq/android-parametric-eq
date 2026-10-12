package com.dylenq.eq.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.center
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-SPEC checks 3 and 6 (band half): a slider moves the published curve, end to end through MainActivity. */
@RunWith(AndroidJUnit4::class)
class SliderToCurveTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose

    private fun await(pred: (EqUiState) -> Boolean): EqUiState =
        runBlocking { withTimeout(10_000) { holder.ui.first { it.curve != null && pred(it) } } }

    private fun indexOf1k(): Int = holder.ui.value.graphicCentresHz.indexOfFirst { it in 990f..1010f }.also { assertTrue(it >= 0) }

    private fun peak(c: CurvePoints): Pair<Float, Float> {
        var bi = 0
        for (i in c.achievedDb.indices) if (c.achievedDb[i] > c.achievedDb[bi]) bi = i
        return c.freqsHz[bi] to c.achievedDb[bi]
    }

    private fun readout(): String =
        compose.onNodeWithTag("readout").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test fun draggingTheOneKilohertzSliderRaisesTheCurveNearOneKilohertz() {
        rule.launch()
        await { true }
        val i = indexOf1k()
        val flat = await { true }.curve!!
        assertTrue(abs(peak(flat).second) < 0.05f)

        compose.onNodeWithTag("band-$i").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40f))
            moveBy(Offset(0f, -40f))
            moveBy(Offset(0f, -40f))
        }
        compose.waitForIdle()
        // while pressed: bubble shows the band's gain and the readout names the band
        val g = rule.runtime.controller.snapshot().graphicGainsDb[i]
        assertTrue("gain $g", g > 0f)
        assertEquals("gain is on the 0.5 dB grid", 0f, (g * 2f) - Math.round(g * 2f), 1e-4f)
        compose.onNodeWithTag("value-bubble").assertExists()
        assertEquals(GainFormat.format(g), compose.onNodeWithTag("value-bubble").fetchSemanticsNode().let { n ->
            // the bubble text is a child
            compose.onNode(androidx.compose.ui.test.hasText(GainFormat.format(g)) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("value-bubble")), useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        })
        assertEquals("1 kHz   ${GainFormat.format(g)}", readout())
        compose.onNodeWithTag("band-$i").performTouchInput { up() }
        compose.waitForIdle()

        val s = await { st -> st.curve!!.let { peak(it).second > 0.5f } }
        val (fHz, db) = peak(s.curve!!)
        assertTrue("peak at $fHz Hz", fHz in 700f..1400f)
        assertTrue("peak $db dB rises with the slider ($g)", db > 0.5f * g)
        assertEquals("1 kHz   ${GainFormat.format(g)}", readout())
    }

    @Test fun doubleTapResetsTheBandAndFlattensTheCurve() {
        rule.launch()
        await { true }
        val i = indexOf1k()
        compose.onNodeWithTag("band-$i").performSemanticsAction(SemanticsActions.SetProgress) { it(8f) }
        compose.waitForIdle()
        await { st -> peak(st.curve!!).second > 1f }
        compose.onNodeWithTag("band-$i").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertEquals(0f, rule.runtime.controller.snapshot().graphicGainsDb[i], 0f)
        val s = await { st -> abs(peak(st.curve!!).second) < 0.05f }
        assertTrue(s.curve!!.achievedDb.all { abs(it) < 0.05f })
        // set + reset: two undo steps
        var n = 0
        while (rule.runtime.controller.snapshot().canUndo && n < 10) { holder.undo(); n++ }
        assertEquals(2, n)
    }

    @Test fun setProgressAtThirtyOneBandsStillUpdatesTheCurve() {
        holder.setBandCount(31)
        rule.launch()
        await { it.graphicBandCount == 31 }
        val i = indexOf1k()
        compose.onNodeWithTag("band-$i").performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
        compose.waitForIdle()
        assertEquals(7f, rule.runtime.controller.snapshot().graphicGainsDb[i], 0.01f)
        val s = await { st -> peak(st.curve!!).second > 3f }
        val (fHz, _) = peak(s.curve!!)
        assertTrue("peak at $fHz Hz", fHz in 800f..1250f)
    }
}
