package com.adsamcik.tracker.app.common.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
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
) {
    LOW(
        icon = Icons.Default.BatteryFull,
        label = R.string.battery_impact_low,
    ),
    MODERATE(
        icon = Icons.Default.Battery6Bar,
        label = R.string.battery_impact_moderate,
    ),
    HIGH(
        icon = Icons.Default.Battery2Bar,
        label = R.string.battery_impact_high,
    );

    companion object {
        // Semantic colors for impacts without a direct Material theme equivalent
        val LowLight = Color(0xFF4CAF50)
        val LowDark = Color(0xFF81C784)
        val ModerateLight = Color(0xFFFFA726)
        val ModerateDark = Color(0xFFFFB74D)
    }
}

/**
 * Theme-aware color for battery impact level.
 * HIGH uses [MaterialTheme.colorScheme.error]; others use semantic constants.
 */
@Composable
fun BatteryImpact.color(): Color {
    val isDark = isSystemInDarkTheme()
    return when (this) {
        BatteryImpact.LOW -> if (isDark) BatteryImpact.LowDark else BatteryImpact.LowLight
        BatteryImpact.MODERATE -> if (isDark) BatteryImpact.ModerateDark else BatteryImpact.ModerateLight
        BatteryImpact.HIGH -> MaterialTheme.colorScheme.error
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
    val impactColor = impact.color()
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

/**
 * Warning card shown for high battery impact configurations.
 */
@Composable
fun BatteryImpactWarning(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = stringResource(com.adsamcik.tracker.tracker.R.string.battery_impact_high_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
