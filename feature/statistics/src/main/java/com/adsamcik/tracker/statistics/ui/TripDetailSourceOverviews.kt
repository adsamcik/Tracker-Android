@file:Suppress("FunctionNaming")

package com.adsamcik.tracker.statistics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.shared.base.extension.formatAsDuration
import com.adsamcik.tracker.shared.utils.style.compose.GlassCard
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.presenter.TripDetailStepsState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryConfidence
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryGapReason
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryMechanism
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.value.EpochMs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val sourceDetailDateTimeFormatter: DateTimeFormatter by lazy {
	DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)
}

/** Exact Activity-only selected detail built solely from retained Activity history. */
@Composable
internal fun TripDetailActivityOverview(
	activity: ActivityHistoryEntry,
) {
	SourceDetailColumn(tag = "trip_detail_activity_only") {
		SourceDetailHeader(
			title = stringResource(R.string.trip_detail_activity_session),
			startTime = activity.startTime,
			endTime = activity.endTime,
		)
		Text(
			text = stringResource(R.string.trip_detail_activity_approximate_notice),
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		GlassCard(modifier = Modifier.fillMaxWidth()) {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				SourceDetailHeading(stringResource(R.string.trip_detail_activity_summary))
				FactRow(
					label = stringResource(R.string.trip_detail_activity_status),
					value = stringResource(activity.state.labelResource),
				)
				FactRow(
					label = stringResource(R.string.trip_detail_activity_coverage),
					value = stringResource(activity.coverage.labelResource),
				)
				FactRow(
					label = stringResource(R.string.trip_detail_activity_origin),
					value = stringResource(activity.origin.labelResource),
				)
				val activeTime = activity.activeTime
				if (activeTime == null) {
					Text(
						text = stringResource(activity.state.explanationResource),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				} else {
					FactRow(
						label = stringResource(R.string.trip_detail_activity_active_time),
						value = formatActivityDuration(activeTime.knownActiveDurationNanos),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_activity_inactive_time),
						value = formatActivityDuration(activeTime.knownInactiveDurationNanos),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_activity_unknown_time),
						value = formatActivityDuration(activeTime.unknownActivityDurationNanos),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_activity_unobserved_time),
						value = formatActivityDuration(activeTime.unobservedDurationNanos),
					)
				}
			}
		}
		if (activity.fragments.isNotEmpty()) {
			SourceDetailHeading(stringResource(R.string.trip_detail_activity_timeline))
			activity.fragments.forEachIndexed { index, fragment ->
				TripDetailActivityFragmentCard(index, fragment)
			}
		}
	}
}

