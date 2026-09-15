package com.adsamcik.tracker.statistics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Route
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adsamcik.tracker.feature.map.api.preview.RoutePreviewRenderer
import com.adsamcik.tracker.feature.map.api.preview.RoutePoint
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.base.extension.formatReadable
import com.adsamcik.tracker.shared.preferences.settings.TrackerSettingsQuick
import com.adsamcik.tracker.shared.preferences.type.LengthSystem
import com.adsamcik.tracker.shared.preferences.extension.formatDistance
import com.adsamcik.tracker.shared.utils.style.compose.EmptyStateCard
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.TripDetailInsights
import com.adsamcik.tracker.statistics.presenter.TripDetailPresenterViewModel
import com.adsamcik.tracker.statistics.presenter.TripDetailSourcePresentation
import com.adsamcik.tracker.statistics.presenter.TripDetailState
import com.adsamcik.tracker.statistics.presenter.TripDetailStepsState
import com.adsamcik.tracker.statistics.presenter.hasUnavailableSourceActions
import com.adsamcik.tracker.statistics.presenter.RouteEmptyReason
import com.adsamcik.tracker.statistics.presenter.resolveRouteEmptyReason
import com.adsamcik.tracker.statistics.presenter.supportsLocationPresentation
import com.adsamcik.tracker.stats.api.repository.PressureHistory
import com.adsamcik.tracker.stats.api.repository.PressureHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.PressureHistoryPresentationState
import com.adsamcik.tracker.stats.api.repository.SourceOnlyHistoryIntent
import com.adsamcik.tracker.stats.api.repository.TrackingHistoryUnavailableReason
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.shared.model.SegmentSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val MS_TO_KMH = 3.6
private const val MS_TO_MPH = 2.236936
private const val MS_TO_KNOTS = 1.9438444924
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
	routePreviewRenderer: RoutePreviewRenderer,
	onViewOnMap: (Long, Long, Long) -> Unit = { _, _, _ -> },
	viewModel: TripDetailPresenterViewModel = hiltViewModel()
) {
	BackHandler(onBack = onBack)
	val state by viewModel.state.collectAsStateWithLifecycle()
	val loadedState = state as? TripDetailState.Loaded
	val skiSegments by viewModel.skiSegments.collectAsStateWithLifecycle()
	val insights by viewModel.insights.collectAsStateWithLifecycle()
	var showMenu by remember { mutableStateOf(false) }
	val context = LocalContext.current
	LaunchedEffect(loadedState?.trip, loadedState?.sourcePresentation) {
		if (loadedState != null) {
			viewModel.loadSupplementalData()
		}
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
					if (loadedState != null && loadedState.supportsLocationPresentation &&
						loadedState.trip.source != SegmentSource.PORTABLE_STEPS_IMPORT
					) {
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
								TripDetailActions(
									onViewOnMap = {
										showMenu = false
										onViewOnMap(
											loadedState.trip.id,
											loadedState.trip.startTimeMs.raw,
											loadedState.trip.endTimeMs.raw,
										)
									},
									onExportGpx = {
										showMenu = false
										viewModel.exportTripGpx(context)
									},
								)
							}
						}
					} else if (loadedState != null &&
						(loadedState.hasUnavailableSourceActions ||
							loadedState.trip.source == SegmentSource.PORTABLE_STEPS_IMPORT)
					) {
						Box {
							IconButton(onClick = { showMenu = true }) {
								Icon(
									Icons.Filled.MoreVert,
									contentDescription = stringResource(
										R.string.trip_detail_more_options,
									),
								)
							}
							DropdownMenu(
								expanded = showMenu,
								onDismissRequest = { showMenu = false },
							) {
								TripDetailUnavailableActions()
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
					if (s.sourcePresentation is TripDetailSourcePresentation.ImportedSteps ||
						s.trip.source == SegmentSource.PORTABLE_STEPS_IMPORT
					) {
						ImportedStepsOverview(s.trip, s.steps, viewModel::retry)
					} else {
						TripOverview(
							trip = s.trip,
							steps = s.steps,
							sourcePresentation = s.sourcePresentation,
							insights = insights,
							skiSegments = skiSegments,
							routePreviewRenderer = routePreviewRenderer,
							onRetrySteps = viewModel::retry,
						)
					}
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

/**
 * Trip Detail remains read-only until selected-session deletion can retract every source fact.
 */
@Composable
@Suppress("FunctionNaming") // Internal only so Compose tests can verify the safe action set.
internal fun TripDetailActions(
	onViewOnMap: () -> Unit,
	onExportGpx: () -> Unit,
) {
	DropdownMenuItem(
		text = { Text(stringResource(R.string.trip_detail_view_on_map)) },
		onClick = onViewOnMap,
	)
	DropdownMenuItem(
		text = { Text(stringResource(R.string.trip_detail_export_gpx)) },
		onClick = onExportGpx,
	)
}

/** Source-local delete/export contracts are not yet bound to this selected physical row. */
@Composable
@Suppress("FunctionNaming") // Internal only so Compose tests can guard physical action authority.
internal fun TripDetailUnavailableActions() {
	DropdownMenuItem(
		text = { Text(stringResource(R.string.trip_detail_source_actions_unavailable)) },
		onClick = {},
		enabled = false,
	)
}

private val dateTimeFormatter: DateTimeFormatter by lazy {
	DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
}

/** Retained imported Steps do not establish distance, activity, route, or elapsed-time coverage. */
@Composable
@Suppress("FunctionNaming")
internal fun ImportedStepsOverview(
	trip: TripSummary,
	steps: TripDetailStepsState,
	onRetrySteps: () -> Unit,
) {
	val zone = ZoneId.systemDefault()
	val startText = dateTimeFormatter.format(Instant.ofEpochMilli(trip.startTimeMs.raw).atZone(zone))
	val endText = dateTimeFormatter.format(Instant.ofEpochMilli(trip.endTimeMs.raw).atZone(zone))
	val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	Column(
		modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
			.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp + navBottom),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(stringResource(R.string.trip_detail_imported_steps), style = MaterialTheme.typography.titleMedium)
		Text("$startText → $endText", style = MaterialTheme.typography.bodyMedium)
		MetricCard(
			label = stringResource(R.string.trip_detail_steps),
			value = steps.metricValue(),
			supportingText = steps.metricStatus(),
			modifier = Modifier.fillMaxWidth(),
		)
		Text(
			stringResource(R.string.trip_detail_imported_steps_scope),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		TripDetailStepsRetry(visible = steps == TripDetailStepsState.Failed, onRetry = onRetrySteps)
	}
}

@Composable
private fun TripOverview(
	trip: TripSummary,
	steps: TripDetailStepsState,
	sourcePresentation: TripDetailSourcePresentation,
	insights: TripDetailInsights,
	skiSegments: List<com.adsamcik.tracker.shared.model.SkiRunSegment> = emptyList(),
	routePreviewRenderer: RoutePreviewRenderer,
	onRetrySteps: () -> Unit,
) {
	when (sourcePresentation) {
		TripDetailSourcePresentation.Resolving -> {
			TripDetailSourceResolving()
			return
		}
		TripDetailSourcePresentation.Failed -> {
			TripDetailSourceFailure(onRetry = onRetrySteps)
			return
		}
		is TripDetailSourcePresentation.Unavailable -> {
			TripDetailSourceFailure(
				onRetry = onRetrySteps,
				reason = sourcePresentation.reason,
				source = sourcePresentation.source,
			)
			return
		}
		is TripDetailSourcePresentation.PressureOnly -> {
			TripDetailPressureOverview(
				trip = trip,
				pressure = sourcePresentation.pressure,
			)
			return
		}
		is TripDetailSourcePresentation.ActivityOnly -> {
			TripDetailActivityOverview(
				activity = sourcePresentation.activity,
				intent = SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY,
			)
			return
		}
		is TripDetailSourcePresentation.WifiOnly -> {
			TripDetailWifiOverview(
				history = sourcePresentation.history,
				intent = SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY,
			)
			return
		}
		is TripDetailSourcePresentation.CellOnly -> {
			TripDetailCellOverview(
				history = sourcePresentation.history,
				intent = SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY,
			)
			return
		}
		is TripDetailSourcePresentation.StepsOnly -> {
			TripDetailStepsOverview(
				trip = trip,
				stepsHistory = sourcePresentation.steps,
				stepsState = steps,
				onRetry = onRetrySteps,
			)
			return
		}
		is TripDetailSourcePresentation.CapturedWithoutLocation -> {
			TripDetailCapturedWithoutLocationOverview(
				trip = trip,
				capturedSources = sourcePresentation.capturedSources,
			)
			return
		}
		is TripDetailSourcePresentation.ImportedSteps -> {
			ImportedStepsOverview(trip, steps, onRetrySteps)
			return
		}
		TripDetailSourcePresentation.LegacyUnverifiable,
		TripDetailSourcePresentation.Standard -> Unit
	}

	val context = LocalContext.current
	val resources = context.resources
	val settings = remember { TrackerSettingsQuick.snapshot(context) }

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
		if (sourcePresentation == TripDetailSourcePresentation.LegacyUnverifiable) {
			TripDetailLegacyCaptureNotice()
		}
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
			firstValue = steps.metricValue(),
			firstSupportingText = steps.metricStatus(),
			secondLabel = stringResource(R.string.trip_detail_avg_speed),
			secondValue = averageSpeedText,
		)
		TripDetailStepsRetry(
			visible = steps == TripDetailStepsState.Failed,
			onRetry = onRetrySteps,
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
			routePreviewRenderer = routePreviewRenderer,
		)
		TripFactsCard(
			activityType = insights.activityType,
			source = insights.sourceLabel,
			startTime = startText,
			endTime = endText,
			elevationGain = elevationGainText,
			elevationLoss = elevationLossText,
			maxAltitude = maxAltitudeText,
			sampleCount = trip.sampleCount,
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
private fun TripDetailSourceResolving() {
	Box(
		modifier = Modifier.fillMaxSize(),
		contentAlignment = Alignment.Center,
	) {
		CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
	}
}

/** Fail-closed captured-source error surface; retry is the only exposed product action. */
@Composable
@Suppress("FunctionNaming") // Internal so Compose tests can guard the failure affordances.
internal fun TripDetailSourceFailure(onRetry: () -> Unit) {
	TripDetailSourceFailure(onRetry = onRetry, reason = null, source = null)
}

@Composable
private fun TripDetailSourceFailure(
	onRetry: () -> Unit,
	reason: TrackingHistoryUnavailableReason?,
	source: com.adsamcik.tracker.stats.api.repository.HistorySource?,
) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.padding(32.dp)
			.testTag("trip_detail_source_failed"),
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			EmptyStateCard(
				icon = Icons.Filled.ErrorOutline,
				title = stringResource(R.string.trip_detail_source_failed),
				subtitle = when {
					reason == null -> stringResource(R.string.trip_detail_source_failed_subtitle)
					source == null -> stringResource(reason.detailMessageResource)
					else -> stringResource(
						R.string.trip_detail_source_failed_for_source,
						stringResource(source.detailLabelResource),
						stringResource(reason.detailMessageResource),
					)
				},
			)
			Spacer(Modifier.height(16.dp))
			androidx.compose.material3.Button(onClick = onRetry) {
				Text(stringResource(R.string.trip_detail_retry))
			}
		}

		private val TrackingHistoryUnavailableReason.detailMessageResource: Int
			get() = when (this) {
				TrackingHistoryUnavailableReason.SOURCE_EVIDENCE_STATE_UNAVAILABLE ->
					R.string.trip_detail_source_failure_evidence
				TrackingHistoryUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED ->
					R.string.trip_detail_source_failure_budget
				TrackingHistoryUnavailableReason.SOURCE_INTEGRITY_FAILURE ->
					R.string.trip_detail_source_failure_integrity
				TrackingHistoryUnavailableReason.PHYSICAL_MEMBERSHIP_INVALID ->
					R.string.trip_detail_source_failure_membership
			}

		private val com.adsamcik.tracker.stats.api.repository.HistorySource.detailLabelResource: Int
			get() = when (this) {
				com.adsamcik.tracker.stats.api.repository.HistorySource.LOCATION ->
					R.string.trip_detail_source_location
				com.adsamcik.tracker.stats.api.repository.HistorySource.WIFI ->
					R.string.trip_detail_source_wifi
				com.adsamcik.tracker.stats.api.repository.HistorySource.CELL ->
					R.string.trip_detail_source_cell
				com.adsamcik.tracker.stats.api.repository.HistorySource.ACTIVITY ->
					R.string.trip_detail_source_activity
				com.adsamcik.tracker.stats.api.repository.HistorySource.STEPS ->
					R.string.trip_detail_source_steps
				com.adsamcik.tracker.stats.api.repository.HistorySource.PRESSURE ->
					R.string.trip_detail_source_pressure
			}
	}
}

/** Contained selected-detail surface for authenticated exact Pressure-only capture history. */
@Composable
@Suppress("FunctionNaming") // Internal so Compose tests can guard the source-only surface.
internal fun TripDetailPressureOverview(
	trip: TripSummary,
	pressure: PressureHistory,
) {
	val context = LocalContext.current
	val startText = remember(trip.startTimeMs) {
		dateTimeFormatter.format(
			Instant.ofEpochMilli(trip.startTimeMs.raw).atZone(ZoneId.systemDefault()),
		)
	}
	val endText = remember(trip.endTimeMs) {
		dateTimeFormatter.format(
			Instant.ofEpochMilli(trip.endTimeMs.raw).atZone(ZoneId.systemDefault()),
		)
	}
	val durationText = remember(trip.duration) {
		trip.duration.raw.formatAsDuration(context)
	}
	val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp + navBottom)
			.testTag("trip_detail_pressure_only"),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		GlassCard(modifier = Modifier.fillMaxWidth()) {
			Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(
					text = stringResource(R.string.trip_detail_pressure_session),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Text(
					text = "$startText → $endText",
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		MetricCard(
			label = stringResource(R.string.trip_detail_duration),
			value = durationText,
			modifier = Modifier.fillMaxWidth(),
		)
		TripDetailPressureCard(pressure)
	}
}

@Composable
@Suppress("FunctionNaming") // Internal so Compose tests can verify null and partial truthfulness.
internal fun TripDetailPressureCard(pressure: PressureHistory) {
	val summary = pressure.summary
	GlassCard(
		modifier = Modifier
			.fillMaxWidth()
			.testTag("trip_detail_pressure_card"),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
			Text(
				text = stringResource(R.string.trip_detail_pressure_title),
				style = MaterialTheme.typography.titleMedium,
				fontWeight = FontWeight.SemiBold,
				color = MaterialTheme.colorScheme.onSurface,
			)
			FactRow(
				label = stringResource(R.string.trip_detail_pressure_state),
				value = stringResource(pressure.presentationState.labelResource),
			)
			if (summary != null) {
				MetricRow(
					firstLabel = stringResource(R.string.trip_detail_pressure_latest),
					firstValue = formatPressure(summary.latestHectopascals),
					secondLabel = stringResource(R.string.trip_detail_pressure_range),
					secondValue = formatPressureRange(
						summary.minimumHectopascals,
						summary.maximumHectopascals,
					),
				)
				FactRow(
					label = stringResource(R.string.trip_detail_pressure_change),
					value = formatPressureChange(
						summary.latestHectopascals - summary.firstHectopascals,
					),
				)
			}
			FactRow(
				label = stringResource(R.string.trip_detail_pressure_coverage),
				value = stringResource(pressure.coverage.labelResource),
			)
			if (summary != null) {
				FactRow(
					label = stringResource(R.string.trip_detail_pressure_zones),
					value = pressure.zoneAuthorities.joinToString(),
				)
			}
		}
	}
}

private val PressureHistoryPresentationState.labelResource: Int
	get() = when (this) {
		PressureHistoryPresentationState.MATERIALIZING ->
			R.string.trip_detail_pressure_materializing
		PressureHistoryPresentationState.PARTIAL -> R.string.trip_detail_pressure_partial
		PressureHistoryPresentationState.READY -> R.string.trip_detail_pressure_ready
		PressureHistoryPresentationState.DELETED -> R.string.trip_detail_pressure_deleted
		PressureHistoryPresentationState.UNVERIFIABLE -> R.string.trip_detail_pressure_unverifiable
		PressureHistoryPresentationState.UNAVAILABLE -> R.string.trip_detail_pressure_unavailable
		PressureHistoryPresentationState.FAILED -> R.string.trip_detail_pressure_failed
	}

private val PressureHistoryCoverage.labelResource: Int
	get() = when (this) {
		PressureHistoryCoverage.NONE -> R.string.trip_detail_pressure_coverage_none
		PressureHistoryCoverage.COMPLETE -> R.string.trip_detail_pressure_coverage_complete
		PressureHistoryCoverage.PARTIAL -> R.string.trip_detail_pressure_coverage_partial
		PressureHistoryCoverage.UNKNOWN -> R.string.trip_detail_pressure_coverage_unknown
	}

private fun formatPressure(value: Float): String =
	String.format(Locale.getDefault(), "%.1f hPa", value)

private fun formatPressureRange(minimum: Float, maximum: Float): String =
	String.format(Locale.getDefault(), "%.1f–%.1f hPa", minimum, maximum)

private fun formatPressureChange(change: Float): String =
	String.format(Locale.getDefault(), "%+.1f hPa", change)

@Composable
@Suppress("FunctionNaming") // Internal only so Compose tests can verify failure recovery is reachable.
internal fun TripDetailStepsRetry(
	visible: Boolean,
	onRetry: () -> Unit,
) {
	if (visible) {
		TextButton(onClick = onRetry) {
			Text(stringResource(R.string.trip_detail_retry))
		}
	}
}

@Composable
private fun MetricRow(
	firstLabel: String,
	firstValue: String,
	secondLabel: String,
	secondValue: String,
	firstSupportingText: String? = null,
	secondSupportingText: String? = null,
) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.spacedBy(12.dp)
	) {
		MetricCard(
			label = firstLabel,
			value = firstValue,
			supportingText = firstSupportingText,
			modifier = Modifier.weight(1f)
		)
		MetricCard(
			label = secondLabel,
			value = secondValue,
			supportingText = secondSupportingText,
			modifier = Modifier.weight(1f)
		)
	}
}

