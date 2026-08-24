package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.preferences.tracking.SourceCollectionFrequency
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsState
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

class SemanticAcquisitionPlanFactory @Inject constructor() {
	fun create(
		settings: TrackingParamsState,
		revision: Long,
		createdAtMs: Long,
		environment: SourcePlanEnvironment,
	): AcquisitionPlanRevision {
		val frequency = settings.sourceCollectionSettings
		return AcquisitionPlanRevision(
			revision = revision,
			planId = "semantic-v${settings.sourceSettingsVersion}-${settings.presetName.lowercase()}",
			createdAtMs = createdAtMs,
			sourcePolicyRevision = settings.sourcePolicyRevision,
			plans = mapOf(
				SourceKind.LOCATION to locationPlan(settings, frequency.location, revision, environment),
				SourceKind.ACTIVITY to activityPlan(frequency.activity, revision),
				SourceKind.STEPS to stepsPlan(frequency.steps, revision),
				SourceKind.PRESSURE to pressurePlan(frequency.pressure, revision),
				SourceKind.WIFI to wifiPlan(frequency.wifi, revision),
				SourceKind.CELL to cellPlan(frequency.cell, revision, environment.subscriptionIds),
			),
		)
	}

	private fun locationPlan(
		settings: TrackingParamsState,
		frequency: SourceCollectionFrequency,
		revision: Long,
		environment: SourcePlanEnvironment,
	): LocationPlan {
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
		SourceCollectionFrequency.OFF -> PressurePlan(revision, false, 1_000_000, 0, 60_000, false)
		SourceCollectionFrequency.BATTERY_SAVER -> PressurePlan(revision, true, 1_000_000, 60_000_000, 60_000, true)
		SourceCollectionFrequency.BALANCED -> PressurePlan(revision, true, 200_000, 10_000_000, 10_000, false)
		SourceCollectionFrequency.RESPONSIVE -> PressurePlan(revision, true, 50_000, 1_000_000, 2_000, false)
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
)
