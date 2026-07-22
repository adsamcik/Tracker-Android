package com.adsamcik.tracker.dashboard.ui.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.dashboard.R
import com.adsamcik.tracker.dashboard.ui.compose.motion.MotionTokens
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.androidModelMslAltitudeM
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.utils.extension.formatDistance

/**
 * Three-level progressive disclosure card for sensor details.
 *
 * Disclosure levels:
 * - Level 0 (Casual): Just a header with expand chevron
 * - Level 1 (Enthusiast): Location accuracy, activity confidence, WiFi count, cell count
 * - Level 2 (Power User): Provider details, satellites, cell types, raw coordinates (debug only)
 *
 * @param collectionData Current collection data snapshot
 * @param isTracking Whether tracking is currently active
 * @param isDebugBuild Whether the current build is a debug build (controls raw coordinate display)
 */
@Composable
internal fun SensorDetailsCard(
	collectionData: CollectionData?,
	isTracking: Boolean,
	isDebugBuild: Boolean = false,
	modifier: Modifier = Modifier,
) {
	if (!isTracking || collectionData == null) return

	// 0 = collapsed, 1 = enthusiast, 2 = power user
	var disclosureLevel by rememberSaveable { mutableIntStateOf(0) }

	val context = LocalContext.current
	val settings = TrackerSettingsQuick.snapshot(context)

	Card(
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(
			containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
		),
	) {
		Column {
			// Header — always visible
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable(role = Role.Button) {
						disclosureLevel = (disclosureLevel + 1) % 3
					}
					.padding(16.dp),
				horizontalArrangement = Arrangement.SpaceBetween,
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = stringResource(R.string.dashboard_sensor_details_title),
					style = MaterialTheme.typography.labelLarge,
					fontWeight = FontWeight.Medium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Icon(
					imageVector = when (disclosureLevel) {
						0 -> Icons.Default.KeyboardArrowDown
						else -> Icons.Default.KeyboardArrowUp
					},
					contentDescription = when (disclosureLevel) {
						0 -> stringResource(R.string.dashboard_sensor_expand)
						else -> stringResource(R.string.dashboard_sensor_collapse)
					},
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}

			// Level 1: Enthusiast — summary metrics
			AnimatedVisibility(
				visible = disclosureLevel >= 1,
				enter = expandVertically(
					animationSpec = tween(MotionTokens.STANDARD_MS),
				),
				exit = shrinkVertically(
					animationSpec = tween(MotionTokens.QUICK_MS),
				),
			) {
				Column(
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					// Location accuracy
					val accuracy = collectionData.location?.horizontalAccuracy
					if (accuracy != null) {
						val accuracyText = "±${
							context.resources.formatDistance(
								accuracy, 0, settings.lengthSystem,
							)
						}"
						SensorDetailRow(
							icon = Icons.Default.MyLocation,
							label = stringResource(R.string.dashboard_sensor_location_accuracy),
							value = accuracyText,
						)
					}

					// Activity confidence
					val activity = collectionData.activity
					if (activity != null) {
						val activityName = activity.getGroupedActivityName(context)
						SensorDetailRow(
							icon = Icons.AutoMirrored.Filled.DirectionsWalk,
							label = stringResource(R.string.dashboard_sensor_activity),
							value = "$activityName (${activity.confidence}%)",
						)
					}

					// WiFi count
					val wifiCount = collectionData.wifi?.inRange?.size
					if (wifiCount != null && wifiCount > 0) {
						SensorDetailRow(
							icon = Icons.Default.Wifi,
							label = stringResource(R.string.dashboard_sensor_wifi),
							value = wifiCount.toString(),
						)
					}

					// Cell count
					val cellCount = collectionData.cell?.totalCount
					if (cellCount != null && cellCount > 0) {
						SensorDetailRow(
							icon = Icons.Default.CellTower,
							label = stringResource(R.string.dashboard_sensor_cell),
							value = cellCount.toString(),
						)
					}
				}
			}

			// Level 2: Power User — detailed breakdown
			AnimatedVisibility(
				visible = disclosureLevel >= 2,
				enter = expandVertically(
					animationSpec = tween(MotionTokens.STANDARD_MS),
				),
				exit = shrinkVertically(
					animationSpec = tween(MotionTokens.QUICK_MS),
				),
			) {
				Column(
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					HorizontalDivider(
						color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
					)
					Spacer(Modifier.height(4.dp))

					// Altitude
					val altitude = collectionData.androidModelMslAltitudeM
					if (altitude != null) {
						SensorDetailRow(
							icon = Icons.Default.Speed,
							label = stringResource(R.string.dashboard_sensor_altitude),
							value = context.resources.formatDistance(
								altitude, 1, settings.lengthSystem,
							),
						)
					}

					// Speed
					val speed = collectionData.location?.speed
					if (speed != null) {
						SensorDetailRow(
							icon = Icons.Default.Speed,
							label = stringResource(R.string.dashboard_sensor_speed),
							value = stringResource(R.string.dashboard_format_speed_ms, speed),
						)
					}

					// Cell type breakdown
					val cells = collectionData.cell?.registeredCells
					if (!cells.isNullOrEmpty()) {
						val typeBreakdown = cells.groupBy { it.type.name }
							.entries.joinToString { "${it.key}: ${it.value.size}" }
						SensorDetailRow(
							icon = Icons.Default.CellTower,
							label = stringResource(R.string.dashboard_sensor_cell_types),
							value = typeBreakdown,
						)
					}

					// Raw coordinates — debug only, privacy protected
					if (isDebugBuild) {
						val location = collectionData.location
						if (location != null) {
							SensorDetailRow(
								icon = Icons.Default.MyLocation,
								label = stringResource(R.string.dashboard_sensor_raw_coords),
								value = stringResource(
R.string.dashboard_format_coordinates,
location.latitude,
location.longitude,
),
							)
						}
					}
				}
			}
		}
	}
}

/**
 * Single row displaying a sensor metric: icon + label + value.
 */
@Composable
private fun SensorDetailRow(
	icon: ImageVector,
	label: String,
	value: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			modifier = Modifier.size(20.dp),
			tint = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier.weight(1f),
		)
		Text(
			text = value,
			style = MaterialTheme.typography.bodyMedium,
			fontWeight = FontWeight.Medium,
			color = MaterialTheme.colorScheme.onSurface,
		)
	}
}
