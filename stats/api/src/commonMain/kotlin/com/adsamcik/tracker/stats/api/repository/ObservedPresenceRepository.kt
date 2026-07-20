package com.adsamcik.tracker.stats.api.repository

/** Geographic viewport for an observed-presence query. East may be less than west at the antimeridian. */
data class ObservedPresenceBounds(
	val north: Double,
	val east: Double,
	val south: Double,
	val west: Double,
) {
	init {
		require(north in -90.0..90.0 && south in -90.0..90.0)
		require(north >= south)
		require(east in -180.0..180.0 && west in -180.0..180.0)
	}

	val crossesAntimeridian: Boolean get() = east < west
}

/**
 * A half-open time-window query for observation-supported presence.
 *
 * [preferredResolutionM] is a display preference, not permission to make a claim finer than the
 * estimator supports. The repository can coarsen it for uncertainty or [maxCells].
 */
data class ObservedPresenceRequest(
	val fromMs: Long,
	val toMsExclusive: Long,
	val bounds: ObservedPresenceBounds? = null,
	val preferredResolutionM: Int,
	val maxCells: Int,
) {
	init {
		require(toMsExclusive > fromMs)
		require(preferredResolutionM > 0)
		require(maxCells > 0)
	}
}

data class ObservedPresenceCell(
	val cellId: String,
	val resolutionM: Int,
	val north: Double,
	val east: Double,
	val south: Double,
	val west: Double,
	val expectedSeconds: Double,
	val observedSeconds: Double,
	val inferredSeconds: Double,
)

enum class ObservedPresenceCoverageState {
	/** No tracked interval overlaps the selected range. */
	EMPTY,

	/** All selected tracked time is represented by a resolved spatial posterior. */
	COMPLETE,

	/** Some tracked time is unresolved or too uncertain for the selected display resolution. */
	PARTIAL,

	/** Historical partitions are still being materialized; the returned cells are safe but partial. */
	MATERIALIZING,
}

/** Selection-wide coverage; unlike cell intensity, these values are not viewport-normalized. */
data class ObservedPresenceCoverage(
	val state: ObservedPresenceCoverageState,
	val trackedSeconds: Double,
	val spatiallyObservedSeconds: Double,
	val spatiallyInferredSeconds: Double,
	val spatiallyUnresolvedSeconds: Double,
	/** Resolved seconds whose uncertainty supports [ObservedPresenceSnapshot.resolutionM] or finer. */
	val supportedAtDisplayResolutionSeconds: Double,
	/** Supported seconds omitted only from this response because its feature budget was exhausted. */
	val omittedPresentationSeconds: Double,
)

data class ObservedPresenceSnapshot(
	val cells: List<ObservedPresenceCell>,
	val resolutionM: Int,
	val isCellBudgetTruncated: Boolean,
	val metricKind: String,
	val modelKey: String,
	val estimatorVersion: Int,
	val calibrationVersion: Int,
	val gridVersion: Int,
	val coverage: ObservedPresenceCoverage,
)

interface ObservedPresenceRepository {
	suspend fun load(request: ObservedPresenceRequest): ObservedPresenceSnapshot
}
