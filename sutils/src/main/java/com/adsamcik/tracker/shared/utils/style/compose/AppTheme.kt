package com.adsamcik.tracker.shared.utils.style.compose

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Reduced motion preference. True when user has "Remove animations" enabled.
 * Check via `LocalReducedMotion.current` before animating.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Reduced transparency preference. True when glass should render opaque.
 * GlassSurface checks this automatically.
 */
val LocalReduceTransparency = staticCompositionLocalOf { false }

/**
 * Unified Ridgeline app theme.
 *
 * - Android 12+ (S): system dynamic colors (Monet) when [useDynamicColor] is true.
 * - Pre-Android 12: Ridgeline Canopy Green palette.
 *
 * Provides [LocalReducedMotion], [LocalReduceTransparency], and [LocalSemanticColors].
 */
@Composable
fun AppTheme(
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    reducedMotion: Boolean = false,
    reduceTransparency: Boolean = false,
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

    val semanticColors = remember(darkTheme) {
        if (darkTheme) {
            RidgelineSemanticColors(
                success = SuccessDark,
                onSuccess = OnSuccessDark,
                successContainer = SuccessContainerDark,
                onSuccessContainer = OnSuccessContainerDark,
                warning = WarningDark,
                onWarning = OnWarningDark,
                warningContainer = WarningContainerDark,
                onWarningContainer = OnWarningContainerDark,
            )
        } else {
            RidgelineSemanticColors(
                success = SuccessLight,
                onSuccess = OnSuccessLight,
                successContainer = SuccessContainerLight,
                onSuccessContainer = OnSuccessContainerLight,
                warning = WarningLight,
                onWarning = OnWarningLight,
                warningContainer = WarningContainerLight,
                onWarningContainer = OnWarningContainerLight,
            )
        }
    }

    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalReduceTransparency provides reduceTransparency,
        LocalSemanticColors provides semanticColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
