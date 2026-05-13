package com.adsamcik.tracker.statistics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.adsamcik.tracker.map.presentation.udf.LatLngModel
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.utils.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.TripDetailInsights
import com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModel
import com.adsamcik.tracker.statistics.presenter.TripDetailState
import com.adsamcik.tracker.statistics.presenter.RouteEmptyReason
import com.adsamcik.tracker.statistics.presenter.resolveRouteEmptyReason
import com.adsamcik.tracker.stats.api.repository.TripSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val MS_TO_KMH = 3.6
private const val MS_TO_MPH = 2.236936
private const val METERS_TO_FEET = 3.28084
private const val METERS_PER_KILOMETER = 1000.0
private const val METERS_PER_MILE = 1609.344

/**
 * Entry composable for trip detail screen.
 * Receives tripId from navigation args.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailRoute(
	tripId: Long,
	onBack: () -> Unit,
	onViewOnMap: (Long, Long, Long) -> Unit = { _, _, _ -> },
	viewModel: TripDetailPresenterViewModel = hiltViewModel()
) {
	BackHandler(onBack = onBack)
	val state by viewModel.state.collectAsState()
	val loadedState = state as? TripDetailState.Loaded
	val skiSegments by viewModel.skiSegments.collectAsState()
	val insights by viewModel.insights.collectAsState()
	var showDeleteDialog by remember { mutableStateOf(false) }
	var showMenu by remember { mutableStateOf(false) }
	val context = LocalContext.current

	if (showDeleteDialog) {
		DeleteConfirmationDialog(
			onConfirm = {
				showDeleteDialog = false
				viewModel.deleteTrip(onDeleted = onBack)
			},
			onDismiss = { showDeleteDialog = false }
		)
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.trip_detail_title)) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(
							Icons.AutoMirrored.Filled.ArrowBack,
							contentDescription = stringResource(R.string.action_navigate_back)
						)
					}
				},
				actions = {
					if (loadedState != null) {
						Box {
							IconButton(onClick = { showMenu = true }) {
								Icon(
									Icons.Filled.MoreVert,
									contentDescription = stringResource(R.string.trip_detail_more_options)
								)
							}
							DropdownMenu(
								expanded = showMenu,
								onDismissRequest = { showMenu = false }
							) {
								DropdownMenuItem(
									text = { Text(stringResource(R.string.trip_detail_view_on_map)) },
									onClick = {
										showMenu = false
										onViewOnMap(
											loadedState.trip.id,
											loadedState.trip.startTimeMs.raw,
											loadedState.trip.endTimeMs.raw,
										)
									}
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.trip_detail_export_gpx)) },
									onClick = {
										showMenu = false
										viewModel.exportTripGpx(context)
									}
								)
								DropdownMenuItem(
									text = { Text(stringResource(R.string.trip_detail_delete)) },
									onClick = {
										showMenu = false
										showDeleteDialog = true
									}
								)
							}
						}
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background
				)
			)
		}
	) { contentPadding ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(contentPadding)
				.background(MaterialTheme.colorScheme.background)
		) {
			when (val s = state) {
				is TripDetailState.Loading -> {
					Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
						CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
					}
				}

				is TripDetailState.Loaded -> {
					TripOverview(
						trip = s.trip,
						insights = insights,
						skiSegments = skiSegments,
					)
				}

				is TripDetailState.NotFound -> {
					Box(
						Modifier
							.fillMaxSize()
							.padding(32.dp),
						contentAlignment = Alignment.Center
					) {
						EmptyStateCard(
							icon = Icons.Filled.ErrorOutline,
							title = stringResource(R.string.trip_detail_not_found),
							subtitle = stringResource(R.string.trip_detail_not_found_subtitle)
						)
					}
				}

				is TripDetailState.Error -> {
					Box(
						Modifier
							.fillMaxSize()
							.padding(32.dp),
						contentAlignment = Alignment.Center
					) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							EmptyStateCard(
								icon = Icons.Filled.ErrorOutline,
								title = stringResource(R.string.trip_detail_error_title),
								subtitle = s.message
							)
							Spacer(Modifier.height(16.dp))
							androidx.compose.material3.Button(onClick = { viewModel.retry() }) {
								Text(stringResource(R.string.trip_detail_retry))
							}
						}
					}
				}
			}
		}
	}
}

private val dateTimeFormatter: DateTimeFormatter by lazy {
	DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
}

@Composable
private fun TripOverview(
	trip: TripSummary,
	insights: TripDetailInsights,
	skiSegments: List<com.adsamcik.tracker.shared.base.database.data.SkiRunSegment> = emptyList()
) {
	val context = LocalContext.current
	val resources = context.resources
	val settings = remember { TrackerSettingsQuick.snapshot(context) }

	val stepCounterSupported = remember {
		context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_SENSOR_STEP_COUNTER)
	}

	val startText = remember(trip.startTimeMs) {
		dateTimeFormatter.format(
			Instant.ofEpochMilli(trip.startTimeMs.raw).atZone(ZoneId.systemDefault())
		)
	}
	val endText = remember(trip.endTimeMs) {
		dateTimeFormatter.format(
			Instant.ofEpochMilli(trip.endTimeMs.raw).atZone(ZoneId.systemDefault())
		)
	}
	val durationText = remember(trip.duration) {
		trip.duration.raw.formatAsDuration(context)
	}
	val distanceText = remember(trip.distance, settings) {
		resources.formatDistance(
			trip.distance.raw,
			digits = if (trip.distance.raw >= 1000f) 1 else 2,
			unit = settings.lengthSystem
		)
	}
	val averageSpeedText = remember(insights.averageSpeedMps, settings.lengthSystem) {
		insights.averageSpeedMps.formatSpeed(settings.lengthSystem)
	}
	val maxSpeedText = remember(insights.maxSpeedMps, settings.lengthSystem) {
		insights.maxSpeedMps.formatSpeed(settings.lengthSystem)
	}
	val paceText = remember(trip.distance.raw, trip.duration.raw, settings.lengthSystem) {
		formatPace(trip.distance.raw.toDouble(), trip.duration.raw, settings.lengthSystem)
	}
	val elevationGainText = remember(insights.elevationGainM, settings.lengthSystem) {
		insights.elevationGainM.formatElevation(settings.lengthSystem)
	}
	val elevationLossText = remember(insights.elevationLossM, settings.lengthSystem) {
		insights.elevationLossM.formatElevation(settings.lengthSystem)
	}
	val maxAltitudeText = remember(insights.maxAltitudeM, settings.lengthSystem) {
		insights.maxAltitudeM.formatElevation(settings.lengthSystem)
	}

	val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp + navBottom),
		verticalArrangement = Arrangement.spacedBy(16.dp)
	) {
		GlassCard(modifier = Modifier.fillMaxWidth()) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically
			) {
				Box(
					modifier = Modifier
						.size(56.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
					contentAlignment = Alignment.Center
				) {
					Icon(
						imageVector = Icons.Filled.Route,
						contentDescription = null,
						modifier = Modifier.size(28.dp),
						tint = MaterialTheme.colorScheme.primary
					)
				}
				Column(Modifier.padding(start = 16.dp)) {
					Text(
						text = insights.activityType,
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold,
						color = MaterialTheme.colorScheme.onSurface
					)
					Text(
						text = "$startText → $endText",
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant
					)
				}
			}
		}

		MetricRow(
			firstLabel = stringResource(R.string.trip_detail_duration),
			firstValue = durationText,
			secondLabel = stringResource(R.string.trip_detail_distance),
			secondValue = distanceText,
		)
		MetricRow(
			firstLabel = stringResource(R.string.trip_detail_steps),
			firstValue = if (trip.steps.raw > 0 || stepCounterSupported) {
				trip.steps.raw.formatReadable()
			} else {
				stringResource(R.string.stats_metric_not_available_short)
			},
			secondLabel = stringResource(R.string.trip_detail_avg_speed),
			secondValue = averageSpeedText,
		)
		MetricRow(
			firstLabel = stringResource(R.string.trip_detail_max_speed),
			firstValue = maxSpeedText,
			secondLabel = stringResource(R.string.trip_detail_pace),
			secondValue = paceText,
		)

		RoutePreviewCard(
			points = insights.routePoints,
			sourceLabel = insights.sourceLabel,
			hasDistance = trip.distance.raw > 0f,
		)
		TripFactsCard(
			activityType = insights.activityType,
			source = insights.sourceLabel,
			startTime = startText,
			endTime = endText,
			elevationGain = elevationGainText,
			elevationLoss = elevationLossText,
			maxAltitude = maxAltitudeText,
		)
		DeveloperMetrics(
			trip = trip,
			insights = insights,
			maxAltitudeText = maxAltitudeText,
		)

		if (skiSegments.isNotEmpty()) {
			com.adsamcik.tracker.statistics.ui.ski.SkiSessionDetailSection(
				segments = skiSegments,
				modifier = Modifier.fillMaxWidth()
			)
		}
	}
}

@Composable
private fun MetricRow(
	firstLabel: String,
	firstValue: String,
	secondLabel: String,
	secondValue: String,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		MetricCard(
			label = firstLabel,
			value = firstValue,
			modifier = Modifier.weight(1f)
		)
		MetricCard(
			label = secondLabel,
			value = secondValue,
			modifier = Modifier.weight(1f)
		)
	}
}

@Composable
private fun RoutePreviewCard(
	points: List<LatLngModel>,
	sourceLabel: String,
	hasDistance: Boolean,
) {
	val validPoints = remember(points) {
		points.filter { it.lat.isFinite() && it.lng.isFinite() }
	}
	val emptyReason = resolveRouteEmptyReason(
		routePointCount = validPoints.size,
		hasDistance = hasDistance,
		sourceLabel = sourceLabel,
	)
	GlassCard(modifier = Modifier.fillMaxWidth()) {
		Column {
			Text(
				text = stringResource(R.string.trip_detail_route_map),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface
			)
			Spacer(Modifier.height(12.dp))
			if (emptyReason != null) {
				Text(
					text = stringResource(
						when (emptyReason) {
							RouteEmptyReason.LEGACY_NO_ROUTE ->
								R.string.trip_detail_route_unavailable_legacy

							RouteEmptyReason.DISTANCE_NO_ROUTE ->
								R.string.trip_detail_route_no_gps_points

							RouteEmptyReason.NO_DATA ->
								R.string.trip_detail_no_location_data
						}
					),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			} else {
				TripRouteMapPreview(
					points = validPoints,
					modifier = Modifier
						.fillMaxWidth()
						.height(200.dp)
				)
			}
		}
	}
}

@Composable
private fun TripFactsCard(
	activityType: String,
	source: String,
	startTime: String,
	endTime: String,
	elevationGain: String,
	elevationLoss: String,
	maxAltitude: String,
) {
	GlassCard(modifier = Modifier.fillMaxWidth()) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			FactRow(
				label = stringResource(R.string.trip_detail_activity_type),
				value = activityType,
			)
			FactRow(
				label = stringResource(R.string.trip_detail_source),
				value = source,
			)
			FactRow(
				label = stringResource(R.string.trip_detail_start_time),
				value = startTime,
			)
			FactRow(
				label = stringResource(R.string.trip_detail_end_time),
				value = endTime,
			)
			FactRow(
				label = stringResource(R.string.trip_detail_elevation_gain),
				value = elevationGain,
			)
			FactRow(
				label = stringResource(R.string.trip_detail_elevation_loss),
				value = elevationLoss,
			)
			FactRow(
				label = stringResource(R.string.trip_detail_max_altitude),
				value = maxAltitude,
			)
		}
	}
}

@Composable
private fun FactRow(
	label: String,
	value: String,
) {
	Column {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant
		)
		Spacer(Modifier.height(2.dp))
		Text(
			text = value,
			style = MaterialTheme.typography.bodyLarge,
			color = MaterialTheme.colorScheme.onSurface
		)
	}
}

@Composable
private fun DeveloperMetrics(
	trip: TripSummary,
	insights: TripDetailInsights,
	maxAltitudeText: String,
) {
	var expanded by remember { mutableStateOf(false) }

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.heightIn(min = 48.dp)
			.clickable { expanded = !expanded }
			.padding(horizontal = 8.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.Center
	) {
		Text(
			text = stringResource(
				if (expanded) R.string.trip_detail_hide_details
				else R.string.trip_detail_show_details
			),
			style = MaterialTheme.typography.labelMedium,
			color = MaterialTheme.colorScheme.onSurface
		)
		Icon(
			imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
			contentDescription = null,
			modifier = Modifier.size(20.dp),
			tint = MaterialTheme.colorScheme.onSurface
		)
	}

	AnimatedVisibility(
		visible = expanded,
		enter = expandVertically(),
		exit = shrinkVertically()
	) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			MetricRow(
				firstLabel = stringResource(R.string.trip_detail_samples),
				firstValue = trip.sampleCount.toString(),
				secondLabel = stringResource(R.string.trip_detail_source),
				secondValue = insights.sourceLabel,
			)
			MetricRow(
				firstLabel = stringResource(R.string.trip_detail_activity_type),
				firstValue = insights.activityType,
				secondLabel = stringResource(R.string.trip_detail_max_altitude),
				secondValue = maxAltitudeText,
			)
		}
	}
}

@Composable
private fun DeleteConfirmationDialog(
	onConfirm: () -> Unit,
	onDismiss: () -> Unit
) {
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.trip_detail_delete_confirm_title)) },
		text = { Text(stringResource(R.string.trip_detail_delete_confirm_message)) },
		confirmButton = {
			TextButton(onClick = onConfirm) {
				Text(stringResource(R.string.trip_detail_delete_confirm))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.trip_detail_cancel))
			}
		}
	)
}

@Composable
private fun MetricCard(
	label: String,
	value: String,
	modifier: Modifier = Modifier
) {
	val isEmptyValue = value == "—" || value == "N/A"
	GlassCard(modifier = modifier) {
		Column(modifier = Modifier.fillMaxWidth()) {
			Text(
				text = label,
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)
			Spacer(Modifier.height(4.dp))
			Text(
				text = value,
				style = MaterialTheme.typography.titleLarge,
				fontWeight = if (isEmptyValue) FontWeight.Normal else FontWeight.Bold,
				color = if (isEmptyValue) {
					MaterialTheme.colorScheme.onSurfaceVariant
				} else {
					MaterialTheme.colorScheme.onSurface
				},
			)
		}
	}
}

private fun Double?.formatSpeed(lengthSystem: LengthSystem): String {
	val speed = this ?: return "—"
	if (speed <= 0.0) return "—"
	val converted = when (lengthSystem) {
		LengthSystem.Imperial -> speed * MS_TO_MPH
		else -> speed * MS_TO_KMH
	}
	val unit = when (lengthSystem) {
		LengthSystem.Imperial -> "mph"
		else -> "km/h"
	}
	return String.format(Locale.getDefault(), "%.1f %s", converted, unit)
}

private fun Double?.formatElevation(lengthSystem: LengthSystem): String {
	val elevation = this ?: return "—"
	val converted = when (lengthSystem) {
		LengthSystem.Imperial -> elevation * METERS_TO_FEET
		else -> elevation
	}
	val unit = when (lengthSystem) {
		LengthSystem.Imperial -> "ft"
		else -> "m"
	}
	return "${converted.roundToInt()} $unit"
}

internal fun formatPace(
	distanceMeters: Double,
	durationMs: Long,
	lengthSystem: LengthSystem,
): String {
	if (distanceMeters <= 0.0 || durationMs <= 0L) return "—"
	val unitDistance = when (lengthSystem) {
		LengthSystem.Imperial -> distanceMeters / METERS_PER_MILE
		else -> distanceMeters / METERS_PER_KILOMETER
	}
	if (unitDistance <= 0.0) return "—"

	val totalSeconds = (durationMs / 1000.0 / unitDistance).roundToInt()
	val unitLabel = when (lengthSystem) {
		LengthSystem.Imperial -> "mi"
		else -> "km"
	}
	if (totalSeconds == 0) return "< 0:01 / $unitLabel"
	val minutes = totalSeconds / 60
	val seconds = totalSeconds % 60
	return String.format(Locale.getDefault(), "%d:%02d / %s", minutes, seconds, unitLabel)
}
