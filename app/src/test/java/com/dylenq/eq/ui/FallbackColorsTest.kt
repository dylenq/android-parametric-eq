package com.dylenq.eq.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hand-authored fallback schemes (API 28-30): anchors from the UI-SPEC and WCAG contrast bounds. */
class FallbackColorsTest {
    private fun lin(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun hex(c: Color): Long = 0xFF000000L or
        (Math.round(c.red * 255f).toLong() shl 16) or (Math.round(c.green * 255f).toLong() shl 8) or Math.round(c.blue * 255f).toLong()

    private fun textPairs(s: ColorScheme): List<Triple<String, Color, Color>> = listOf(
        Triple("onPrimary/primary", s.onPrimary, s.primary),
        Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
        Triple("onSurface/surface", s.onSurface, s.surface),
        Triple("onSurfaceVariant/surface", s.onSurfaceVariant, s.surface),
        Triple("onSurfaceVariant/surfaceContainerLow", s.onSurfaceVariant, s.surfaceContainerLow),
        Triple("onSurfaceVariant/surfaceContainerHigh", s.onSurfaceVariant, s.surfaceContainerHigh),
        Triple("onSurface/surfaceContainerLow", s.onSurface, s.surfaceContainerLow),
        Triple("onSurface/surfaceContainerHighest", s.onSurface, s.surfaceContainerHighest),
        Triple("onError/error", s.onError, s.error),
        Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
        Triple("onSecondary/secondary", s.onSecondary, s.secondary),
        Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
        Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
        Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
        Triple("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
    )

    private fun check(name: String, s: ColorScheme) {
        for ((label, fg, bg) in textPairs(s)) {
            val c = contrast(fg, bg)
            assertTrue("$name $label contrast $c < 4.5", c >= 4.5)
        }
        assertTrue("$name primary/surface", contrast(s.primary, s.surface) >= 3.0)
        assertTrue("$name primary/surfaceContainerLow", contrast(s.primary, s.surfaceContainerLow) >= 3.0)
        assertTrue("$name outline/surface", contrast(s.outline, s.surface) >= 3.0)
        assertTrue("$name outline/surfaceContainerLow", contrast(s.outline, s.surfaceContainerLow) >= 3.0)
        assertTrue("$name error/surfaceContainerLow", contrast(s.error, s.surfaceContainerLow) >= 3.0)
    }

    @Test fun lightAnchors() {
        assertEquals(0xFF0B63D6L, hex(lightFallback.primary))
        assertEquals(0xFFFFFFFFL, hex(lightFallback.onPrimary))
        assertEquals(0xFFF9F9FFL, hex(lightFallback.surface))
        assertEquals(0xFFBA1A1AL, hex(lightFallback.error))
    }

    @Test fun darkAnchors() {
        assertEquals(0xFFA9C7FFL, hex(darkFallback.primary))
        assertEquals(0xFF002F65L, hex(darkFallback.onPrimary))
        assertEquals(0xFF111318L, hex(darkFallback.surface))
        assertEquals(0xFFFFB4ABL, hex(darkFallback.error))
    }

    @Test fun lightContrast() = check("light", lightFallback)

    @Test fun darkContrast() = check("dark", darkFallback)

    @Test fun everyRoleIsSetAndDarkDiffersFromLight() {
        // lightColorScheme/darkColorScheme default every role, so "set" means differs from the stock scheme
        // wherever the UI-SPEC gives a value; here: no role may be left fully transparent.
        for (s in listOf(lightFallback, darkFallback)) {
            for (c in listOf(
                s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary, s.secondary, s.onSecondary,
                s.secondaryContainer, s.onSecondaryContainer, s.tertiary, s.onTertiary, s.tertiaryContainer, s.onTertiaryContainer,
                s.background, s.onBackground, s.surface, s.onSurface, s.surfaceVariant, s.onSurfaceVariant, s.surfaceTint,
                s.inverseSurface, s.inverseOnSurface, s.error, s.onError, s.errorContainer, s.onErrorContainer, s.outline,
                s.outlineVariant, s.scrim, s.surfaceBright, s.surfaceDim, s.surfaceContainerLowest, s.surfaceContainerLow,
                s.surfaceContainer, s.surfaceContainerHigh, s.surfaceContainerHighest,
            )) assertEquals(1f, c.alpha, 0f)
        }
        assertTrue(luminance(darkFallback.surface) < luminance(lightFallback.surface))
    }
}
