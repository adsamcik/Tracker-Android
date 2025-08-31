package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import com.adsamcik.tracker.shared.utils.style.StyleData
import com.adsamcik.tracker.shared.utils.style.StyleManager

/**
 * Tracker app theme that integrates with the existing StyleManager system
 */
@Composable
fun TrackerTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val styleData = StyleManager.styleData
    val isSystemDark = isSystemInDarkTheme()
    
    // Generate color scheme from current StyleData
    val colorScheme = remember(styleData, isSystemDark) {
        createColorScheme(styleData, isSystemDark)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

/**
 * Creates a Material 3 color scheme from StyleData
 */
internal fun createColorScheme(styleData: StyleData, isSystemDark: Boolean) = 
    if (isSystemDark) {
        createDarkColorScheme(styleData)
    } else {
        createLightColorScheme(styleData)
    }

/**
 * Creates a light color scheme from StyleData
 */
private fun createLightColorScheme(styleData: StyleData) = lightColorScheme(
    primary = Color(derivePrimaryColor(styleData, false)),
    onPrimary = Color(styleData.foregroundColor(isInverted = false)),
    primaryContainer = Color(derivePrimaryContainer(styleData, false)),
    onPrimaryContainer = Color(styleData.foregroundColor(isInverted = false)),
    
    secondary = Color(deriveSecondaryColor(styleData, false)),
    onSecondary = Color(styleData.foregroundColor(isInverted = false)),
    secondaryContainer = Color(deriveSecondaryContainer(styleData, false)),
    onSecondaryContainer = Color(styleData.foregroundColor(isInverted = false)),
    
    tertiary = Color(deriveTertiaryColor(styleData, false)),
    onTertiary = Color(styleData.foregroundColor(isInverted = false)),
    tertiaryContainer = Color(deriveTertiaryContainer(styleData, false)),
    onTertiaryContainer = Color(styleData.foregroundColor(isInverted = false)),
    
    background = Color(styleData.backgroundColor(isInverted = false, layer = 0)),
    onBackground = Color(styleData.foregroundColor(isInverted = false)),
    surface = Color(styleData.backgroundColor(isInverted = false, layer = 1)),
    onSurface = Color(styleData.foregroundColor(isInverted = false)),
    surfaceVariant = Color(styleData.backgroundColor(isInverted = false, layer = 2)),
    onSurfaceVariant = Color(styleData.foregroundColor(isInverted = false)),
    
    error = Color(0xFFB00020),
    onError = Color.White,
    errorContainer = Color(0xFFFDE7E9),
    onErrorContainer = Color(0xFF410002),
    
    outline = Color(deriveOutlineColor(styleData, false)),
    outlineVariant = Color(deriveOutlineVariant(styleData, false)),
    scrim = Color.Black.copy(alpha = 0.32f)
)

/**
 * Creates a dark color scheme from StyleData  
 */
private fun createDarkColorScheme(styleData: StyleData) = darkColorScheme(
    primary = Color(derivePrimaryColor(styleData, true)),
    onPrimary = Color(styleData.foregroundColor(isInverted = true)),
    primaryContainer = Color(derivePrimaryContainer(styleData, true)),
    onPrimaryContainer = Color(styleData.foregroundColor(isInverted = true)),
    
    secondary = Color(deriveSecondaryColor(styleData, true)),
    onSecondary = Color(styleData.foregroundColor(isInverted = true)),
    secondaryContainer = Color(deriveSecondaryContainer(styleData, true)),
    onSecondaryContainer = Color(styleData.foregroundColor(isInverted = true)),
    
    tertiary = Color(deriveTertiaryColor(styleData, true)),
    onTertiary = Color(styleData.foregroundColor(isInverted = true)),
    tertiaryContainer = Color(deriveTertiaryContainer(styleData, true)),
    onTertiaryContainer = Color(styleData.foregroundColor(isInverted = true)),
    
    background = Color(styleData.backgroundColor(isInverted = true, layer = 0)),
    onBackground = Color(styleData.foregroundColor(isInverted = true)),
    surface = Color(styleData.backgroundColor(isInverted = true, layer = 1)),
    onSurface = Color(styleData.foregroundColor(isInverted = true)),
    surfaceVariant = Color(styleData.backgroundColor(isInverted = true, layer = 2)),
    onSurfaceVariant = Color(styleData.foregroundColor(isInverted = true)),
    
    error = Color(0xFFCF6679),
    onError = Color.Black,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    
    outline = Color(deriveOutlineColor(styleData, true)),
    outlineVariant = Color(deriveOutlineVariant(styleData, true)),
    scrim = Color.Black.copy(alpha = 0.32f)
)

// Helper functions to derive colors from StyleData

private fun derivePrimaryColor(styleData: StyleData, isDark: Boolean): Int {
    val baseColor = styleData.backgroundColor(isInverted = false)
    
    // Create a primary color by adjusting hue slightly and increasing saturation
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(baseColor, hsl)
    
    // Adjust for primary color characteristics
    hsl[0] = (hsl[0] + 15f) % 360f // Shift hue slightly
    hsl[1] = (hsl[1] * 1.2f).coerceAtMost(1f) // Increase saturation
    hsl[2] = if (isDark) 0.8f else 0.4f // Adjust lightness for theme
    
    return ColorUtils.HSLToColor(hsl)
}

private fun derivePrimaryContainer(styleData: StyleData, isDark: Boolean): Int {
    val baseColor = styleData.backgroundColor(isInverted = false)
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(baseColor, hsl)
    
    hsl[0] = (hsl[0] + 15f) % 360f
    hsl[1] = (hsl[1] * 0.8f).coerceAtMost(1f)
    hsl[2] = if (isDark) 0.2f else 0.9f
    
    return ColorUtils.HSLToColor(hsl)
}

private fun deriveSecondaryColor(styleData: StyleData, isDark: Boolean): Int {
    val baseColor = styleData.backgroundColor(isInverted = false)
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(baseColor, hsl)
    
    hsl[0] = (hsl[0] + 60f) % 360f // Complementary hue shift
    hsl[1] = (hsl[1] * 0.9f).coerceAtMost(1f)
    hsl[2] = if (isDark) 0.7f else 0.5f
    
    return ColorUtils.HSLToColor(hsl)
}

private fun deriveSecondaryContainer(styleData: StyleData, isDark: Boolean): Int {
    val baseColor = styleData.backgroundColor(isInverted = false)
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(baseColor, hsl)
    
    hsl[0] = (hsl[0] + 60f) % 360f
    hsl[1] = (hsl[1] * 0.6f).coerceAtMost(1f)
    hsl[2] = if (isDark) 0.25f else 0.85f
    
    return ColorUtils.HSLToColor(hsl)
}

private fun deriveTertiaryColor(styleData: StyleData, isDark: Boolean): Int {
    val baseColor = styleData.backgroundColor(isInverted = false)
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(baseColor, hsl)
    
    hsl[0] = (hsl[0] + 120f) % 360f // Triadic hue shift
    hsl[1] = (hsl[1] * 0.8f).coerceAtMost(1f)
    hsl[2] = if (isDark) 0.6f else 0.6f
    
    return ColorUtils.HSLToColor(hsl)
}

private fun deriveTertiaryContainer(styleData: StyleData, isDark: Boolean): Int {
    val baseColor = styleData.backgroundColor(isInverted = false)
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(baseColor, hsl)
    
    hsl[0] = (hsl[0] + 120f) % 360f
    hsl[1] = (hsl[1] * 0.5f).coerceAtMost(1f)
    hsl[2] = if (isDark) 0.3f else 0.8f
    
    return ColorUtils.HSLToColor(hsl)
}

private fun deriveOutlineColor(styleData: StyleData, isDark: Boolean): Int {
    val foregroundColor = styleData.foregroundColor(isInverted = isDark)
    return ColorUtils.setAlphaComponent(foregroundColor, (255 * 0.3f).toInt())
}

private fun deriveOutlineVariant(styleData: StyleData, isDark: Boolean): Int {
    val foregroundColor = styleData.foregroundColor(isInverted = isDark)
    return ColorUtils.setAlphaComponent(foregroundColor, (255 * 0.15f).toInt())
}
