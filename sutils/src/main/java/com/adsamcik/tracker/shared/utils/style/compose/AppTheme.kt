package com.adsamcik.tracker.shared.utils.style.compose

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

private val ExpressiveSeedColor = AppColors.NeonLime

/**
 * Unified app theme using Material 3 with dynamic color support and a Material Expressive fallback.
 *
 * - Android 12+ (S): system dynamic colors (Monet) when [useDynamicColor] is true.
 * - Pre-Android 12: generated Expressive palette derived from [seedColor].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppTheme(
    useDynamicColor: Boolean = true,
    darkTheme: Boolean = isSystemInDarkTheme(),
    seedColor: Color = ExpressiveSeedColor,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val colorScheme = remember(useDynamicColor, darkTheme, seedColor, context) {
        when {
            useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

            else -> dynamicColorScheme(
                seedColor = seedColor,
                isDark = darkTheme,
                style = PaletteStyle.Expressive
            )
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = TrackerShapes,
        typography = TrackerTypography,
        content = content
    )
}