@Composable
private fun RoutePreviewCard(
	points: List<RoutePoint>,
	sourceLabel: String,
	hasDistance: Boolean,
	routePreviewRenderer: RoutePreviewRenderer,
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
				routePreviewRenderer.Content(
					points = validPoints,
					modifier = Modifier
						.fillMaxWidth()
						.height(200.dp)
				)
			}
		}
	}
}

// internal — exposed for compose tests covering the Samples row and the absence
// of the legacy DeveloperMetrics / Show-Hide affordances.
@Composable
internal fun TripFactsCard(
	activityType: String,
	source: String,
	startTime: String,
	endTime: String,
	elevationGain: String,
	elevationLoss: String,
	maxAltitude: String,
	sampleCount: Int,
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
			FactRow(
				label = stringResource(R.string.trip_detail_samples),
				value = sampleCount.toString(),
			)
		}
	}
}

@Composable
internal fun FactRow(
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
internal fun MetricCard(
	label: String,
	value: String,
	modifier: Modifier = Modifier,
	supportingText: String? = null,
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
			if (supportingText != null) {
				Spacer(Modifier.height(2.dp))
				Text(
					text = supportingText,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
internal fun TripDetailStepsState.metricValue(): String = when (this) {
	is TripDetailStepsState.Complete -> count.formatReadable()
	is TripDetailStepsState.LowerBound -> "≥ ${count.formatReadable()}"
	is TripDetailStepsState.LegacyUnverified -> recordedCount?.let {
		stringResource(R.string.trip_detail_steps_legacy_value, it.formatReadable())
	} ?: "—"
	else -> "—"
}

// Exhaustiveness is deliberate: every durable product state must retain distinct UI copy.
@Composable
@Suppress("CyclomaticComplexMethod")
internal fun TripDetailStepsState.metricStatus(): String = stringResource(
	when (this) {
		is TripDetailStepsState.Complete -> R.string.trip_detail_steps_complete
		is TripDetailStepsState.LowerBound -> R.string.trip_detail_steps_partial
		is TripDetailStepsState.LegacyUnverified -> R.string.trip_detail_steps_legacy_unverified
		TripDetailStepsState.Materializing -> R.string.trip_detail_steps_materializing
		TripDetailStepsState.Partial -> R.string.trip_detail_steps_partial_without_value
		TripDetailStepsState.NotCaptured -> R.string.trip_detail_steps_not_captured
		TripDetailStepsState.Disabled -> R.string.trip_detail_steps_disabled
		TripDetailStepsState.Unsupported -> R.string.trip_detail_steps_unsupported
		TripDetailStepsState.PermissionRequired -> R.string.trip_detail_steps_permission_required
		TripDetailStepsState.OsLimited -> R.string.trip_detail_steps_os_limited
		TripDetailStepsState.Unavailable -> R.string.trip_detail_steps_unavailable
		TripDetailStepsState.NoObservation -> R.string.trip_detail_steps_no_observation
		TripDetailStepsState.Deleted -> R.string.trip_detail_steps_deleted
		TripDetailStepsState.Failed -> R.string.trip_detail_steps_failed
	},
)

private fun Double?.formatSpeed(lengthSystem: LengthSystem): String {
	val speed = this ?: return "—"
	if (speed <= 0.0) return "—"
	val converted = when (lengthSystem) {
		LengthSystem.Imperial -> speed * MS_TO_MPH
		LengthSystem.Sailing -> speed * MS_TO_KNOTS
		else -> speed * MS_TO_KMH
	}
	val unit = when (lengthSystem) {
		LengthSystem.Imperial -> "mph"
		LengthSystem.Sailing -> "kn"
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
