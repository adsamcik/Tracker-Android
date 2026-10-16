package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.LocationAdaptiveSettings
import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceSettings
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.shared.preferences.tracking.baselineBalanceLevel
import com.adsamcik.tracker.shared.preferences.tracking.captureParticipates
import com.adsamcik.tracker.tracker.source.model.SourceBalancePreference
import com.adsamcik.tracker.tracker.source.catalog.SourceAcquisitionPlanFactory
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.ActivityMode
import com.adsamcik.tracker.tracker.source.model.ActivityPlan
import com.adsamcik.tracker.tracker.source.model.CellMode
import com.adsamcik.tracker.tracker.source.model.CellPlan
import com.adsamcik.tracker.tracker.source.model.LocationBackend
import com.adsamcik.tracker.tracker.source.model.LocationMode
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.PressurePlan
import com.adsamcik.tracker.tracker.source.model.RetryBackoff
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.SourcePlan
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import javax.inject.Inject

/**
 * Builds source plans from participation, five-level quality and current environment while preserving
 * saved custom choices.
 */
class SemanticAcquisitionPlanFactory @Inject constructor() : SourceAcquisitionPlanFactory {
	override fun create(
		settings: TrackingParamsState,
		revision: Long,
		createdAtMs: Long,
		environment: SourcePlanEnvironment,
	): AcquisitionPlanRevision {
		val frequency = participatingFrequencies(settings)
		val preferences = balancePreferences(settings, environment)
		return AcquisitionPlanRevision(
			revision = revision,
			planId = "semantic-v${settings.sourceSettingsVersion}-${settings.presetName.lowercase()}",
			createdAtMs = createdAtMs,
			sourcePolicyRevision = settings.sourcePolicyRevision,
			balanceProfileVersion = settings.balanceProfileVersion,
			sourcePreferences = preferences,
			plans = mapOf(
				SourceKind.LOCATION to locationPlan(settings, frequency.location, revision, environment),
				SourceKind.ACTIVITY to sourcePlan(
					SourceKind.ACTIVITY, frequency.activity, preferences, revision, environment),
				SourceKind.STEPS to sourcePlan(
					SourceKind.STEPS, frequency.steps, preferences, revision, environment),
				SourceKind.PRESSURE to sourcePlan(
					SourceKind.PRESSURE, frequency.pressure, preferences, revision, environment),
				SourceKind.WIFI to sourcePlan(
					SourceKind.WIFI, frequency.wifi, preferences, revision, environment),
				SourceKind.CELL to sourcePlan(
					SourceKind.CELL, frequency.cell, preferences, revision, environment),
			),
		)
	}

	private fun participatingFrequencies(settings: TrackingParamsState): SourceCollectionSettings {
		val stored = settings.sourceCollectionSettings
		return stored.copy(
			location = participatingFrequency(settings, SourceKind.LOCATION, stored.location),
			activity = participatingFrequency(settings, SourceKind.ACTIVITY, stored.activity),
			steps = participatingFrequency(settings, SourceKind.STEPS, stored.steps),
			pressure = participatingFrequency(settings, SourceKind.PRESSURE, stored.pressure),
			wifi = participatingFrequency(settings, SourceKind.WIFI, stored.wifi),
			cell = participatingFrequency(settings, SourceKind.CELL, stored.cell),
		)
	}

	private fun participatingFrequency(
		settings: TrackingParamsState,
		source: SourceKind,
		frequency: SourceCollectionFrequency,
	): SourceCollectionFrequency =
		if (!settings.captureParticipates(TrackingSourceComponent.fromStableCode(source.stableCode))) {
			SourceCollectionFrequency.OFF
		} else {
			frequency.takeUnless { it == SourceCollectionFrequency.OFF } ?: SourceCollectionFrequency.BALANCED
		}

