package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.adsamcik.tracker.shared.utils.style.StyleData

/**
 * Utility functions for working with StyleData in Compose
 */
object StyleUtils {
    
    /**
     * Get background color for a specific layer
     */
    @Composable
    fun getBackgroundColor(styleData: StyleData, layer: Int = 0, isInverted: Boolean = false): Color {
        return Color(styleData.backgroundColor(isInverted = isInverted, layer = layer))
    }
    
    /**
     * Get foreground color
     */
    @Composable
    fun getForegroundColor(styleData: StyleData, isInverted: Boolean = false): Color {
        return Color(styleData.foregroundColor(isInverted = isInverted))
    }
    
    /**
     * Check if the current style data represents a dark theme
     */
    fun isDarkTheme(styleData: StyleData): Boolean {
        val backgroundColor = Color(styleData.backgroundColor(isInverted = false))
        return backgroundColor.luminance() < 0.5f
    }
    
    /**
     * Get the primary color from the current color scheme
     */
    @Composable
    fun getPrimaryColor(colorScheme: ColorScheme): Color = colorScheme.primary
    
    /**
     * Get contrast color (black or white) based on background
     */
    fun getContrastColor(backgroundColor: Color): Color {
        return if (backgroundColor.luminance() > 0.5f) Color.Black else Color.White
    }
}

/**
 * Extension function to get a color with adjusted alpha
 */
fun Color.withAlpha(alpha: Float): Color = this.copy(alpha = alpha)
