package com.adsamcik.tracker.app.settings.tracking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.adsamcik.tracker.R
import com.adsamcik.tracker.app.common.ui.BatteryImpact
import com.adsamcik.tracker.app.common.ui.BatteryImpactIndicator
import com.adsamcik.tracker.app.settings.TrackingSettingsUiState
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.tracker.source.battery.BatteryImpactEstimate
import com.adsamcik.tracker.tracker.source.battery.EstimateConfidence
import com.adsamcik.tracker.tracker.source.battery.EstimateTarget
import com.adsamcik.tracker.tracker.source.battery.ImpactLevel
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceState
import com.adsamcik.tracker.tracker.source.coordinator.EffectiveSourceStatus
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import java.util.Locale

@Composable
internal fun BatteryEstimateCard(
	estimate: BatteryImpactEstimate,
	modifier: Modifier = Modifier,
) {
	var detailsExpanded by remember { mutableStateOf(false) }
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
				text = stringResource(R.string.settings_battery_estimate_summary),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
				Text(
					text = stringResource(
						if (detailsExpanded) R.string.settings_battery_estimate_hide_details
						else R.string.settings_battery_estimate_show_details,
					),
				)
			}
			if (detailsExpanded) {
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
	description: String,
	@DrawableRes iconRes: Int,
	frequency: SourceCollectionFrequency,
	status: EffectiveSourceStatus?,
	plan: SourcePlan?,
	optionPlans: Map<SourceCollectionFrequency, SourcePlan>,
	onFrequencySelected: (SourceCollectionFrequency) -> Unit,
) {
	var dialogOpen by remember { mutableStateOf(false) }
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.testTag("sourceFrequency-${status?.source?.name ?: title}")
			.clickable(role = Role.Button) { dialogOpen = true }
			.padding(horizontal = 16.dp, vertical = 14.dp),
		verticalAlignment = Alignment.Top,
	) {
		Surface(
			shape = RoundedCornerShape(12.dp),
			color = MaterialTheme.colorScheme.primaryContainer,
			contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
			modifier = Modifier
				.padding(top = 1.dp)
				.size(44.dp),
		) {
			Box(contentAlignment = Alignment.Center) {
				Icon(
					painter = painterResource(iconRes),
					contentDescription = null,
					modifier = Modifier.size(27.dp),
				)
			}
		}
		Spacer(Modifier.width(16.dp))
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(5.dp),
		) {
			Row(
				modifier = Modifier.fillMaxWidth(),
				verticalAlignment = Alignment.CenterVertically,
			) {
				Text(
					text = title,
					style = MaterialTheme.typography.titleMedium,
					modifier = Modifier.weight(1f),
				)
				Spacer(Modifier.width(8.dp))
				FrequencyPill(frequency)
			}
			Text(
				text = description,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			FlowRow(
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				val badges = plan?.cadenceBadges()
					?: listOf(stringResource(R.string.settings_source_cadence_unavailable))
				badges.forEach { label -> CadenceBadge(label) }
			}
		}
	}
	if (dialogOpen) {
		AlertDialog(
			onDismissRequest = { dialogOpen = false },
			title = { Text(stringResource(R.string.settings_source_frequency_dialog, title)) },
			text = {
				Column(
					modifier = Modifier.selectableGroup(),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					SourceCollectionFrequency.entries.forEach { option ->
						Surface(
							shape = RoundedCornerShape(12.dp),
							color = if (option == frequency) {
								MaterialTheme.colorScheme.primaryContainer
							} else {
								MaterialTheme.colorScheme.surface
							},
						) {
							Row(
								modifier = Modifier
									.fillMaxWidth()
									.selectable(
										selected = option == frequency,
										role = Role.RadioButton,
										onClick = {
										onFrequencySelected(option)
										dialogOpen = false
										},
									)
									.padding(horizontal = 10.dp, vertical = 10.dp),
								verticalAlignment = Alignment.CenterVertically,
							) {
								RadioButton(selected = option == frequency, onClick = null)
								Column(modifier = Modifier.padding(start = 8.dp)) {
									Text(option.displayName(), style = MaterialTheme.typography.titleSmall)
									Text(
										text = optionPlans[option]?.cadenceDescription()
											?: stringResource(R.string.settings_source_cadence_unavailable),
										style = MaterialTheme.typography.bodySmall,
										color = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								}
							}
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

@Composable
private fun FrequencyPill(frequency: SourceCollectionFrequency) {
	val (containerColor, contentColor) = when (frequency) {
		SourceCollectionFrequency.OFF -> MaterialTheme.colorScheme.surfaceVariant to
			MaterialTheme.colorScheme.onSurfaceVariant
		SourceCollectionFrequency.BATTERY_SAVER -> MaterialTheme.colorScheme.secondaryContainer to
			MaterialTheme.colorScheme.onSecondaryContainer
		SourceCollectionFrequency.BALANCED -> MaterialTheme.colorScheme.tertiaryContainer to
			MaterialTheme.colorScheme.onTertiaryContainer
		SourceCollectionFrequency.RESPONSIVE -> MaterialTheme.colorScheme.primaryContainer to
			MaterialTheme.colorScheme.onPrimaryContainer
	}
	Surface(
		shape = CircleShape,
		color = containerColor,
		contentColor = contentColor,
	) {
		Row(
			modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(6.dp),
		) {
			FrequencyLevelIndicator(level = frequency.stableCode, color = contentColor)
			Text(text = frequency.displayName(), style = MaterialTheme.typography.labelLarge)
			Icon(
				imageVector = Icons.Default.KeyboardArrowDown,
				contentDescription = null,
				modifier = Modifier.size(18.dp),
			)
		}
	}
}

@Composable
private fun FrequencyLevelIndicator(level: Int, color: androidx.compose.ui.graphics.Color) {
	Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
		repeat(3) { index ->
			Box(
				modifier = Modifier
					.size(5.dp)
					.clip(CircleShape)
					.background(if (index < level) color else color.copy(alpha = 0.25f)),
			)
		}
	}
}

@Composable
private fun CadenceBadge(label: String) {
	Surface(
		shape = CircleShape,
		color = MaterialTheme.colorScheme.surfaceContainerHighest,
		contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
	) {
		Text(
			text = label,
			style = MaterialTheme.typography.labelMedium,
			modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
		)
	}
}

@Composable
private fun SourcePlan.cadenceBadges(): List<String> = when (this) {
	is LocationPlan -> when {
		!enabled -> listOf(stringResource(R.string.settings_source_cadence_disabled))
		requestedIntervalMs <= 0L -> listOf(stringResource(R.string.settings_source_badge_as_available))
		else -> listOf(
			stringResource(R.string.settings_source_badge_updates, requestedIntervalMs.cadenceDuration()),
			stringResource(R.string.settings_source_badge_minimum, minimumDisplacementMeters.cadenceDistance()),
		)
	}
	is ActivityPlan -> when (mode) {
		ActivityMode.OFF -> listOf(stringResource(R.string.settings_source_cadence_disabled))
		ActivityMode.TRANSITIONS_ONLY -> listOf(stringResource(R.string.settings_source_badge_activity_changes))
		ActivityMode.CONTINUOUS_RECOGNITION -> listOf(
			stringResource(R.string.settings_source_badge_continuous),
			stringResource(R.string.settings_source_badge_checks, desiredDetectionLatencyMs.cadenceDuration()),
		)
	}
	is StepsPlan -> if (!enabled) {
		listOf(stringResource(R.string.settings_source_cadence_disabled))
	} else {
		listOf(stringResource(R.string.settings_source_badge_batching, maximumReportLatencyMs.cadenceDuration()))
	}
	is PressurePlan -> if (!enabled) {
		listOf(stringResource(R.string.settings_source_cadence_disabled))
	} else {
		listOf(
			stringResource(
				R.string.settings_source_badge_samples,
				(1_000_000 / hardwareSamplePeriodMicros.coerceAtLeast(1)).coerceAtLeast(1),
			),
			stringResource(R.string.settings_source_badge_summaries, aggregationWindowMs.cadenceDuration()),
		)
	}
	is WifiPlan -> when (mode) {
		WifiMode.OFF -> listOf(stringResource(R.string.settings_source_cadence_disabled))
		WifiMode.CACHED_ONLY -> listOf(
			stringResource(R.string.settings_source_badge_cached),
			stringResource(R.string.settings_source_badge_attempt_limit, minimumAttemptIntervalMs.cadenceDuration()),
		)
		WifiMode.BROADCAST_DRIVEN -> listOf(
			stringResource(R.string.settings_source_badge_android_events),
			stringResource(R.string.settings_source_badge_attempt_limit, minimumAttemptIntervalMs.cadenceDuration()),
		)
		WifiMode.ACTIVE_ATTEMPTS -> listOf(
			stringResource(R.string.settings_source_badge_active_scan),
			stringResource(R.string.settings_source_badge_attempt_limit, minimumAttemptIntervalMs.cadenceDuration()),
		)
	}
	is CellPlan -> if (!enabled) {
		listOf(stringResource(R.string.settings_source_cadence_disabled))
	} else {
		listOf(
			stringResource(R.string.settings_source_badge_network_changes),
			stringResource(R.string.settings_source_badge_refresh_limit, minimumRefreshAttemptIntervalMs.cadenceDuration()),
		)
	}
}

@Composable
private fun SourcePlan.cadenceDescription(): String = when (this) {
	is LocationPlan -> if (!enabled) {
		stringResource(R.string.settings_source_cadence_disabled)
	} else if (requestedIntervalMs <= 0L) {
		stringResource(R.string.settings_source_cadence_location_available)
	} else {
		stringResource(
			R.string.settings_source_cadence_location,
			requestedIntervalMs.cadenceDuration(),
			minimumDisplacementMeters.cadenceDistance(),
		)
	}
	is ActivityPlan -> when (mode) {
		ActivityMode.OFF -> stringResource(R.string.settings_source_cadence_disabled)
		ActivityMode.TRANSITIONS_ONLY -> stringResource(R.string.settings_source_cadence_activity_events)
		ActivityMode.CONTINUOUS_RECOGNITION -> stringResource(
			R.string.settings_source_cadence_activity_continuous,
			desiredDetectionLatencyMs.cadenceDuration(),
		)
	}
	is StepsPlan -> if (!enabled) {
		stringResource(R.string.settings_source_cadence_disabled)
	} else {
		stringResource(R.string.settings_source_cadence_steps, maximumReportLatencyMs.cadenceDuration())
	}
	is PressurePlan -> if (!enabled) {
		stringResource(R.string.settings_source_cadence_disabled)
	} else {
		stringResource(
			R.string.settings_source_cadence_pressure,
			(1_000_000 / hardwareSamplePeriodMicros.coerceAtLeast(1)).coerceAtLeast(1),
			aggregationWindowMs.cadenceDuration(),
		)
	}
	is WifiPlan -> when (mode) {
		WifiMode.OFF -> stringResource(R.string.settings_source_cadence_disabled)
		WifiMode.CACHED_ONLY -> stringResource(
			R.string.settings_source_cadence_wifi_cached,
			minimumAttemptIntervalMs.cadenceDuration(),
		)
		WifiMode.BROADCAST_DRIVEN -> stringResource(
			R.string.settings_source_cadence_wifi_events,
			minimumAttemptIntervalMs.cadenceDuration(),
		)
		WifiMode.ACTIVE_ATTEMPTS -> stringResource(
			R.string.settings_source_cadence_wifi_active,
			minimumAttemptIntervalMs.cadenceDuration(),
		)
	}
	is CellPlan -> if (!enabled) {
		stringResource(R.string.settings_source_cadence_disabled)
	} else {
		stringResource(
			R.string.settings_source_cadence_cell_events,
			minimumRefreshAttemptIntervalMs.cadenceDuration(),
		)
	}
}

private fun Long.cadenceDuration(): String = when {
	this < 1_000L -> "${(this / 100L) / 10.0} sec"
	this < 60_000L -> "${this / 1_000L} sec"
	this % 60_000L == 0L -> "${this / 60_000L} min"
	else -> "${this / 1_000L} sec"
}

private fun Float.cadenceDistance(): String = if (this % 1f == 0f) {
	"${toInt()} m"
} else {
	"$this m"
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