	private fun balancePreferences(
		settings: TrackingParamsState,
		environment: SourcePlanEnvironment,
	): Map<SourceKind, SourceBalancePreference> {
		if (settings.balanceProfileVersion == null) {
			return emptyMap()
		}
		return SourceKind.entries.associateWith { source ->
			val component = TrackingSourceComponent.fromStableCode(source.stableCode)
			val baseline = requireNotNull(settings.baselineBalanceLevel(component))
			val focus = settings.captureParticipates(component) && source in environment.liveSources
			SourceBalancePreference(
				if (focus) {
					maxOf(baseline, SourceBalanceSettings.LIVE_LEVEL)
				} else {
					baseline
				},
				focus,
			)
		}
	}

	private fun sourcePlan(
		source: SourceKind,
		frequency: SourceCollectionFrequency,
		preferences: Map<SourceKind, SourceBalancePreference>,
		revision: Long,
		environment: SourcePlanEnvironment,
	): SourcePlan {
		if (preferences.isNotEmpty() && frequency != SourceCollectionFrequency.OFF) {
			return adaptiveSourcePlan(source, preferences.getValue(source).requestedLevel, revision, environment)
		}
		return when (source) {
			SourceKind.LOCATION -> error("Location uses its dedicated profile")
			SourceKind.ACTIVITY -> activityPlan(frequency, revision)
			SourceKind.STEPS -> stepsPlan(frequency, revision)
			SourceKind.PRESSURE -> pressurePlan(frequency, revision)
			SourceKind.WIFI -> wifiPlan(frequency, revision)
			SourceKind.CELL -> cellPlan(frequency, revision, environment.subscriptionIds)
		}
	}

	private fun locationPlan(
		settings: TrackingParamsState,
		frequency: SourceCollectionFrequency,
		revision: Long,
		environment: SourcePlanEnvironment,
	): LocationPlan {
		if (settings.locationProfileVersion == LocationAdaptiveSettings.PROFILE_VERSION &&
			frequency != SourceCollectionFrequency.OFF) {
			val savedLevel = requireNotNull(settings.locationBalanceLevel)
			val level = if (SourceKind.LOCATION in environment.liveSources) {
				maxOf(savedLevel, SourceBalanceSettings.LIVE_LEVEL)
			} else {
				savedLevel
			}
			return adaptiveLocationPlan(level, revision, environment.locationBackend,
                environment.preciseLocationAvailable)
		}
		val values = when (frequency) {
			SourceCollectionFrequency.OFF -> LocationValues(LocationMode.DISABLED, 60_000, 60_000, 100f, 0)
			SourceCollectionFrequency.BATTERY_SAVER -> LocationValues(LocationMode.LOW_POWER, 60_000, 30_000, 50f,
                120_000)
			SourceCollectionFrequency.BALANCED -> LocationValues(
				LocationMode.BALANCED,
				settings.minTimeSeconds * 1_000L,
				settings.minTimeSeconds * 1_000L,
				settings.minDistanceMeters.toFloat(),
				maxOf(10_000L, settings.minTimeSeconds * 5_000L),
			)
			SourceCollectionFrequency.RESPONSIVE -> LocationValues(LocationMode.HIGH_ACCURACY, 1_000, 500, 2f, 2_000)
		}
		return LocationPlan(
			revision,
			environment.locationBackend,
			values.mode,
			values.intervalMs,
			values.minimumIntervalMs,
			values.displacementMeters,
			values.maximumBatchDelayMs,
			preciseLocationAvailable = environment.preciseLocationAvailable,
		)
	}

	private fun activityPlan(frequency: SourceCollectionFrequency, revision: Long): ActivityPlan = when (frequency) {
		SourceCollectionFrequency.OFF -> ActivityPlan(revision, ActivityMode.OFF, 60_000, 70, emptySet())
		// Transitions are a low-power control signal, not enough evidence for captured Activity
		// history. Lower QoS therefore keeps classification capture but relaxes its delivery latency.
		SourceCollectionFrequency.BATTERY_SAVER -> ActivityPlan(revision, ActivityMode.CONTINUOUS_RECOGNITION, 60_000,
            75, setOf(0, 1))
		SourceCollectionFrequency.BALANCED -> ActivityPlan(revision, ActivityMode.CONTINUOUS_RECOGNITION, 30_000, 65,
            setOf(0, 1))
		SourceCollectionFrequency.RESPONSIVE -> ActivityPlan(revision, ActivityMode.CONTINUOUS_RECOGNITION, 5_000, 55,
            setOf(0, 1))
	}

