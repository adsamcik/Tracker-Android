package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
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
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.model.WifiMode
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import javax.inject.Inject

class SemanticAcquisitionPlanFactory @Inject constructor() : SourceAcquisitionPlanFactory {
	override fun create(
		settings: TrackingParamsState,
		revision: Long,
		createdAtMs: Long,
		environment: SourcePlanEnvironment,
	): AcquisitionPlanRevision {
        fun legacy(source: SourceKind, frequency: SourceCollectionFrequency): SourceCollectionFrequency =
            if (!settings.captureParticipates(TrackingSourceComponent.fromStableCode(source.stableCode))) SourceCollectionFrequency.OFF
            else frequency.takeUnless { it == SourceCollectionFrequency.OFF } ?: SourceCollectionFrequency.BALANCED
        val stored = settings.sourceCollectionSettings
        val frequency = stored.copy(
            location = legacy(SourceKind.LOCATION, stored.location), activity = legacy(SourceKind.ACTIVITY, stored.activity),
            steps = legacy(SourceKind.STEPS, stored.steps), pressure = legacy(SourceKind.PRESSURE, stored.pressure),
            wifi = legacy(SourceKind.WIFI, stored.wifi), cell = legacy(SourceKind.CELL, stored.cell),
        )
        val preferences = if (settings.balanceProfileVersion == null) emptyMap() else SourceKind.entries.associateWith { source ->
            val component = TrackingSourceComponent.fromStableCode(source.stableCode)
            val baseline = requireNotNull(settings.baselineBalanceLevel(component))
            val focus = settings.captureParticipates(component) && source in environment.liveSources
            SourceBalancePreference(if (focus) maxOf(baseline, 4) else baseline, focus)
        }
		return AcquisitionPlanRevision(
			revision = revision,
			planId = "semantic-v${settings.sourceSettingsVersion}-${settings.presetName.lowercase()}",
			createdAtMs = createdAtMs,
			sourcePolicyRevision = settings.sourcePolicyRevision,
			balanceProfileVersion = settings.balanceProfileVersion,
			sourcePreferences = preferences,
			plans = mapOf(
				SourceKind.LOCATION to locationPlan(settings, frequency.location, revision, environment),
				SourceKind.ACTIVITY to if (preferences.isNotEmpty() && frequency.activity != SourceCollectionFrequency.OFF)
                    adaptiveSourcePlan(SourceKind.ACTIVITY, preferences.getValue(SourceKind.ACTIVITY).requestedLevel, revision, environment) else activityPlan(frequency.activity, revision),
				SourceKind.STEPS to if (preferences.isNotEmpty() && frequency.steps != SourceCollectionFrequency.OFF)
                    adaptiveSourcePlan(SourceKind.STEPS, preferences.getValue(SourceKind.STEPS).requestedLevel, revision, environment) else stepsPlan(frequency.steps, revision),
				SourceKind.PRESSURE to if (preferences.isNotEmpty() && frequency.pressure != SourceCollectionFrequency.OFF)
                    adaptiveSourcePlan(SourceKind.PRESSURE, preferences.getValue(SourceKind.PRESSURE).requestedLevel, revision, environment) else pressurePlan(frequency.pressure, revision),
				SourceKind.WIFI to if (preferences.isNotEmpty() && frequency.wifi != SourceCollectionFrequency.OFF)
                    adaptiveSourcePlan(SourceKind.WIFI, preferences.getValue(SourceKind.WIFI).requestedLevel, revision, environment) else wifiPlan(frequency.wifi, revision),
				SourceKind.CELL to if (preferences.isNotEmpty() && frequency.cell != SourceCollectionFrequency.OFF)
                    adaptiveSourcePlan(SourceKind.CELL, preferences.getValue(SourceKind.CELL).requestedLevel, revision, environment) else cellPlan(frequency.cell, revision, environment.subscriptionIds),
			),
		)
	}

