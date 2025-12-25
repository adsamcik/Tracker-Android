package com.adsamcik.tracker.app.common.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R

/**
 * Battery impact level for tracking configurations.
 * Calculated based on location precision, update frequency, sensor count, etc.
 */
enum class BatteryImpact(
    val icon: ImageVector,
    val label: Int,
    val colorLight: Color,
    val colorDark: Color
) {
    LOW(
        icon = Icons.Default.BatteryFull,
        label = R.string.battery_impact_low,
        colorLight = Color(0xFF4CAF50), // Material Green 500
        colorDark = Color(0xFF81C784)   // Material Green 300
    ),
    MODERATE(
        icon = Icons.Default.Battery6Bar,
        label = R.string.battery_impact_moderate,
        colorLight = Color(0xFFFFA726), // Material Orange 400
        colorDark = Color(0xFFFFB74D)   // Material Orange 300
    ),
    HIGH(
        icon = Icons.Default.Battery2Bar,
        label = R.string.battery_impact_high,
        colorLight = Color(0xFFEF5350), // Material Red 400
        colorDark = Color(0xFFE57373)   // Material Red 300
    );

    /**
     * Get appropriate color based on current theme
     */
    @Composable
    fun getColor(isDark: Boolean = false): Color {
        return if (isDark) colorDark else colorLight
    }
}

/**
 * Battery impact indicator component showing icon + label.
 * Displays color-coded battery level with corresponding text.
 *
 * @param impact Battery impact level to display
 * @param modifier Optional modifier for the container
 * @param showLabel Whether to show text label (default true)
 * @param compact If true, uses smaller icon (default false)
 */
@Composable
fun BatteryImpactIndicator(
    impact: BatteryImpact,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    compact: Boolean = false
) {
    val isDark = !MaterialTheme.colorScheme.surface.value.equals(Color.White.value)
    val impactColor = impact.getColor(isDark)
    val iconSize = if (compact) 20.dp else 24.dp

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = impact.icon,
            contentDescription = stringResource(impact.label),
            tint = impactColor,
            modifier = Modifier.size(iconSize)
        )

        if (showLabel) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(impact.label),
                style = if (compact) 
                    MaterialTheme.typography.bodySmall 
                else 
                    MaterialTheme.typography.bodyMedium,
                color = impactColor
            )
        }
    }
}
