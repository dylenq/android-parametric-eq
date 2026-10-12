package com.dylenq.eq.ui

import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dylenq.eq.MainActivity
import com.dylenq.eq.testsupport.EqComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GFX-06 / L-07: on API 31+ the scheme [EqTheme] hands to the screen equals the dynamic scheme for the
 * system night mode. The compared values are logged under tag EQ-THEME (committed as theme-test.raw.log).
 * Also checks the real MainActivity: its root paints the dynamic `surface` colour.
 */
@RunWith(AndroidJUnit4::class)
class ThemeTest {
    @get:Rule val rule = EqComposeRule()

    private fun shell(cmd: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        pfd.close()
    }

    private fun isNight(scenario: ActivityScenario<MainActivity>): Boolean {
        var night = false
        scenario.onActivity { night = (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
        return night
    }

    private fun setNight(scenario: ActivityScenario<MainActivity>, night: Boolean) {
        shell("cmd uimode night ${if (night) "yes" else "no"}")
        val deadline = System.currentTimeMillis() + 10_000
        while (isNight(scenario) != night && System.currentTimeMillis() < deadline) Thread.sleep(100)
        rule.compose.waitForIdle()
        assertEquals("activity configuration follows `cmd uimode night`", night, isNight(scenario))
    }

    private fun hex(c: Color) = String.format("#%08X", c.toArgb())

    private fun roles(s: ColorScheme) = listOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary, "surface" to s.surface,
        "surfaceContainerLow" to s.surfaceContainerLow, "primaryContainer" to s.primaryContainer, "error" to s.error,
    )

    /** Hosts a probe inside EqTheme and returns the scheme it sees plus the dynamic scheme for the same context. */
    private fun probe(scenario: ActivityScenario<MainActivity>, night: Boolean) {
        var seen: ColorScheme? = null
        var expected: ColorScheme? = null
        scenario.onActivity { act ->
            expected = if (night) dynamicDarkColorScheme(act) else dynamicLightColorScheme(act)
            act.setContent { EqTheme { Capture { seen = it } } }
        }
        rule.compose.waitForIdle()
        val e = expected!!
        val s = seen ?: error("probe did not compose")
        for ((name, color) in roles(s)) {
            val want = roles(e).first { it.first == name }.second
            Log.i("EQ-THEME", "night=$night $name app=${hex(color)} dynamic=${hex(want)}")
            assertEquals("night=$night $name", want, color)
        }
    }

    @androidx.compose.runtime.Composable
    private fun Capture(onScheme: (ColorScheme) -> Unit) {
        val cs = MaterialTheme.colorScheme
        SideEffect { onScheme(cs) }
    }

    @Test fun appSchemeEqualsDynamicSchemeInBothNightModes() {
        assumeTrue("dynamic colour needs API 31+", Build.VERSION.SDK_INT >= 31)
        val scenario = rule.launch()
        try {
            for (night in listOf(true, false, true)) {
                setNight(scenario, night)
                probe(scenario, night)
            }
        } finally {
            shell("cmd uimode night auto")
        }
    }

    @Test fun realActivityPaintsTheDynamicSurface() {
        assumeTrue("dynamic colour needs API 31+", Build.VERSION.SDK_INT >= 31)
        val scenario = rule.launch()
        try {
            for (night in listOf(true, false)) {
                setNight(scenario, night)
                var want = 0
                scenario.onActivity { want = (if (night) dynamicDarkColorScheme(it) else dynamicLightColorScheme(it)).surface.toArgb() }
                val img = rule.compose.onRoot().captureToImage().asAndroidBitmap()
                val got = img.getPixel(img.width / 2, 2)
                Log.i("EQ-THEME", "night=$night real-activity surface want=${String.format("#%08X", want)} got=${String.format("#%08X", got)}")
                assertEquals("night=$night top-bar background is the dynamic surface", want, got)
            }
        } finally {
            shell("cmd uimode night auto")
        }
    }
}
