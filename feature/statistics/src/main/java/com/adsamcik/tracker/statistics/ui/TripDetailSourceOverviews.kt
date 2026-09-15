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
import com.adsamcik.tracker.shared.base.extension.formatReadable
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
import com.adsamcik.tracker.stats.api.repository.CellHistoryChildCompleteness
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryTechnology
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.SourceOnlyHistoryIntent
import com.adsamcik.tracker.stats.api.repository.StepsHistory
import com.adsamcik.tracker.stats.api.repository.StepsHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.TripSummary
import com.adsamcik.tracker.stats.api.repository.WifiHistoryBand
import com.adsamcik.tracker.stats.api.repository.WifiHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.WifiHistoryEntry
import com.adsamcik.tracker.stats.api.repository.WifiHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.WifiHistoryProductState
import com.adsamcik.tracker.stats.api.repository.WifiHistoryResultCompleteness
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
	intent: SourceOnlyHistoryIntent? = null,
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
		if (intent != null) {
			SourceHistoryAuthorityCard(
				source = HistorySource.ACTIVITY,
				originLabel = stringResource(activity.origin.labelResource),
				intent = intent,
			)
		}
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

			/** Identity-free selected Wi-Fi detail. Counts are retained results, never unique networks. */
			@Composable
			internal fun TripDetailWifiOverview(
				history: WifiHistoryEntry,
				intent: SourceOnlyHistoryIntent,
			) {
				val resources = LocalContext.current.resources
				SourceDetailColumn(tag = "trip_detail_wifi_only") {
					SourceDetailHeader(
						title = stringResource(R.string.trip_detail_wifi_session),
						startTime = history.startTime,
						endTime = history.endTime,
					)
					SourceHistoryAuthorityCard(
						source = HistorySource.WIFI,
						originLabel = stringResource(history.origin.labelResource),
						intent = intent,
					)
					RadioHistoryStateCard(
						status = stringResource(history.state.labelResource),
						coverage = stringResource(history.coverage.labelResource),
						empty = history.observations.isEmpty(),
						emptyExplanation = stringResource(history.state.emptyExplanationResource),
					)
					if (history.observations.isNotEmpty()) {
						val retainedResults = history.observations.sumOf { it.observationCount.toLong() }
						val partialDeliveries = history.observations.count {
							it.resultCompleteness == WifiHistoryResultCompleteness.PARTIAL
						}
						GlassCard(modifier = Modifier.fillMaxWidth()) {
							Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
								SourceDetailHeading(stringResource(R.string.trip_detail_wifi_evidence))
								FactRow(
									label = stringResource(R.string.trip_detail_radio_deliveries),
									value = history.observations.size.formatReadable(),
								)
								FactRow(
									label = stringResource(R.string.trip_detail_wifi_retained_results),
									value = retainedResults.formatReadable(),
								)
								FactRow(
									label = stringResource(R.string.trip_detail_radio_partial_deliveries),
									value = partialDeliveries.formatReadable(),
								)
								val bandMix = history.observations
									.flatMap { observation -> observation.bandMix.entries }
									.groupBy { it.key }
									.mapValues { (_, entries) -> entries.sumOf { it.value.toLong() } }
								if (bandMix.isNotEmpty()) {
									FactRow(
										label = stringResource(R.string.trip_detail_wifi_band_mix),
										value = bandMix.entries
											.sortedBy { it.key.ordinal }
											.joinToString { (band, count) ->
												"${resources.getString(band.labelResource)}: ${count.formatReadable()}"
											},
									)
								}
								val strongest = history.observations.maxOf {
									it.signalQuality.strongestSignalDbm
								}
								val weakest = history.observations.minOf {
									it.signalQuality.weakestSignalDbm
								}
								FactRow(
									label = stringResource(R.string.trip_detail_wifi_signal_range),
									value = stringResource(
										R.string.trip_detail_wifi_signal_range_value,
										weakest,
										strongest,
									),
								)
							}
						}
					}
					RadioLocationExclusionNotice()
				}
			}

			/** Identity-free selected Cell detail. Counts never imply unique towers or subscriptions. */
			@Composable
			internal fun TripDetailCellOverview(
				history: CellHistoryEntry,
				intent: SourceOnlyHistoryIntent,
			) {
				val resources = LocalContext.current.resources
				SourceDetailColumn(tag = "trip_detail_cell_only") {
					SourceDetailHeader(
						title = stringResource(R.string.trip_detail_cell_session),
						startTime = history.startTime,
						endTime = history.endTime,
					)
					SourceHistoryAuthorityCard(
						source = HistorySource.CELL,
						originLabel = stringResource(history.origin.labelResource),
						intent = intent,
					)
					RadioHistoryStateCard(
						status = stringResource(history.state.labelResource),
						coverage = stringResource(history.coverage.labelResource),
						empty = history.observations.isEmpty(),
						emptyExplanation = stringResource(history.state.emptyExplanationResource),
					)
					if (history.observations.isNotEmpty()) {
						val retainedRecords = history.observations.sumOf { it.acceptedChildCount.toLong() }
						val registeredRecords = history.observations.sumOf {
							it.registeredObservationCount.toLong()
						}
						val partialDeliveries = history.observations.count {
							it.childCompleteness == CellHistoryChildCompleteness.PARTIAL
						}
						GlassCard(modifier = Modifier.fillMaxWidth()) {
							Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
								SourceDetailHeading(stringResource(R.string.trip_detail_cell_evidence))
								FactRow(
									label = stringResource(R.string.trip_detail_radio_deliveries),
									value = history.observations.size.formatReadable(),
								)
								FactRow(
									label = stringResource(R.string.trip_detail_cell_retained_records),
									value = retainedRecords.formatReadable(),
								)
								FactRow(
									label = stringResource(R.string.trip_detail_cell_registered_records),
									value = registeredRecords.formatReadable(),
								)
								FactRow(
									label = stringResource(R.string.trip_detail_radio_partial_deliveries),
									value = partialDeliveries.formatReadable(),
								)
								val technologyMix = history.observations
									.flatMap { observation -> observation.technologyMix.entries }
									.groupBy { it.key }
									.mapValues { (_, entries) -> entries.sumOf { it.value.toLong() } }
								if (technologyMix.isNotEmpty()) {
									FactRow(
										label = stringResource(R.string.trip_detail_cell_technology_mix),
										value = technologyMix.entries
											.sortedBy { it.key.ordinal }
											.joinToString { (technology, count) ->
												"${resources.getString(technology.labelResource)}: ${count.formatReadable()}"
											},
									)
								}
							}
						}
					}
					RadioLocationExclusionNotice()
				}
			}

			@Composable
			private fun SourceHistoryAuthorityCard(
				source: HistorySource,
				originLabel: String,
				intent: SourceOnlyHistoryIntent,
			) {
				GlassCard(modifier = Modifier.fillMaxWidth()) {
					Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
						SourceDetailHeading(stringResource(R.string.trip_detail_source_authority))
						FactRow(
							label = stringResource(R.string.trip_detail_source),
							value = stringResource(source.labelResource),
						)
						FactRow(
							label = stringResource(R.string.trip_detail_source_origin),
							value = originLabel,
						)
						FactRow(
							label = stringResource(R.string.trip_detail_source_purpose),
							value = stringResource(intent.purposeLabelResource),
						)
						Text(
							text = stringResource(intent.explanationResource),
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
			}

			@Composable
			private fun RadioHistoryStateCard(
				status: String,
				coverage: String,
				empty: Boolean,
				emptyExplanation: String,
			) {
				GlassCard(modifier = Modifier.fillMaxWidth()) {
					Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
						SourceDetailHeading(stringResource(R.string.trip_detail_radio_summary))
						FactRow(label = stringResource(R.string.trip_detail_radio_status), value = status)
						FactRow(label = stringResource(R.string.trip_detail_radio_coverage), value = coverage)
						if (empty) {
							Text(
								text = emptyExplanation,
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}

			@Composable
			private fun RadioLocationExclusionNotice() {
				Text(
					text = stringResource(R.string.trip_detail_radio_location_excluded),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
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

private val WifiHistoryOrigin.labelResource: Int
	get() = when (this) {
		WifiHistoryOrigin.LOCAL -> R.string.trip_detail_source_origin_local
		WifiHistoryOrigin.IMPORTED -> R.string.trip_detail_source_origin_imported
	}

private val CellHistoryOrigin.labelResource: Int
	get() = when (this) {
		CellHistoryOrigin.Local -> R.string.trip_detail_source_origin_local
		is CellHistoryOrigin.Imported -> R.string.trip_detail_source_origin_imported
	}

private val SourceOnlyHistoryIntent.purposeLabelResource: Int
	get() = when (this) {
		SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY -> R.string.trip_detail_source_purpose_exact
		SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP ->
			R.string.trip_detail_source_purpose_imported
	}

private val SourceOnlyHistoryIntent.explanationResource: Int
	get() = when (this) {
		SourceOnlyHistoryIntent.EXACT_NATIVE_ONLY ->
			R.string.trip_detail_source_purpose_exact_explanation
		SourceOnlyHistoryIntent.PORTABLE_SOURCE_MEMBERSHIP ->
			R.string.trip_detail_source_purpose_imported_explanation
	}

private val WifiHistoryProductState.labelResource: Int
	get() = when (this) {
		WifiHistoryProductState.MATERIALIZING -> R.string.trip_detail_radio_materializing
		WifiHistoryProductState.PARTIAL -> R.string.trip_detail_radio_partial
		WifiHistoryProductState.READY -> R.string.trip_detail_radio_ready
		WifiHistoryProductState.UNAVAILABLE -> R.string.trip_detail_radio_unavailable
		WifiHistoryProductState.MISSING -> R.string.trip_detail_radio_missing
		WifiHistoryProductState.DELETED -> R.string.trip_detail_radio_deleted
		WifiHistoryProductState.FAILED -> R.string.trip_detail_radio_failed
	}

private val CellHistoryProductState.labelResource: Int
	get() = when (this) {
		CellHistoryProductState.MATERIALIZING -> R.string.trip_detail_radio_materializing
		CellHistoryProductState.PARTIAL -> R.string.trip_detail_radio_partial
		CellHistoryProductState.READY -> R.string.trip_detail_radio_ready
		CellHistoryProductState.UNAVAILABLE -> R.string.trip_detail_radio_unavailable
		CellHistoryProductState.MISSING -> R.string.trip_detail_radio_missing
		CellHistoryProductState.DELETED -> R.string.trip_detail_radio_deleted
		CellHistoryProductState.UNVERIFIABLE -> R.string.trip_detail_radio_unverifiable
		CellHistoryProductState.FAILED -> R.string.trip_detail_radio_failed
	}

private val WifiHistoryProductState.emptyExplanationResource: Int
	get() = when (this) {
		WifiHistoryProductState.MATERIALIZING -> R.string.trip_detail_radio_waiting
		WifiHistoryProductState.DELETED -> R.string.trip_detail_radio_deleted_explanation
		WifiHistoryProductState.FAILED -> R.string.trip_detail_radio_unverifiable_explanation
		WifiHistoryProductState.PARTIAL,
		WifiHistoryProductState.READY,
		WifiHistoryProductState.UNAVAILABLE,
		WifiHistoryProductState.MISSING -> R.string.trip_detail_radio_no_evidence
	}

private val CellHistoryProductState.emptyExplanationResource: Int
	get() = when (this) {
		CellHistoryProductState.MATERIALIZING -> R.string.trip_detail_radio_waiting
		CellHistoryProductState.DELETED -> R.string.trip_detail_radio_deleted_explanation
		CellHistoryProductState.UNVERIFIABLE,
		CellHistoryProductState.FAILED -> R.string.trip_detail_radio_unverifiable_explanation
		CellHistoryProductState.PARTIAL,
		CellHistoryProductState.READY,
		CellHistoryProductState.UNAVAILABLE,
		CellHistoryProductState.MISSING -> R.string.trip_detail_radio_no_evidence
	}

private val WifiHistoryCoverage.labelResource: Int
	get() = when (this) {
		WifiHistoryCoverage.NONE -> R.string.trip_detail_radio_coverage_none
		WifiHistoryCoverage.PARTIAL -> R.string.trip_detail_radio_coverage_partial
		WifiHistoryCoverage.COMPLETE -> R.string.trip_detail_radio_coverage_complete
		WifiHistoryCoverage.UNKNOWN -> R.string.trip_detail_radio_coverage_unknown
	}

private val CellHistoryCoverage.labelResource: Int
	get() = when (this) {
		CellHistoryCoverage.NONE -> R.string.trip_detail_radio_coverage_none
		CellHistoryCoverage.PARTIAL -> R.string.trip_detail_radio_coverage_partial
		CellHistoryCoverage.COMPLETE -> R.string.trip_detail_radio_coverage_complete
		CellHistoryCoverage.UNKNOWN -> R.string.trip_detail_radio_coverage_unknown
	}

private val WifiHistoryBand.labelResource: Int
	get() = when (this) {
		WifiHistoryBand.TWO_POINT_FOUR_GHZ -> R.string.trip_detail_wifi_band_2_4
		WifiHistoryBand.FIVE_GHZ -> R.string.trip_detail_wifi_band_5
		WifiHistoryBand.SIX_GHZ -> R.string.trip_detail_wifi_band_6
		WifiHistoryBand.OTHER -> R.string.trip_detail_wifi_band_other
	}

private val CellHistoryTechnology.labelResource: Int
	get() = when (this) {
		CellHistoryTechnology.GSM -> R.string.trip_detail_cell_technology_gsm
		CellHistoryTechnology.CDMA -> R.string.trip_detail_cell_technology_cdma
		CellHistoryTechnology.WCDMA -> R.string.trip_detail_cell_technology_wcdma
		CellHistoryTechnology.TDSCDMA -> R.string.trip_detail_cell_technology_tdscdma
		CellHistoryTechnology.LTE -> R.string.trip_detail_cell_technology_lte
		CellHistoryTechnology.NR -> R.string.trip_detail_cell_technology_nr
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