/** Exact Steps-only selected detail that keeps value, coverage, and product state independent. */
@Composable
internal fun TripDetailStepsOverview(
	trip: TripSummary,
	stepsHistory: StepsHistory,
	stepsState: TripDetailStepsState,
	onRetry: () -> Unit,
) {
	SourceDetailColumn(tag = "trip_detail_steps_only") {
		SourceDetailHeader(
			title = stringResource(R.string.trip_detail_steps_session),
			startTime = trip.startTimeMs,
			endTime = trip.endTimeMs,
		)
		MetricCard(
			label = stringResource(R.string.trip_detail_steps),
			value = stepsState.metricValue(),
			supportingText = stepsState.metricStatus(),
			modifier = Modifier.fillMaxWidth().testTag("trip_detail_steps_only_value"),
		)
		GlassCard(modifier = Modifier.fillMaxWidth()) {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				SourceDetailHeading(stringResource(R.string.trip_detail_steps_evidence))
				FactRow(
					label = stringResource(R.string.trip_detail_steps_coverage),
					value = stringResource(stepsHistory.coverage.labelResource),
				)
				Text(
					text = stringResource(R.string.trip_detail_steps_only_scope),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
		TripDetailStepsRetry(
			visible = stepsState == TripDetailStepsState.Failed,
			onRetry = onRetry,
		)
	}
}

/** Exact non-Location capture combinations stay contained until a source detail API is bound. */
@Composable
internal fun TripDetailCapturedWithoutLocationOverview(
	trip: TripSummary,
	capturedSources: Set<HistorySource>,
) {
	require(capturedSources.isNotEmpty() && HistorySource.LOCATION !in capturedSources)
	val resources = LocalContext.current.resources
	SourceDetailColumn(tag = "trip_detail_captured_without_location") {
		SourceDetailHeader(
			title = stringResource(R.string.trip_detail_captured_session),
			startTime = trip.startTimeMs,
			endTime = trip.endTimeMs,
		)
		GlassCard(modifier = Modifier.fillMaxWidth()) {
			Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
				SourceDetailHeading(stringResource(R.string.trip_detail_location_not_captured))
				FactRow(
					label = stringResource(R.string.trip_detail_captured_sources),
					value = capturedSources
						.sortedBy(HistorySource::ordinal)
						.joinToString { source -> resources.getString(source.labelResource) },
				)
				Text(
					text = stringResource(R.string.trip_detail_source_combination_unavailable),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

/** Retained legacy details remain visible, but their source authority is explicitly bounded. */
@Composable
internal fun TripDetailLegacyCaptureNotice() {
	GlassCard(
		modifier = Modifier
			.fillMaxWidth()
			.testTag("trip_detail_legacy_capture_notice"),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			SourceDetailHeading(stringResource(R.string.trip_detail_legacy_capture_title))
			Text(
				text = stringResource(R.string.trip_detail_legacy_capture_subtitle),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
		}
	}
}

@Composable
private fun TripDetailActivityFragmentCard(
	index: Int,
	fragment: ActivityHistoryFragment,
) {
	GlassCard(
		modifier = Modifier
			.fillMaxWidth()
			.testTag("trip_detail_activity_fragment_$index"),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
			when (fragment) {
				is ActivityHistoryFragment.Band -> {
					SourceDetailHeading(stringResource(fragment.activity.labelResource))
					FactRow(
						label = stringResource(R.string.trip_detail_activity_band_duration),
						value = formatActivityDuration(fragment.durationNanos),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_activity_confidence),
						value = fragment.confidence.label(),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_activity_mechanism),
						value = stringResource(fragment.mechanism.labelResource),
					)
					fragment.refinedTransitionActivity?.let { refinedActivity ->
						FactRow(
							label = stringResource(R.string.trip_detail_activity_refined_from),
							value = stringResource(refinedActivity.labelResource),
						)
					}
				}
				is ActivityHistoryFragment.Gap -> {
					SourceDetailHeading(stringResource(R.string.trip_detail_activity_known_gap))
					FactRow(
						label = stringResource(R.string.trip_detail_activity_gap_reason),
						value = stringResource(fragment.reason.labelResource),
					)
					FactRow(
						label = stringResource(R.string.trip_detail_activity_gap_duration),
						value = formatActivityDuration(fragment.durationNanos),
					)
				}
			}
		}
	}
}

@Composable
private fun SourceDetailHeader(
	title: String,
	startTime: EpochMs,
	endTime: EpochMs,
) {
	val startText = remember(startTime) {
		sourceDetailDateTimeFormatter.format(
			Instant.ofEpochMilli(startTime.raw).atZone(ZoneId.systemDefault()),
		)
	}
	val endText = remember(endTime) {
		sourceDetailDateTimeFormatter.format(
			Instant.ofEpochMilli(endTime.raw).atZone(ZoneId.systemDefault()),
		)
	}
	GlassCard(modifier = Modifier.fillMaxWidth()) {
		Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
			Text(
				text = title,
				modifier = Modifier.semantics { heading() },
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
}

@Composable
private fun SourceDetailColumn(
	tag: String,
	content: @Composable () -> Unit,
) {
	val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp + navBottom)
			.testTag(tag),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		content()
	}
}

@Composable
private fun SourceDetailHeading(text: String) {
	Text(
		text = text,
		modifier = Modifier.semantics { heading() },
		style = MaterialTheme.typography.titleMedium,
		fontWeight = FontWeight.SemiBold,
		color = MaterialTheme.colorScheme.onSurface,
	)
}

@Composable
private fun formatActivityDuration(durationNanos: Long): String =
	(durationNanos / NANOS_PER_MILLISECOND).formatAsDuration(LocalContext.current)

@Composable
private fun ActivityHistoryConfidence.label(): String = when (this) {
	ActivityHistoryConfidence.TransitionSignal ->
		stringResource(R.string.trip_detail_activity_confidence_transition)
	is ActivityHistoryConfidence.Sampled -> pluralStringResource(
		R.plurals.trip_detail_activity_confidence_sampled,
		observationCount,
		minimumPercent,
		maximumPercent,
		observationCount,
	)
}

private val ActivityHistoryProductState.labelResource: Int
	get() = when (this) {
		ActivityHistoryProductState.MATERIALIZING -> R.string.trip_detail_activity_materializing
		ActivityHistoryProductState.PARTIAL -> R.string.trip_detail_activity_partial
		ActivityHistoryProductState.READY -> R.string.trip_detail_activity_ready
		ActivityHistoryProductState.UNAVAILABLE -> R.string.trip_detail_activity_unavailable
		ActivityHistoryProductState.FAILED -> R.string.trip_detail_activity_failed
	}

private val ActivityHistoryProductState.explanationResource: Int
	get() = when (this) {
		ActivityHistoryProductState.MATERIALIZING ->
			R.string.trip_detail_activity_materializing_explanation
		ActivityHistoryProductState.PARTIAL -> R.string.trip_detail_activity_partial_explanation
		ActivityHistoryProductState.READY -> R.string.trip_detail_activity_ready_explanation
		ActivityHistoryProductState.UNAVAILABLE ->
			R.string.trip_detail_activity_unavailable_explanation
		ActivityHistoryProductState.FAILED -> R.string.trip_detail_activity_failed_explanation
	}

private val ActivityHistoryCoverage.labelResource: Int
	get() = when (this) {
		ActivityHistoryCoverage.NONE -> R.string.trip_detail_activity_coverage_none
		ActivityHistoryCoverage.PARTIAL -> R.string.trip_detail_activity_coverage_partial
		ActivityHistoryCoverage.COMPLETE -> R.string.trip_detail_activity_coverage_complete
	}

private val ActivityHistoryOrigin.labelResource: Int
	get() = when (this) {
		ActivityHistoryOrigin.LOCAL -> R.string.trip_detail_activity_origin_local
		ActivityHistoryOrigin.IMPORTED -> R.string.trip_detail_activity_origin_imported
	}

private val ActivityHistoryType.labelResource: Int
	get() = when (this) {
		ActivityHistoryType.STILL -> R.string.trip_detail_activity_still
		ActivityHistoryType.WALKING -> R.string.trip_detail_activity_walking
		ActivityHistoryType.RUNNING -> R.string.trip_detail_activity_running
		ActivityHistoryType.ON_BICYCLE -> R.string.trip_detail_activity_cycling
		ActivityHistoryType.IN_VEHICLE -> R.string.trip_detail_activity_vehicle
		ActivityHistoryType.ON_FOOT -> R.string.trip_detail_activity_on_foot
		ActivityHistoryType.TILTING -> R.string.trip_detail_activity_tilting
		ActivityHistoryType.UNKNOWN -> R.string.trip_detail_activity_unknown
	}

private val ActivityHistoryMechanism.labelResource: Int
	get() = when (this) {
		ActivityHistoryMechanism.TRANSITION -> R.string.trip_detail_activity_mechanism_transition
		ActivityHistoryMechanism.SAMPLED_REFINEMENT ->
			R.string.trip_detail_activity_mechanism_refinement
		ActivityHistoryMechanism.SAMPLED_CLASSIFICATION ->
			R.string.trip_detail_activity_mechanism_classification
	}

private val ActivityHistoryGapReason.labelResource: Int
	get() = when (this) {
		ActivityHistoryGapReason.NO_QUALIFIED_EVIDENCE ->
			R.string.trip_detail_activity_gap_no_evidence
		ActivityHistoryGapReason.PROVIDER_DISCONTINUITY ->
			R.string.trip_detail_activity_gap_provider
		ActivityHistoryGapReason.AUTHORIZATION_DISCONTINUITY ->
			R.string.trip_detail_activity_gap_authorization
		ActivityHistoryGapReason.PROCESS_OR_REBOOT_DISCONTINUITY ->
			R.string.trip_detail_activity_gap_process
		ActivityHistoryGapReason.SOURCE_REJECTED_EVIDENCE ->
			R.string.trip_detail_activity_gap_rejected
	}

private val StepsHistoryCoverage.labelResource: Int
	get() = when (this) {
		StepsHistoryCoverage.NONE -> R.string.trip_detail_steps_coverage_none
		StepsHistoryCoverage.COMPLETE -> R.string.trip_detail_steps_coverage_complete
		StepsHistoryCoverage.PARTIAL -> R.string.trip_detail_steps_coverage_partial
		StepsHistoryCoverage.UNKNOWN -> R.string.trip_detail_steps_coverage_unknown
	}

private val HistorySource.labelResource: Int
	get() = when (this) {
		HistorySource.LOCATION -> R.string.trip_detail_source_location
		HistorySource.WIFI -> R.string.trip_detail_source_wifi
		HistorySource.CELL -> R.string.trip_detail_source_cell
		HistorySource.ACTIVITY -> R.string.trip_detail_source_activity
		HistorySource.STEPS -> R.string.trip_detail_source_steps
		HistorySource.PRESSURE -> R.string.trip_detail_source_pressure
	}

private const val NANOS_PER_MILLISECOND = 1_000_000L
