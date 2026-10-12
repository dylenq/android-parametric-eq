package com.dylenq.eq.ui

import android.media.AudioManager
import android.media.AudioTrack
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.testsupport.EqComposeRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurveCardTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val ctrl get() = rule.runtime.controller

    private fun await(pred: (EqUiState) -> Boolean): EqUiState =
        runBlocking { withTimeout(10_000) { holder.ui.first { it.curve != null && pred(it) } } }

    private fun setGain(i: Int, db: Float) {
        holder.onBandDragStart(i)
        holder.onBandDragChange(i, db)
        holder.onBandDragEnd()
    }

    private fun stateDescription(expected: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)

    private fun waitForText(text: String) = rule.compose.waitUntil(10_000) {
        rule.compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun flatCurveShowsOnlyActualAndNoCaption() {
        rule.launch()
        await { true }
        rule.compose.onNodeWithTag("curve").assertIsDisplayed()
        rule.compose.onNodeWithText("Actual").assertIsDisplayed()
        rule.compose.onNodeWithText("Target").assertDoesNotExist()
        rule.compose.onNodeWithTag("caption").assertDoesNotExist()
        rule.compose.onNodeWithTag("curve").assertContentDescriptionEquals("Frequency response, 20 hertz to 20 kilohertz")
    }

    @Test fun narrowLowBandsShowTargetLegendCaptionAndDialog() {
        holder.setBandCount(31)
        // Find a narrow opposite-sign pair of low bands whose Actual curve misses the Target by >= 1.0 dB.
        val rate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC).toUInt()
        var found = -1
        for (i in 0..8) {
            setGain(i, 12f); setGain(i + 1, -12f)
            val c = ctrl.responseCurve(256u, rate, ctrl.snapshot().frameDurationMs)
            val d = CurveStats.maxDiff(c.freqsHz.toFloatArray(), c.idealDb.toFloatArray(), c.achievedDb.toFloatArray())
            if (CurveStats.captionVisible(d.db)) { found = i; break }
            setGain(i, 0f); setGain(i + 1, 0f)
        }
        assertTrue("no low pair reached a 1.0 dB difference", found >= 0)
        rule.launch()
        val s = await { it.curve != null && CurveStats.captionVisible(CurveStats.maxDiff(it.curve!!.freqsHz, it.curve!!.idealDb, it.curve!!.achievedDb).db) }
        val expected = CurveStats.caption(s.curve!!.freqsHz, s.curve!!.idealDb, s.curve!!.achievedDb)!!
        assertTrue(expected, expected.startsWith("Actual differs from target by up to ") && expected.endsWith("."))

        waitForText("Target")
        rule.compose.onNodeWithText("Actual").assertIsDisplayed()
        rule.compose.onNodeWithTag("caption").assertIsDisplayed()
        rule.compose.onNodeWithText(expected).assertIsDisplayed()

        rule.compose.onNodeWithTag("caption").performClick()
        rule.compose.onNodeWithText("Target and actual curves").assertIsDisplayed()
        rule.compose.onNodeWithText(
            "Target is the curve you set. Actual is what Android's equalizer can deliver. They differ most for narrow changes at low frequencies.",
        ).assertIsDisplayed()
        rule.compose.onNodeWithText("Got it").performClick()
        rule.compose.onNodeWithText("Target and actual curves").assertDoesNotExist()
    }

    @Test fun overshootShowsOverChipAndTappingAppliesTheSuggestion() {
        setGain(3, 12f)
        rule.launch()
        val s = await { it.headroomAfterPreampDb < -0.05f }
        val chip = CurveStats.headroomText(s.headroomAfterPreampDb)
        assertTrue(chip, Regex("Over by \\d+\\.\\d dB").matches(chip))
        waitForText(chip)
        rule.compose.onNodeWithTag("headroom_chip").assertIsDisplayed()
        rule.compose.onNodeWithTag("headroom_chip").assert(hasText(chip))
        assertTrue(s.suggestedPreampDb < 0f)

        rule.compose.onNodeWithTag("headroom_chip").performClick()
        val after = await { it.preampDb == s.suggestedPreampDb && it.headroomAfterPreampDb >= -0.05f }
        assertEquals(s.suggestedPreampDb, after.preampDb, 0f)
        rule.compose.waitUntil(10_000) {
            rule.compose.onAllNodes(hasText("Headroom", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.compose.onNodeWithText("Over by", substring = true).assertDoesNotExist()
    }

    @Test fun bypassDimsAndHidesChrome() {
        setGain(3, 12f)
        rule.launch()
        await { it.headroomAfterPreampDb < -0.05f }
        holder.setBypass(true)
        waitForText("Bypassed")
        rule.compose.onNodeWithText("Bypassed").assertIsDisplayed()
        rule.compose.onNodeWithText("Actual").assertDoesNotExist()
        rule.compose.onNodeWithText("Target").assertDoesNotExist()
        rule.compose.onNodeWithTag("headroom_chip").assertDoesNotExist()
        rule.compose.onNodeWithTag("caption").assertDoesNotExist()
        rule.compose.waitUntil(5_000) {
            runCatching { rule.compose.onNodeWithTag("curve").assert(stateDescription("Equalizer is off. Curve not applied.")) }.isSuccess
        }
        holder.setBypass(false)
        rule.compose.waitUntil(5_000) { rule.compose.onAllNodes(hasText("Bypassed")).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun curveSemanticsDescribePeakAndTrough() {
        setGain(5, 4.5f)
        rule.launch()
        val s = await { (it.curve?.achievedDb?.max() ?: 0f) > 3f }
        val c = s.curve!!
        val expected = CurveStats.stateDescription(c.freqsHz, c.idealDb, c.achievedDb, bypass = false)
        assertTrue(expected, expected.startsWith("Peak plus "))
        rule.compose.waitUntil(5_000) {
            runCatching { rule.compose.onNodeWithTag("curve").assert(stateDescription(expected)) }.isSuccess
        }
        rule.compose.onNodeWithTag("curve").assertContentDescriptionEquals("Frequency response, 20 hertz to 20 kilohertz")
    }

    @Test fun parametricModeStillDrawsTheCard() {
        holder.setMode(EqMode.PARAMETRIC)
        rule.launch()
        await { it.mode == EqMode.PARAMETRIC }
        rule.compose.onNodeWithTag("curve").assertIsDisplayed()
        rule.compose.onNodeWithText("Actual").assertIsDisplayed()
    }

    @Test fun curveErrorShowsUnavailableText() {
        val scenario = rule.launch()
        await { true }
        scenario.onActivity { act ->
            val view = ComposeView(act).apply {
                setContent {
                    androidx.compose.material3.MaterialTheme {
                        CurveCard({ EqUiState(curveError = true) }, {})
                    }
                }
            }
            act.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        waitForText("Curve unavailable")
        assertNotNull(rule.compose.onNodeWithText("Curve unavailable").fetchSemanticsNode())
    }
}
