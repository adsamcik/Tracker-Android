package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.SourceKind

data class TrackingRolloutState(
	val revision: Long,
	val schemaVersion: Int,
	val coordinatorMode: CoordinatorMode,
	val projectionMode: ProjectionMode,
	val sourceOwners: Map<SourceKind, SourceOwner>,
	val semanticSettingsEnabled: Boolean,
	val batteryEstimateMode: BatteryEstimateMode,
) {
	init {
		require(revision >= 0L)
		require(schemaVersion > 0)
		require(sourceOwners.keys.containsAll(SourceKind.entries)) {
			"Rollout state must explicitly assign every physical source"
		}
		require(coordinatorMode == CoordinatorMode.EVENT || sourceOwners.values.none { it == SourceOwner.EVENT }) {
			"Event-owned sources require the event coordinator"
		}
		require(projectionMode != ProjectionMode.EVENT_CANONICAL || coordinatorMode == CoordinatorMode.EVENT) {
			"Canonical event projections require the event coordinator"
		}
		require(projectionMode != ProjectionMode.EVENT_CANONICAL || sourceOwners.values.all { it == SourceOwner.EVENT }) {
			"Canonical event projections require event ownership for every physical source"
		}
	}

	companion object {
		/** Phase 10 production state: source-native acquisition and event projections are canonical. */
		fun eventCanonical(revision: Long = 1L): TrackingRolloutState = TrackingRolloutState(
			revision = revision,
			schemaVersion = 2,
			coordinatorMode = CoordinatorMode.EVENT,
			projectionMode = ProjectionMode.EVENT_CANONICAL,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.EVENT },
			semanticSettingsEnabled = true,
			batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
		)

		/** Decode/rollback fixture retained for the supported release-window migration. */
		fun legacy(revision: Long = 0L): TrackingRolloutState = TrackingRolloutState(
			revision = revision,
			schemaVersion = 1,
			coordinatorMode = CoordinatorMode.LEGACY,
			projectionMode = ProjectionMode.LEGACY_ONLY,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.LEGACY },
			semanticSettingsEnabled = false,
			batteryEstimateMode = BatteryEstimateMode.LEGACY_QUALITATIVE,
		)
	}
}

enum class CoordinatorMode { LEGACY, EVENT }
enum class SourceOwner { LEGACY, EVENT }
enum class ProjectionMode { LEGACY_ONLY, SHADOW_READ_ONLY, EVENT_CANONICAL }
enum class BatteryEstimateMode { LEGACY_QUALITATIVE, SOURCE_PLAN_QUALITATIVE, CALIBRATED }
