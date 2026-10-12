package com.dylenq.eq.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** True when the system animation scale is 0 (reduced motion). Provided by [EqTheme]. */
val LocalReducedMotion = compositionLocalOf { false }

/** The reduced-motion flag from [EqTheme] (UI-SPEC "Reduced motion"). */
@Composable
fun rememberReducedMotion(): Boolean = LocalReducedMotion.current

/** The colour scheme [EqTheme] uses for the given mode: dynamic on API 31+, the committed fallback below. */
@Composable
fun eqColorScheme(dark: Boolean): ColorScheme {
    val ctx = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkFallback
        else -> lightFallback
    }
}

/** Only the five styles the UI-SPEC "Typography" table uses are overridden. */
internal val EqTypography: Typography = Typography().let { d ->
    d.copy(
        titleLarge = d.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal),
        titleMedium = d.titleMedium.copy(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
        bodyMedium = d.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        labelLarge = d.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelSmall = d.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    )
}

internal val EqShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The only theme wrapper (UI-SPEC "Design System", GFX-06): follows the system light/dark mode, dynamic
 * colour on Android 12+ and the committed fallback scheme below; no in-app override (L-09).
 */
@Composable
fun EqTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val reduced = remember(ctx) {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = eqColorScheme(isSystemInDarkTheme()), typography = EqTypography, shapes = EqShapes, content = content)
    }
}