	private fun locationPlan(
		settings: TrackingParamsState,
		frequency: SourceCollectionFrequency,
		revision: Long,
		environment: SourcePlanEnvironment,
	): LocationPlan {
		if (settings.locationProfileVersion == 1 && frequency != SourceCollectionFrequency.OFF) {
			val savedLevel = requireNotNull(settings.locationBalanceLevel)
			val level = if (SourceKind.LOCATION in environment.liveSources) maxOf(savedLevel, 4) else savedLevel
			return adaptiveLocationPlan(level, revision, environment.locationBackend, environment.preciseLocationAvailable)
		}
		val values = when (frequency) {
			SourceCollectionFrequency.OFF -> LocationValues(LocationMode.DISABLED, 60_000, 60_000, 100f, 0)
			SourceCollectionFrequency.BATTERY_SAVER -> LocationValues(LocationMode.LOW_POWER, 60_000, 30_000, 50f, 120_000)
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
		SourceCollectionFrequency.BATTERY_SAVER -> ActivityPlan(revision, ActivityMode.CONTINUOUS_RECOGNITION, 60_000, 75, setOf(0, 1))
		SourceCollectionFrequency.BALANCED -> ActivityPlan(revision, ActivityMode.CONTINUOUS_RECOGNITION, 30_000, 65, setOf(0, 1))
		SourceCollectionFrequency.RESPONSIVE -> ActivityPlan(revision, ActivityMode.CONTINUOUS_RECOGNITION, 5_000, 55, setOf(0, 1))
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
		val backoff = RetryBackoff(30_000, 30 * 60_000L)
		return when (frequency) {
			SourceCollectionFrequency.OFF -> WifiPlan(revision, WifiMode.OFF, 0, 0, 0, backoff)
			// Android has no distinct cached-only registration here. The lowest-cost live mechanism is
			// the same passive scan-result receiver, with no active scan attempts.
			SourceCollectionFrequency.BATTERY_SAVER -> WifiPlan(revision, WifiMode.BROADCAST_DRIVEN, 15 * 60_000, 10 * 60_000, 30 * 60_000, backoff)
			SourceCollectionFrequency.BALANCED -> WifiPlan(revision, WifiMode.BROADCAST_DRIVEN, 5 * 60_000, 5 * 60_000, 10 * 60_000, backoff)
			SourceCollectionFrequency.RESPONSIVE -> WifiPlan(revision, WifiMode.ACTIVE_ATTEMPTS, 60_000, 60_000, 2 * 60_000, backoff)
		}
	}

	private fun cellPlan(
		frequency: SourceCollectionFrequency,
		revision: Long,
		subscriptionIds: Set<Int>,
	): CellPlan {
		val backoff = RetryBackoff(30_000, 30 * 60_000L)
		return when (frequency) {
			SourceCollectionFrequency.OFF -> CellPlan(revision, CellMode.OFF, 0, 0, subscriptionIds, backoff)
			SourceCollectionFrequency.BATTERY_SAVER -> CellPlan(revision, CellMode.OBSERVE_CHANGES, 15 * 60_000, 10 * 60_000, subscriptionIds, backoff)
			SourceCollectionFrequency.BALANCED -> CellPlan(revision, CellMode.OBSERVE_CHANGES, 5 * 60_000, 5 * 60_000, subscriptionIds, backoff)
			SourceCollectionFrequency.RESPONSIVE -> CellPlan(revision, CellMode.OBSERVE_AND_SPARSE_REFRESH, 2 * 60_000, 60_000, subscriptionIds, backoff)
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
	require(level in 1..5)
	val interval = when (level) { 1 -> 60_000L; 2 -> 30_000L; 3 -> 10_000L; 4 -> 5_000L; else -> 1_000L }
	val minimum = when (level) { 1 -> 30_000L; 2 -> 15_000L; 3 -> 10_000L; 4 -> 2_500L; else -> 500L }
	val batch = when (level) { 1 -> 120_000L; 2 -> 60_000L; 3 -> 50_000L; 4 -> 10_000L; else -> 2_000L }
	val mode = when {
		level == 1 -> LocationMode.LOW_POWER
		level == 2 || !preciseLocationAvailable -> LocationMode.BALANCED
		else -> LocationMode.HIGH_ACCURACY
	}
	return LocationPlan(
		revision, backend, mode, interval, minimum, 0f, batch,
		preciseLocationAvailable = preciseLocationAvailable,
		adaptiveProfileVersion = 1,
	)
}

/** Only preferences change between levels; useful-evidence contracts stay fixed. */
internal fun adaptiveSourcePlan(source: SourceKind, level: Int, revision: Long, environment: SourcePlanEnvironment): com.adsamcik.tracker.tracker.source.model.SourcePlan {
    require(level in 1..5)
    val index = level - 1
    val backoff = RetryBackoff(30_000L, 30 * 60_000L)
    return when (source) {
        SourceKind.LOCATION -> adaptiveLocationPlan(level, revision, environment.locationBackend, environment.preciseLocationAvailable)
        SourceKind.ACTIVITY -> ActivityPlan(revision, ActivityMode.CONTINUOUS_RECOGNITION,
            listOf(60_000L, 45_000L, 30_000L, 15_000L, 5_000L)[index], 65, setOf(0, 1), adaptiveProfileVersion = 1)
        SourceKind.STEPS -> StepsPlan(revision, true,
            listOf(300_000L, 120_000L, 60_000L, 15_000L, 5_000L)[index],
            listOf(60_000L, 30_000L, 15_000L, 5_000L, 2_000L)[index], level >= 4)
        SourceKind.PRESSURE -> PressurePlan(revision, true,
            listOf(1_000_000, 500_000, 200_000, 100_000, 50_000)[index],
            listOf(60_000_000, 30_000_000, 10_000_000, 5_000_000, 1_000_000)[index],
            listOf(60_000L, 30_000L, 10_000L, 5_000L, 2_000L)[index])
        SourceKind.WIFI -> WifiPlan(revision,
            if (level >= 4) WifiMode.ACTIVE_ATTEMPTS else WifiMode.BROADCAST_DRIVEN,
            if (level == 5) 60_000L else 120_000L, 600_000L, 600_000L, backoff)
        SourceKind.CELL -> CellPlan(revision,
            if (level >= 4) CellMode.OBSERVE_AND_SPARSE_REFRESH else CellMode.OBSERVE_CHANGES,
            if (level == 5) 120_000L else 300_000L, 600_000L, environment.subscriptionIds, backoff)
    }
}
