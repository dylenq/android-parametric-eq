package com.dylenq.eq.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.EqApp
import com.dylenq.eq.core.Band
import com.dylenq.eq.core.EqMode
import com.dylenq.eq.core.FilterKind
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Evidence screenshots of the real Parametric screen, only when run with `-e screenshots 1` (a no-op in the
 * normal suite). `-e shot NAME` names the file (written with screencap to /data/local/tmp so it survives the
 * uninstall that connectedDebugAndroidTest does), `-e select N` expands band N, `-e ime 1` focuses its
 * frequency field and waits for the keyboard. The device size and rotation are set by the caller with adb.
 */
class ParametricScreenshots {
    @get:Rule val rule = EqComposeRule()

    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EqApp

    @Test fun realScreen() {
        assumeTrue(args.getString("screenshots") == "1")
        val name = args.getString("shot") ?: "parametric"
        val select = args.getString("select")?.toInt()
        rule.runtime.controller.edit {
            it.setMode(EqMode.PARAMETRIC)
            it.setBand(0u, Band(FilterKind.LOW_SHELF, 63f, 5f, 0.71f, true))
            it.setBand(1u, Band(FilterKind.PEAK, 250f, -2.5f, 1.4f, true))
            it.setBand(2u, Band(FilterKind.PEAK, 1250f, 3.5f, 1f, true))
            it.setBand(3u, Band(FilterKind.PEAK, 3200f, -4f, 2f, false))
            it.setBand(5u, Band(FilterKind.HIGH_SHELF, 8000f, 2f, 0.71f, true))
            while (it.snapshot().bands.size < 7) it.addBand()
        }
        rule.launch()
        rule.compose.waitForIdle()
        if (select != null) app.uiHolder().selectBand(select)
        Thread.sleep(900)
        rule.compose.waitForIdle()
        if (args.getString("ime") == "1" && select != null) {
            rule.compose.onNodeWithTag("band-field-freq-$select").performClick()
            Thread.sleep(2500)
            rule.compose.waitForIdle()
        }
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.executeShellCommand("screencap -p /data/local/tmp/$name.png").close()
        Thread.sleep(1500)
    }
}
