package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.tracker.source.model.SourceKind

data class TrackingRolloutState(
	val revision: Long,
	val schemaVersion: Int,
	val coordinatorMode: CoordinatorMode,
	val sourceOwners: Map<SourceKind, SourceOwner>,
	val productProjectionStages: Map<SourceKind, ProductProjectionStage>,
	val captureModeMasks: Map<SourceKind, Long>,
	val semanticSettingsEnabled: Boolean,
	val batteryEstimateMode: BatteryEstimateMode,
) {
	init {
		require(revision >= 0L)
		require(schemaVersion > 0)
		require(sourceOwners.keys == SourceKind.entries.toSet()) {
			"Rollout state must explicitly assign every physical source"
		}
		require(productProjectionStages.keys == SourceKind.entries.toSet()) {
			"Rollout state must explicitly gate every source product projection"
		}
		require(captureModeMasks.keys == SourceKind.entries.toSet()) {
			"Rollout state must explicitly gate every source capture mode"
		}
		require(captureModeMasks.values.all { mask ->
			mask >= 0L && mask and CaptureReachabilityMode.ALL_MASK.inv() == 0L
		}) { "Capture mode masks must contain only known modes" }
		require(coordinatorMode == CoordinatorMode.EVENT || sourceOwners.values.none {
			it == SourceOwner.EVENT || it == SourceOwner.CONTROL
		}) {
			"Event and control-operational sources require the event coordinator"
		}
		require(productProjectionStages.none { (source, stage) ->
			stage != ProductProjectionStage.LEGACY_CANONICAL &&
				(coordinatorMode != CoordinatorMode.EVENT || sourceOwners[source] != SourceOwner.EVENT)
		}) {
			"An event projection requires event acquisition ownership for that source"
		}
		require(sourceOwners.none { (source, owner) ->
			owner == SourceOwner.EVENT &&
				productProjectionStages[source] == ProductProjectionStage.LEGACY_CANONICAL
		}) {
			"Event acquisition requires a reachable source-local shadow or canonical product lane"
		}
		require(sourceOwners.none { (source, owner) ->
			owner == SourceOwner.CONTROL &&
				productProjectionStages[source] != ProductProjectionStage.LEGACY_CANONICAL
		}) {
			"Control-only acquisition cannot authorize a capture product lane"
		}
		require(sourceOwners.none { (source, owner) ->
			(owner == SourceOwner.EVENT) != (captureModeMasks.getValue(source) != 0L)
		}) { "Only event-owned sources may authorize session capture modes" }
	}

	/** A provider may acquire only when its source-local product lane is explicitly reachable. */
	fun isAcquisitionReachable(source: SourceKind): Boolean =
		coordinatorMode == CoordinatorMode.EVENT &&
			sourceOwners[source] == SourceOwner.EVENT &&
			captureModeMasks.getValue(source) != 0L &&
			productProjectionStages[source] in setOf(
				ProductProjectionStage.EVENT_SHADOW,
				ProductProjectionStage.EVENT_CANONICAL,
			)

	fun isCaptureReachable(source: SourceKind, mode: CaptureReachabilityMode): Boolean =
		isAcquisitionReachable(source) && captureModeMasks.getValue(source) and mode.mask != 0L

	/** A provider may satisfy declared control demands without becoming a captured product source. */
	fun isControlAcquisitionReachable(source: SourceKind): Boolean =
		coordinatorMode == CoordinatorMode.EVENT &&
			(sourceOwners[source] == SourceOwner.EVENT || sourceOwners[source] == SourceOwner.CONTROL)

	companion object {
		/**
		 * Safe unreleased-v28 bootstrap. Existing product facts remain readable through their legacy
		 * destinations, but no source-native provider is eligible to start until that source has an
		 * explicitly reachable shadow lane.
		 */
		fun contained(revision: Long = 1L): TrackingRolloutState = TrackingRolloutState(
			revision = revision,
			schemaVersion = CURRENT_SCHEMA_VERSION,
			coordinatorMode = CoordinatorMode.EVENT,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.CONTAINED },
			productProjectionStages = SourceKind.entries.associateWith {
				ProductProjectionStage.LEGACY_CANONICAL
			},
			captureModeMasks = SourceKind.entries.associateWith { 0L },
			semanticSettingsEnabled = true,
			batteryEstimateMode = BatteryEstimateMode.SOURCE_PLAN_QUALITATIVE,
		)

		/**
		 * Enables source-native acquisition only for sources with a real source-local shadow lane.
		 * Callers must name the sources deliberately; there is no all-source default promotion.
		 * Control sources may serve declared dependencies, but retain no event product lane.
		 */
		fun eventShadow(
			sources: Set<SourceKind>,
			revision: Long = 1L,
			controlSources: Set<SourceKind> = emptySet(),
			captureModes: Map<SourceKind, Set<CaptureReachabilityMode>> =
				sources.associateWith { setOf(CaptureReachabilityMode.MANUAL_SESSION_CAPTURE) },
		): TrackingRolloutState {
			require(sources.isNotEmpty()) { "At least one shadow-reachable source is required" }
			require(sources.intersect(controlSources).isEmpty()) {
				"A source cannot be both capture-owned and control-only"
			}
			require(captureModes.keys == sources && captureModes.values.all(Set<CaptureReachabilityMode>::isNotEmpty)) {
				"Every capture source requires one or more explicit session capture modes"
			}
			return contained(revision).copy(
				sourceOwners = SourceKind.entries.associateWith { source ->
					when (source) {
						in sources -> SourceOwner.EVENT
						in controlSources -> SourceOwner.CONTROL
						else -> SourceOwner.CONTAINED
					}
				},
				productProjectionStages = SourceKind.entries.associateWith { source ->
					if (source in sources) {
						ProductProjectionStage.EVENT_SHADOW
					} else {
						ProductProjectionStage.LEGACY_CANONICAL
					}
				},
				captureModeMasks = SourceKind.entries.associateWith { source ->
					captureModes[source]?.fold(0L) { mask, mode -> mask or mode.mask } ?: 0L
				},
			)
		}

		/** Decode/rollback fixture retained for the supported release-window migration. */
		fun legacy(revision: Long = 0L): TrackingRolloutState = TrackingRolloutState(
			revision = revision,
			schemaVersion = 1,
			coordinatorMode = CoordinatorMode.LEGACY,
			sourceOwners = SourceKind.entries.associateWith { SourceOwner.LEGACY },
			productProjectionStages = SourceKind.entries.associateWith {
				ProductProjectionStage.LEGACY_CANONICAL
			},
			captureModeMasks = SourceKind.entries.associateWith { 0L },
			semanticSettingsEnabled = false,
			batteryEstimateMode = BatteryEstimateMode.LEGACY_QUALITATIVE,
		)

		const val CURRENT_SCHEMA_VERSION: Int = 4
	}
}

enum class CaptureReachabilityMode(val mask: Long) {
	MANUAL_SESSION_CAPTURE(1L shl 0),
	AUTOMATIC_SESSION_CAPTURE(1L shl 1),
	AMBIENT(1L shl 2),
	;

	companion object {
		val ALL_MASK: Long = entries.fold(0L) { mask, mode -> mask or mode.mask }
	}
}

enum class CoordinatorMode { LEGACY, EVENT }
enum class SourceOwner { LEGACY, EVENT, CONTROL, CONTAINED }
enum class ProductProjectionStage { LEGACY_CANONICAL, EVENT_SHADOW, EVENT_CANONICAL }
enum class BatteryEstimateMode { LEGACY_QUALITATIVE, SOURCE_PLAN_QUALITATIVE, CALIBRATED }