	private fun stepsPlan(frequency: SourceCollectionFrequency, revision: Long): StepsPlan = when (frequency) {
		SourceCollectionFrequency.OFF -> StepsPlan(revision, false, 0, 60_000, false)
		SourceCollectionFrequency.BATTERY_SAVER -> StepsPlan(revision, true, 300_000, 60_000, false)
		SourceCollectionFrequency.BALANCED -> StepsPlan(revision, true, 60_000, 15_000, false)
		SourceCollectionFrequency.RESPONSIVE -> StepsPlan(revision, true, 5_000, 2_000, true)
	}

	private fun pressurePlan(frequency: SourceCollectionFrequency, revision: Long): PressurePlan = when (frequency) {
		SourceCollectionFrequency.OFF -> PressurePlan(revision, false, 1_000_000, 0, 60_000)
		SourceCollectionFrequency.BATTERY_SAVER -> PressurePlan(revision, true, 1_000_000, 60_000_000, 60_000)
		SourceCollectionFrequency.BALANCED -> PressurePlan(revision, true, 200_000, 10_000_000, 10_000)
		SourceCollectionFrequency.RESPONSIVE -> PressurePlan(revision, true, 50_000, 1_000_000, 2_000)
	}

	private fun wifiPlan(frequency: SourceCollectionFrequency, revision: Long): WifiPlan {
		val backoff = RetryBackoff(RADIO_RETRY_INITIAL_DELAY_MS, RADIO_RETRY_MAXIMUM_DELAY_MS)
		return when (frequency) {
			SourceCollectionFrequency.OFF -> WifiPlan(revision, WifiMode.OFF, 0, 0, 0, backoff)
			// Android has no distinct cached-only registration here. The lowest-cost live mechanism is
			// the same passive scan-result receiver, with no active scan attempts.
			SourceCollectionFrequency.BATTERY_SAVER -> WifiPlan(revision, WifiMode.BROADCAST_DRIVEN, 15 * 60_000,
                10 * 60_000, 30 * 60_000, backoff)
			SourceCollectionFrequency.BALANCED -> WifiPlan(revision, WifiMode.BROADCAST_DRIVEN, 5 * 60_000, 5 * 60_000,
                10 * 60_000, backoff)
			SourceCollectionFrequency.RESPONSIVE -> WifiPlan(revision, WifiMode.ACTIVE_ATTEMPTS, 60_000, 60_000,
                2 * 60_000, backoff)
		}
	}

	private fun cellPlan(
		frequency: SourceCollectionFrequency,
		revision: Long,
		subscriptionIds: Set<Int>,
	): CellPlan {
		val backoff = RetryBackoff(RADIO_RETRY_INITIAL_DELAY_MS, RADIO_RETRY_MAXIMUM_DELAY_MS)
		return when (frequency) {
			SourceCollectionFrequency.OFF -> CellPlan(revision, CellMode.OFF, 0, 0, subscriptionIds, backoff)
			SourceCollectionFrequency.BATTERY_SAVER -> CellPlan(revision, CellMode.OBSERVE_CHANGES, 15 * 60_000,
                10 * 60_000, subscriptionIds, backoff)
			SourceCollectionFrequency.BALANCED -> CellPlan(revision, CellMode.OBSERVE_CHANGES, 5 * 60_000, 5 * 60_000,
                subscriptionIds, backoff)
			SourceCollectionFrequency.RESPONSIVE -> CellPlan(revision, CellMode.OBSERVE_AND_SPARSE_REFRESH, 2 * 60_000,
                60_000, subscriptionIds, backoff)
		}
	}

