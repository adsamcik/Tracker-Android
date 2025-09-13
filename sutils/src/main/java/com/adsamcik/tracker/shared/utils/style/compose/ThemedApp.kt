package com.adsamcik.tracker.shared.utils.style.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Static app theme with fixed seed colors - no runtime customization. */
@Composable
fun AppTheme(dark: Boolean, content: @Composable () -> Unit) {
    // Fixed theme colors - consistent brand identity without runtime complexity
    val seedPrimary = Color(0xFF6750A4)
    val seedSecondary = Color(0xFF625B71) 
    val seedTertiary = Color(0xFF7D5260)
    
    val scheme = if (dark) {
        darkColorScheme(
            primary = seedPrimary,
            secondary = seedSecondary,
            tertiary = seedTertiary
        )
    } else {
        lightColorScheme(
            primary = seedPrimary,
            secondary = seedSecondary,
            tertiary = seedTertiary
        )
    }
    
    MaterialTheme(colorScheme = scheme, content = content)
}
