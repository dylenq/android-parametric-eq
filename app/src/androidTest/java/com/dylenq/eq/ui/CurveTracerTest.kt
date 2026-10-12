package com.dylenq.eq.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.testsupport.EqComposeRule
import com.dylenq.eq.testsupport.FakeEffectFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurveTracerTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp

    /** Waits for the holder to publish a curve satisfying [pred]. */
    private fun awaitCurve(pred: (CurvePoints) -> Boolean): CurvePoints = runBlocking {
        withTimeout(10_000) { app.uiHolder().ui.first { it.curve?.let(pred) == true }.curve!! }
    }

    @Test
    fun realCurveReachesTheCanvasAndFollowsEdits() {
        rule.runtime.controller.edit { it.setGraphicGain(3u, 6f) }
        rule.launch()
        rule.compose.onNodeWithTag("curve").assertIsDisplayed()

        val curve = awaitCurve { it.achievedDb.max() > 4f }
        assertEquals(256, curve.freqsHz.size)
        assertEquals(20f, curve.freqsHz.first(), 0.5f)
        assertEquals(20000f, curve.freqsHz.last(), 1f)

        rule.compose.waitForIdle()
        val img = rule.compose.onNodeWithTag("curve").captureToImage()
        val px = IntArray(img.width * img.height)
        img.readPixels(px)
        assertTrue("curve capture is one flat colour", px.toSet().size > 1)

        rule.runtime.controller.edit { it.setGraphicGain(3u, 0f) }
        app.uiHolder().refreshCurve()
        awaitCurve { it.achievedDb.max() < 1f }
    }

    @Test
    fun responseCurveDoesNotMarkDirty() {
        val applies = AtomicInteger()
        rule.runtime.onApplied = { applies.incrementAndGet() }
        val factory = rule.runtimeRule.factoryAs<FakeEffectFactory>()
        val before = factory.calls().size
        val c = rule.runtime.controller
        repeat(20) { c.responseCurve(256u, 48000u, c.snapshot().frameDurationMs) }
        rule.runtime.flush()
        assertEquals(0, applies.get())
        assertEquals(before, factory.calls().size)
    }
}
