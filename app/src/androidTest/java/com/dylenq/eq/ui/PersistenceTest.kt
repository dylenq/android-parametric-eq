package com.dylenq.eq.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.EqRuntime
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.service.SettingsStore
import com.dylenq.eq.testsupport.EqComposeRule
import com.dylenq.eq.testsupport.FakeEffectFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI-SPEC checks 8 and 18 on the whole screen. "Process death" is simulated by flushing the runtime
 * and opening a second [EqRuntime] on the same state directory (what a new process does); the process
 * itself is not killed here.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val holder get() = app.uiHolder()
    private val compose get() = rule.compose
    private val ctrl get() = rule.runtime.controller

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun scrollOffset(): Int {
        val node = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange).and(hasTestTag("slider-row")))
            .fetchSemanticsNode()
        return node.config[SemanticsProperties.HorizontalScrollAxisRange].value().toInt()
    }

    @Test fun limiterOffAndTheRestSurviveANewRuntimeOnTheSameState() {
        rule.launch()
        compose.waitForIdle()

        // Through the screen: the limiter row and the Equalizer toggle.
        assertTrue(ctrl.snapshot().limiterEnabled)
        compose.onNodeWithTag("limiter-row").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(ctrl.snapshot().limiterEnabled)
        compose.onNodeWithTag("equalizer-toggle").performClick()
        compose.waitForIdle()
        assertTrue(ctrl.snapshot().bypass)

        // Through the holder: bands, a gain, the preamp.
        holder.setBandCount(31)
        holder.onBandDragStart(7)
        holder.onBandDragChange(7, 4.5f)
        holder.onBandDragEnd()
        holder.setPreamp(-3f)
        holder.setMode(EqMode.PARAMETRIC)

        rule.runtime.flush()

        val second = EqRuntime(FakeEffectFactory(), rule.runtimeRule.dir, SettingsStore(null))
        try {
            val h2 = UiStateHolder(second)
            try {
                val s = runBlocking { withTimeout(5000) { h2.ui.first { true } } }
                assertFalse("limiter off persists (UI-SPEC check 8)", s.limiterEnabled)
                assertTrue(s.bypass)
                assertEquals(31, s.graphicBandCount)
                assertEquals(31, s.graphicGainsDb.size)
                assertEquals(4.5f, s.graphicGainsDb[7], 0.01f)
                assertEquals(-3f, s.preampDb, 0f)
                assertEquals(EqMode.PARAMETRIC, s.mode)
            } finally {
                h2.close()
            }
        } finally {
            second.shutdown()
        }
    }

    @Test fun rotationKeepsBandCountModeValuesAndScrollPosition() {
        val scenario = rule.launch()
        compose.waitForIdle()
        holder.setBandCount(31)
        holder.onBandDragStart(3)
        holder.onBandDragChange(3, 4.5f)
        holder.onBandDragEnd()
        compose.waitForIdle()

        compose.onNodeWithTag("band-30").performScrollTo()
        compose.waitForIdle()
        val before = scrollOffset()
        assertTrue("scrolled to the far end: $before", before > 0)

        scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("slider-row").assertIsDisplayed()
        assertEquals(31, ctrl.snapshot().graphicBandCount.toInt())
        assertEquals(EqMode.GRAPHIC, ctrl.snapshot().mode)
        assertEquals(4.5f, ctrl.snapshot().graphicGainsDb[3], 0.01f)
        assertEquals("scroll position survives recreation", before, scrollOffset())

        // Parametric survives too.
        compose.onNodeWithTag("mode-parametric").performClick()
        compose.waitForIdle()
        scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("parametric-placeholder").assertIsDisplayed()
        assertEquals(0, count("slider-row"))
        assertEquals(EqMode.PARAMETRIC, ctrl.snapshot().mode)
    }
}
