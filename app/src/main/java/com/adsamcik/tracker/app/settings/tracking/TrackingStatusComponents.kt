package com.adsamcik.tracker.app.settings.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.app.settings.components.SettingsItem
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimate
import com.adsamcik.tracker.tracker.source.battery.EstimateConfidence
import com.adsamcik.tracker.tracker.source.battery.EstimateTarget
import com.adsamcik.tracker.tracker.source.battery.ImpactLevel
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceState
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.Locale

@Composable
internal fun BatteryEstimateCard(
	estimate: BatteryImpactEstimate,
	modifier: Modifier = Modifier,
) {
	Card(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.testTag("batteryEstimateCard"),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
	) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = stringResource(R.string.settings_battery_estimate_title),
				style = MaterialTheme.typography.titleMedium,
			)
			BatteryImpactIndicator(impact = estimate.level.toUiImpact())
			Text(
				text = stringResource(
					R.string.settings_battery_confidence,
					estimate.confidence.displayName(),
				),
				style = MaterialTheme.typography.bodyMedium,
			)
			val drivers = estimate.dominantDrivers.joinToString { driver ->
				driver.name.humanize()
			}
			if (drivers.isNotBlank()) {
				Text(
					text = stringResource(R.string.settings_battery_drivers, drivers),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			estimate.comparisonBaselineId?.let { baseline ->
				Text(
					text = stringResource(R.string.settings_battery_comparison, baseline.humanize()),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			val range = estimate.estimatedPercentPerHour
			Text(
				text = if (range == null) {
					stringResource(R.string.settings_battery_generic_prior)
				} else {
					when (estimate.estimateTarget) {
						EstimateTarget.OBSERVED_TOTAL_DEVICE_DRAIN -> stringResource(
							R.string.settings_battery_observed_range,
							range.start,
							range.endInclusive,
						)
						EstimateTarget.ESTIMATED_INCREMENTAL_TRACKER_DRAIN -> stringResource(
							R.string.settings_battery_incremental_range,
							range.start,
							range.endInclusive,
						)
						EstimateTarget.QUALITATIVE_RELATIVE_TRACKER_IMPACT -> stringResource(
							R.string.settings_battery_relative_range,
							range.start,
							range.endInclusive,
						)
					}
				},
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			Text(
				text = stringResource(R.string.settings_battery_platform_caveat),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			val assumptions = estimate.assumptions.joinToString { assumption -> assumption.code.humanize() }
			if (assumptions.isNotBlank()) {
				Text(
					text = stringResource(R.string.settings_battery_assumptions, assumptions),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
internal fun EffectiveTrackingStatusCard(
	uiState: TrackingSettingsUiState,
	modifier: Modifier = Modifier,
) {
	Card(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 8.dp)
			.testTag("effectiveTrackingStatus"),
		colors = CardDefaults.cardColors(
			containerColor = if (uiState.runtimeFailureCode == null) {
				MaterialTheme.colorScheme.secondaryContainer
			} else {
				MaterialTheme.colorScheme.errorContainer
			},
		),
	) {
		Column(
			modifier = Modifier.padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			Text(
				text = stringResource(R.string.settings_effective_status_title),
				style = MaterialTheme.typography.titleMedium,
			)
			Text(
				text = if (uiState.trackingActive) {
					stringResource(
						R.string.settings_effective_status_running,
						uiState.desiredPlanRevision?.toString() ?: "–",
						uiState.appliedPlanRevision?.toString() ?: "–",
					)
				} else {
					stringResource(R.string.settings_effective_status_idle)
				},
				style = MaterialTheme.typography.bodyMedium,
			)
			uiState.sourceStatuses.values
				.filter { status ->
					uiState.trackingActive && status.requestedFrequency != SourceCollectionFrequency.OFF ||
						status.state in setOf(
							EffectiveSourceState.BLOCKED,
							EffectiveSourceState.DEGRADED,
							EffectiveSourceState.FAILED,
						)
				}
				.sortedBy { it.source.ordinal }
				.forEach { status ->
					Text(
						text = "${status.source.displayName()}: ${status.requestedFrequency.displayName()} → " +
							status.effectiveDescription(),
						style = MaterialTheme.typography.bodySmall,
					)
				}
			if (uiState.trackingActive) {
				val metrics = uiState.runtimeTelemetry
				Text(
					text = stringResource(R.string.settings_runtime_telemetry_title),
					style = MaterialTheme.typography.labelLarge,
				)
				Text(
					text = stringResource(
						R.string.settings_runtime_telemetry_summary,
						metrics.projectedEventCount,
						metrics.planRevisionCount,
						metrics.trackingFrameCount,
						metrics.trackingFrameWakeLockNanos / 1_000_000L,
					),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSecondaryContainer,
				)
			}
		}
	}
}

@Composable
internal fun SourceFrequencySettingsItem(
	title: String,
	frequency: SourceCollectionFrequency,
	status: EffectiveSourceStatus?,
	onFrequencySelected: (SourceCollectionFrequency) -> Unit,
) {
	var dialogOpen by remember { mutableStateOf(false) }
	val effective = status?.effectiveDescription()
	val requestedText = stringResource(R.string.settings_source_requested, frequency.displayName())
	val effectiveText = effective?.let { description ->
		stringResource(R.string.settings_source_effective, description)
	}
	SettingsItem(
		title = title,
		subtitle = buildString {
			append(requestedText)
			if (effectiveText != null) {
				append(" • ")
				append(effectiveText)
			}
		},
		modifier = Modifier.testTag("sourceFrequency-${status?.source?.name ?: title}"),
		onClick = { dialogOpen = true },
	)
	if (dialogOpen) {
		AlertDialog(
			onDismissRequest = { dialogOpen = false },
			title = { Text(stringResource(R.string.settings_source_frequency_dialog, title)) },
			text = {
				Column {
					SourceCollectionFrequency.entries.forEach { option ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									onFrequencySelected(option)
									dialogOpen = false
								}
								.padding(vertical = 8.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							RadioButton(selected = option == frequency, onClick = null)
							Text(option.displayName(), modifier = Modifier.padding(start = 8.dp))
						}
					}
				}
			},
			confirmButton = {
				TextButton(onClick = { dialogOpen = false }) {
					Text(stringResource(android.R.string.cancel))
				}
			},
		)
	}
}

private fun EffectiveSourceStatus.effectiveDescription(): String {
	val stateText = state.name.humanize()
	val reasons = reasonCodes.joinToString { it.humanize() }
	return buildString {
		append(stateText)
		if (effectiveMode != requestedMode && state != EffectiveSourceState.DISABLED) {
			append(" (")
			append(effectiveMode.humanize())
			append(")")
		}
		if (reasons.isNotBlank()) {
			append(": ")
			append(reasons)
		}
	}
}

private fun SourceCollectionFrequency.displayName(): String = when (this) {
	SourceCollectionFrequency.OFF -> "Off"
	SourceCollectionFrequency.BATTERY_SAVER -> "Efficient"
	SourceCollectionFrequency.BALANCED -> "Balanced"
	SourceCollectionFrequency.RESPONSIVE -> "Responsive"
}

private fun SourceKind.displayName(): String = when (this) {
	SourceKind.LOCATION -> "Location"
	SourceKind.ACTIVITY -> "Activity"
	SourceKind.STEPS -> "Steps"
	SourceKind.PRESSURE -> "Pressure"
	SourceKind.WIFI -> "Wi-Fi"
	SourceKind.CELL -> "Cell"
}

private fun EstimateConfidence.displayName(): String = when (this) {
	EstimateConfidence.LOW -> "Low"
	EstimateConfidence.MEDIUM -> "Medium"
	EstimateConfidence.HIGHER -> "Higher"
}

private fun ImpactLevel.toUiImpact(): BatteryImpact = when (this) {
	ImpactLevel.LOW -> BatteryImpact.LOW
	ImpactLevel.MODERATE -> BatteryImpact.MODERATE
	ImpactLevel.HIGH -> BatteryImpact.HIGH
}

private fun String.humanize(): String = lowercase(Locale.ROOT)
	.replace('_', ' ')
	.replaceFirstChar { character -> character.titlecase(Locale.ROOT) }
