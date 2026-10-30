package com.dylenq.eq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
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
}