	private data class LocationValues(
		val mode: LocationMode,
		val intervalMs: Long,
		val minimumIntervalMs: Long,
		val displacementMeters: Float,
		val maximumBatchDelayMs: Long,
	)
}

data class SourcePlanEnvironment(
	val locationBackend: LocationBackend,
	val preciseLocationAvailable: Boolean,
	val subscriptionIds: Set<Int>,
	/** Actual accepted live participation, never inferred from the lifetime of the shared host. */
	val liveSources: Set<SourceKind> = emptySet(),
)

/** Version-one adaptive targets. Legacy/custom request bytes never use this mapping. */
internal fun adaptiveLocationPlan(
	level: Int,
	revision: Long,
	backend: LocationBackend,
	preciseLocationAvailable: Boolean,
): LocationPlan {
	require(level in LocationAdaptiveSettings.BALANCE_LEVELS)
	val index = level - LocationAdaptiveSettings.BALANCE_LEVELS.first
	val mode = when {
		level == LocationAdaptiveSettings.BALANCE_LEVELS.first -> LocationMode.LOW_POWER
		level == 2 || !preciseLocationAvailable -> LocationMode.BALANCED
		else -> LocationMode.HIGH_ACCURACY
	}
	return LocationPlan(
		revision = revision,
		backend = backend,
		mode = mode,
		requestedIntervalMs = adaptiveLocationIntervalsMs[index],
		minimumUpdateIntervalMs = adaptiveLocationMinimumIntervalsMs[index],
		minimumDisplacementMeters = 0f,
		maximumBatchDelayMs = adaptiveLocationBatchDelaysMs[index],
		preciseLocationAvailable = preciseLocationAvailable,
		adaptiveProfileVersion = LocationAdaptiveSettings.PROFILE_VERSION,
	)
}

/** Only preferences change between levels; useful-evidence contracts stay fixed. */
internal fun adaptiveSourcePlan(
	source: SourceKind,
	level: Int,
	revision: Long,
	environment: SourcePlanEnvironment,
): SourcePlan {
	require(level in LocationAdaptiveSettings.BALANCE_LEVELS)
	val index = level - LocationAdaptiveSettings.BALANCE_LEVELS.first
	val backoff = RetryBackoff(RADIO_RETRY_INITIAL_DELAY_MS, RADIO_RETRY_MAXIMUM_DELAY_MS)
	return when (source) {
		SourceKind.LOCATION -> adaptiveLocationPlan(level, revision, environment.locationBackend,
			environment.preciseLocationAvailable)
		SourceKind.ACTIVITY -> ActivityPlan(revision, ActivityMode.CONTINUOUS_RECOGNITION,
			adaptiveActivityDetectionLatenciesMs[index], ADAPTIVE_ACTIVITY_MINIMUM_CONFIDENCE,
			setOf(0, 1), adaptiveProfileVersion = SourceBalanceSettings.PROFILE_VERSION)
		// Keep one cumulative-counter request across adaptive quality changes. Android owns delivery.
		SourceKind.STEPS -> StepsPlan(revision, true, ADAPTIVE_STEPS_REPORT_LATENCY_MS,
			adaptiveStepsCheckpointIntervalsMs[index], level >= SourceBalanceSettings.LIVE_LEVEL)
		SourceKind.PRESSURE -> PressurePlan(revision, true,
			adaptivePressureSamplePeriodsMicros[index], adaptivePressureReportLatenciesMicros[index],
			adaptivePressureAggregationWindowsMs[index])
		SourceKind.WIFI -> adaptiveWifiPlan(level, revision, backoff)
		SourceKind.CELL -> adaptiveCellPlan(level, revision, environment.subscriptionIds, backoff)
	}
}

