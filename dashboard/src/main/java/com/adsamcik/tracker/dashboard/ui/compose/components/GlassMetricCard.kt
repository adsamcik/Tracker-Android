package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R

/**
 * Frosted glass-style metric card for key dashboard values.
 *
 * Layout: icon on left, label+value stacked center, trend arrow far right.
 * Touch target >= 48dp.
 *
 * @param icon Leading icon for the metric category
 * @param label Short metric label (e.g. "Distance")
 * @param value Formatted metric value (e.g. "4.2 km")
 * @param unit Optional unit suffix displayed after value
 * @param trend Positive = upward trend, negative = downward, null = hidden
 */
@Composable
internal fun GlassMetricCard(
	icon: ImageVector,
	label: String,
	value: String,
	unit: String?,
	trend: Float?,
	modifier: Modifier = Modifier,
) {
	Surface(
		modifier = modifier
			.fillMaxWidth()
			.defaultMinSize(minHeight = 48.dp),
		shape = GlassMetricShape,
		color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
			.copy(alpha = 0.85f),
		border = BorderStroke(
			width = 1.dp,
			color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
		),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			// Leading icon
			Icon(
				imageVector = icon,
				contentDescription = null,
				modifier = Modifier.size(24.dp),
				tint = MaterialTheme.colorScheme.primary,
			)

			// Label + Value
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = label,
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Row(
					verticalAlignment = Alignment.Bottom,
					horizontalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Text(
						text = value,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface,
					)
					if (unit != null) {
						Text(
							text = unit,
							style = MaterialTheme.typography.labelSmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}

			// Trend arrow
			if (trend != null && trend != 0f) {
				val isPositive = trend > 0f
				Icon(
					imageVector = if (isPositive) {
						Icons.AutoMirrored.Filled.TrendingUp
					} else {
						Icons.AutoMirrored.Filled.TrendingDown
					},
					contentDescription = if (isPositive) {
						stringResource(R.string.dashboard_trend_up)
					} else {
						stringResource(R.string.dashboard_trend_down)
					},
					modifier = Modifier.size(20.dp),
					tint = if (isPositive) {
						MaterialTheme.colorScheme.tertiary
					} else {
						MaterialTheme.colorScheme.error
					},
				)
			}
		}
	}
}

private val GlassMetricShape = RoundedCornerShape(16.dp)
