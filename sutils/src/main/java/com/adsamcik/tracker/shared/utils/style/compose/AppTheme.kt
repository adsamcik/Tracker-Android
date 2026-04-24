package com.adsamcik.tracker.shared.utils.style.compose

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Reduced motion preference. True when user has "Remove animations" enabled.
 * Check via `LocalReducedMotion.current` before animating.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Unified Ridgeline app theme.
 *
 * - Android 12+ (S): system dynamic colors (Monet) when [useDynamicColor] is true.
 * - Pre-Android 12: Ridgeline Canopy Green palette.
 *
 * Provides [LocalReducedMotion].
 */
@Composable
fun AppTheme(
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = remember(useDynamicColor, darkTheme, context) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) {
                    androidx.compose.material3.dynamicDarkColorScheme(context)
                } else {
                    androidx.compose.material3.dynamicLightColorScheme(context)
                }

            else -> if (darkTheme) DarkColorScheme else LightColorScheme
        }
    }

    // Keep status-bar and navigation-bar icon appearance in sync with the theme so icons
    // stay legible against app backgrounds (pre-Android 12 static scheme, and as a safety
    // net on Android 12+ when dynamic color is disabled).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
