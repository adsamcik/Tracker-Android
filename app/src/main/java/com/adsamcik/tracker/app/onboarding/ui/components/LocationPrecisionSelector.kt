package com.adsamcik.tracker.app.onboarding.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator

/**
 * Location precision modes for tracking
 */
enum class LocationPrecisionMode {
    /**
     * Coarse/approximate location (ACCESS_COARSE_LOCATION)
     * - Network-based positioning (WiFi/cell towers)
     * - Accuracy: 100-500 meters
     * - Lower battery usage
     */
    APPROXIMATE,

    /**
     * Precise location (ACCESS_FINE_LOCATION)
     * - GPS-based positioning
     * - Accuracy: <10 meters
     * - Higher battery usage
     */
    PRECISE
}

/**
 * Precision mode selector for onboarding flow.
 * Allows users to choose between approximate and precise location tracking
 * with clear battery impact indicators and explanations.
 *
 * @param selectedMode Currently selected precision mode (null if none selected yet)
 * @param onModeSelected Callback when user selects a mode
 * @param modifier Optional modifier for the container
 */
@Composable
fun LocationPrecisionSelector(
    selectedMode: LocationPrecisionMode?,
    onModeSelected: (LocationPrecisionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.location_precision_selector_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Text(
            text = stringResource(R.string.location_precision_selector_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Approximate mode option
        PrecisionModeCard(
            mode = LocationPrecisionMode.APPROXIMATE,
            isSelected = selectedMode == LocationPrecisionMode.APPROXIMATE,
            onClick = { onModeSelected(LocationPrecisionMode.APPROXIMATE) }
        )

        // Precise mode option
        PrecisionModeCard(
            mode = LocationPrecisionMode.PRECISE,
            isSelected = selectedMode == LocationPrecisionMode.PRECISE,
            onClick = { onModeSelected(LocationPrecisionMode.PRECISE) }
        )
    }
}

/**
 * Card representing a single precision mode option
 */
@Composable
private fun PrecisionModeCard(
    mode: LocationPrecisionMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, titleRes, descriptionRes, batteryImpact) = when (mode) {
        LocationPrecisionMode.APPROXIMATE -> PrecisionModeInfo(
            icon = Icons.Default.GpsNotFixed,
            title = R.string.location_precision_approximate_title,
            description = R.string.location_precision_approximate_description,
            batteryImpact = BatteryImpact.LOW
        )
        LocationPrecisionMode.PRECISE -> PrecisionModeInfo(
            icon = Icons.Default.GpsFixed,
            title = R.string.location_precision_precise_title,
            description = R.string.location_precision_precise_description,
            batteryImpact = BatteryImpact.MODERATE
        )
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected)
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                BatteryImpactIndicator(
                    impact = batteryImpact,
                    compact = true
                )
            }
        }
    }
}

/**
 * Data class holding precision mode display information
 */
private data class PrecisionModeInfo(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: Int,
    val description: Int,
    val batteryImpact: BatteryImpact
)
