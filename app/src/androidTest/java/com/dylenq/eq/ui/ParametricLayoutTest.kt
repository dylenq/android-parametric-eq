package com.dylenq.eq.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Parametric screen measured on the real [EqScreen]: IME-open layout, landscape form and the portrait
 * region heights. Layout numbers are read from the screen and logged under `EQ-LAYOUT`; only invariants are asserted.
 */
@RunWith(AndroidJUnit4::class)
class ParametricLayoutTest {
    @get:Rule val rule = EqComposeRule()

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp
    private val compose get() = rule.compose
    private val ctrl get() = rule.runtime.controller
    private var ime by mutableStateOf(false)

    @Before fun parametricWithBands() {
        ime = false
        ctrl.edit {
            it.setMode(EqMode.PARAMETRIC)
            it.setBand(0u, Band(FilterKind.LOW_SHELF, 63f, 5f, 0.71f, true))
            it.setBand(1u, Band(FilterKind.PEAK, 250f, -2.5f, 1.4f, true))
            it.setBand(2u, Band(FilterKind.PEAK, 1250f, 3.5f, 1f, true))
            it.setBand(3u, Band(FilterKind.HIGH_SHELF, 8000f, 2f, 0.71f, true))
        }
    }

    /** The whole [EqScreen] in a box of exactly the window size ([spec]); [fakeIme] is the injected keyboard flag. */
    private fun hostScreen(spec: WindowSpec) = rule.host {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, spec.fontScale)) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(spec.widthDp.dp, spec.heightDp.dp)) {
                    EqScreen(
                        holder = app.uiHolder(), prefs = rule.uiPrefs, notificationsGranted = true,
                        onStartService = {}, onAllowNotifications = {}, onOpenNotificationSettings = {},
                        spec = spec, imeVisible = ime,
                    )
                }
            }
        }
    }

    private fun b(tag: String): DpRect {
        val n = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val p = n.positionInRoot
        return DpRect((p.x / d).dp, (p.y / d).dp, ((p.x + n.size.width) / d).dp, ((p.y + n.size.height) / d).dp)
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun verticalMax(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    @Test fun fakeImeSwitchesToTheCompactLayoutAndBackWithoutLosingFocus() {
        hostScreen(WindowSpec(411, 891))
        app.uiHolder().selectBand(1)
        compose.waitForIdle()
        val plotBefore = b("curve").height
        // The click may raise the real keyboard too (it shrinks the whole screen), so compare offsets inside the column.
        val listOffsetBefore = b("peq-list").top - b("eq-column").top
        assertTrue("normal plot >= 192 dp: $plotBefore", plotBefore >= 192.dp)
        assertTrue(exists("status-strip") && exists("mode-row"))
        compose.onNodeWithTag("band-field-freq-1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("band-field-freq-1").assertIsFocused()

        ime = true
        compose.waitForIdle()
        assertEquals("plot is 120 dp", 120f, b("curve").height.value, 1f)
        assertFalse("strip hidden", exists("status-strip"))
        assertFalse("mode row hidden", exists("mode-row"))
        assertFalse("caption hidden", exists("caption"))
        compose.onNodeWithTag("equalizer-toggle").assertIsDisplayed()
        compose.onNodeWithTag("band-field-freq-1").assertIsFocused()
        val listOffsetAfter = b("peq-list").top - b("eq-column").top
        assertTrue("the list moved up by the freed height: $listOffsetAfter vs $listOffsetBefore", listOffsetAfter < listOffsetBefore - 200.dp)
        val f = b("band-field-freq-1")
        val list = b("peq-list")
        assertTrue("focused field inside the list viewport: $f in $list", f.top >= list.top - 1.dp && f.bottom <= list.bottom + 1.dp)
        Log.i("EQ-LAYOUT", "ime-open 411x891 (injected): plot=${b("curve").height} list=${b("peq-list").height} (was $plotBefore, list offset $listOffsetBefore -> $listOffsetAfter)")

        val t0 = System.nanoTime()
        ime = false
        compose.waitForIdle()
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("EQ-LAYOUT", "ime-close restore took $ms ms")
        assertTrue("restored in ${ms} ms", ms < 150)
        assertTrue(exists("status-strip") && exists("mode-row"))
        // A real keyboard may still be up here, which legitimately shrinks the plot toward its 192 dp floor.
        assertTrue("normal plot back: ${b("curve").height}", b("curve").height >= 190.dp)
        compose.onNodeWithTag("band-field-freq-1").assertIsFocused()
    }

    @Test fun switchingModeDropsFocusAndTheKeyboardFormIsOnlyForParametric() {
        hostScreen(WindowSpec(411, 891))
        ime = true // a keyboard in Graphic mode must not trigger the Parametric compact form
        app.uiHolder().setMode(EqMode.GRAPHIC)
        val until = System.currentTimeMillis() + 3000
        while (!exists("mode-row") && System.currentTimeMillis() < until) { Thread.sleep(50); compose.waitForIdle() }
        assertTrue("graphic keeps the strip and mode row", exists("status-strip") && exists("mode-row"))
        assertTrue("graphic plot is not compact: ${b("curve").height}", b("curve").height > 150.dp)
    }

    /** Real soft keyboard on the emulator; skipped (and reported) when no IME shows. */
    @Test fun realKeyboardKeepsTheFocusedFieldAboveItAndRestoresOnClose() {
        ctrl.edit {
            while (it.snapshot().bands.size < 10) it.addBand()
        }
        rule.host {
            EqScreen(
                holder = app.uiHolder(), prefs = rule.uiPrefs, notificationsGranted = true,
                onStartService = {}, onAllowNotifications = {}, onOpenNotificationSettings = {},
            )
        }
        app.uiHolder().selectBand(8)
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        val plotBefore = b("curve").height
        compose.onNodeWithTag("band-field-freq-8").performClick()
        var decorHolder: android.view.View? = null
        compose.runOnUiThread { decorHolder = currentDecor() }
        val decor = decorHolder!!
        var shown = false
        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            compose.runOnUiThread { shown = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            if (shown) break
            Thread.sleep(100)
        }
        Log.i("EQ-LAYOUT", "real keyboard shown=$shown")
        assumeTrue("no soft keyboard appeared on this emulator", shown)
        Thread.sleep(1200) // let the IME animation settle
        compose.waitForIdle()
        var imeTopPx = 0
        compose.runOnUiThread {
            val i = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.ime())
            imeTopPx = decor.height - i.bottom
        }
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertFalse("mode row hidden", exists("mode-row"))
        assertEquals("plot 120 dp", 120f, b("curve").height.value, 1f)
        val f = b("band-field-freq-8")
        Log.i("EQ-LAYOUT", "real keyboard: field=${f} imeTop=${imeTopPx / d}dp plot=${b("curve").height} list=${b("peq-list")}")
        assertTrue("focused field above the keyboard: ${f.bottom} <= ${imeTopPx / d}", f.bottom.value <= imeTopPx / d + 0.5f)
        assertTrue("focused field below the curve: ${f.top} >= ${b("curve").bottom}", f.top >= b("curve").bottom)
        // Close the keyboard (back) and the normal layout returns.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close()
        val d2 = System.currentTimeMillis() + 5000
        while (!exists("mode-row") && System.currentTimeMillis() < d2) { Thread.sleep(100); compose.waitForIdle() }
        assertTrue("mode row back", exists("mode-row"))
        assertEquals(plotBefore.value, b("curve").height.value, 1f)
    }

    /** Must run on the main thread. */
    private fun currentDecor(): android.view.View {
        val resumed = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
        return checkNotNull(resumed.firstOrNull()?.window?.decorView) { "no resumed activity" }
    }

    @Test fun landscape914x411IsTwoPaneWithTheListOnTheRightAndAPlotOfAtLeast140() {
        hostScreen(WindowSpec(914, 411))
        compose.waitForIdle()
        val plot = b("curve").height
        Log.i("EQ-LAYOUT", "landscape 914x411: curve=${b("curve")} plot=$plot list=${b("peq-list")}")
        assertTrue("two panes", exists("two-pane"))
        assertFalse("status second line dropped", exists("status-hint"))
        assertTrue("list in the right pane: ${b("peq-list")} vs ${b("right-pane")}", b("peq-list").left >= b("right-pane").left - 1.dp)
        assertTrue("curve left of the list", b("curve").right <= b("peq-list").left)
        assertTrue("plot >= 140 dp in a 411 dp tall window: $plot", plot >= 140.dp)
        assertFalse("preamp card is in the list, not pinned", exists("preamp-pill") && b("preamp-pill").top > b("peq-list").bottom)
    }

    @Test fun landscape800x360ReportsItsPlot() {
        hostScreen(WindowSpec(800, 360))
        compose.waitForIdle()
        Log.i("EQ-LAYOUT", "landscape 800x360: curve=${b("curve")} plot=${b("curve").height} list=${b("peq-list")}")
        assertTrue("two panes", exists("two-pane"))
        assertTrue("curve left of the list", b("curve").right <= b("peq-list").left)
        assertTrue("plot at the 140 dp floor or reported: ${b("curve").height}", b("curve").height >= 100.dp)
    }

    @Test fun wideTallLandscapePlotIsClampedTo160To280() {
        hostScreen(WindowSpec(1000, 700))
        compose.waitForIdle()
        val plot = b("curve").height
        Log.i("EQ-LAYOUT", "two-pane 1000x700: plot=$plot")
        assertTrue("plot within 160..280: $plot", plot >= 160.dp && plot <= 280.dp)
    }
}