private fun adaptiveWifiPlan(level: Int, revision: Long, backoff: RetryBackoff): WifiPlan = WifiPlan(
	revision = revision,
	mode = if (level >= SourceBalanceSettings.LIVE_LEVEL) {
		WifiMode.ACTIVE_ATTEMPTS
	} else {
		WifiMode.BROADCAST_DRIVEN
	},
	minimumAttemptIntervalMs = if (level == LocationAdaptiveSettings.BALANCE_LEVELS.last) {
		WIFI_MAXIMUM_QUALITY_ATTEMPT_INTERVAL_MS
	} else {
		WIFI_ORDINARY_ATTEMPT_INTERVAL_MS
	},
	maximumAcceptableResultAgeMs = ADAPTIVE_RADIO_MAXIMUM_RESULT_AGE_MS,
	unchangedResultDedupeWindowMs = ADAPTIVE_RADIO_MAXIMUM_RESULT_AGE_MS,
	backoff = backoff,
)

private fun adaptiveCellPlan(
	level: Int,
	revision: Long,
	subscriptionIds: Set<Int>,
	backoff: RetryBackoff,
): CellPlan = CellPlan(
	revision = revision,
	mode = if (level >= SourceBalanceSettings.LIVE_LEVEL) {
		CellMode.OBSERVE_AND_SPARSE_REFRESH
	} else {
		CellMode.OBSERVE_CHANGES
	},
	minimumRefreshAttemptIntervalMs = if (level == LocationAdaptiveSettings.BALANCE_LEVELS.last) {
		CELL_MAXIMUM_QUALITY_REFRESH_INTERVAL_MS
	} else {
		CELL_ORDINARY_REFRESH_INTERVAL_MS
	},
	maximumAcceptableCachedAgeMs = ADAPTIVE_RADIO_MAXIMUM_RESULT_AGE_MS,
	subscriptionIds = subscriptionIds,
	backoff = backoff,
)

// Index zero is balance level one; these are target rates, independently of participation and floors.
private val adaptiveLocationIntervalsMs = listOf(60_000L, 30_000L, 10_000L, 5_000L, 1_000L)
private val adaptiveLocationMinimumIntervalsMs = listOf(30_000L, 15_000L, 10_000L, 2_500L, 500L)
private val adaptiveLocationBatchDelaysMs = listOf(120_000L, 60_000L, 50_000L, 10_000L, 2_000L)
private val adaptiveActivityDetectionLatenciesMs = listOf(60_000L, 45_000L, 30_000L, 15_000L, 5_000L)
private val adaptiveStepsCheckpointIntervalsMs = listOf(60_000L, 30_000L, 15_000L, 5_000L, 2_000L)
private val adaptivePressureSamplePeriodsMicros = listOf(1_000_000, 500_000, 200_000, 100_000, 50_000)
private val adaptivePressureReportLatenciesMicros = listOf(60_000_000, 30_000_000, 10_000_000, 5_000_000, 1_000_000)
private val adaptivePressureAggregationWindowsMs = listOf(60_000L, 30_000L, 10_000L, 5_000L, 2_000L)
private const val ADAPTIVE_ACTIVITY_MINIMUM_CONFIDENCE = 65
private const val ADAPTIVE_STEPS_REPORT_LATENCY_MS = 5_000L
private const val RADIO_RETRY_INITIAL_DELAY_MS = 30_000L
private const val RADIO_RETRY_MAXIMUM_DELAY_MS = 1_800_000L
internal const val ADAPTIVE_RADIO_MAXIMUM_RESULT_AGE_MS = 600_000L
private const val WIFI_MAXIMUM_QUALITY_ATTEMPT_INTERVAL_MS = 60_000L
private const val WIFI_ORDINARY_ATTEMPT_INTERVAL_MS = 120_000L
private const val CELL_MAXIMUM_QUALITY_REFRESH_INTERVAL_MS = 120_000L
private const val CELL_ORDINARY_REFRESH_INTERVAL_MS = 300_000L
