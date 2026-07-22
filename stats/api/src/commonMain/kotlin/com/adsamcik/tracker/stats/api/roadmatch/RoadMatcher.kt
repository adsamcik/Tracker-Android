package com.adsamcik.tracker.stats.api.roadmatch

/**
 * One GPS observation fed to the [RoadMatcher]. Coordinates are in E7
 * (1e-7 degrees) to match the on-device storage format. [accuracyM] is the
 * horizontal accuracy radius in metres (used as the HMM emission sigma);
 * pass a sensible default when unknown. Observations MUST be supplied in
 * ascending time order.
 */
data class RoadObservation(
	val latE7: Int,
	val lonE7: Int,
	val accuracyM: Float,
	val timeMs: Long,
)

/** A single point of road-snapped geometry in E7 coordinates. */
data class RoadPoint(val latE7: Int, val lonE7: Int)

/**
 * How the speed limit attached to a matched edge was obtained.
 *
 * Consumers that make a user-facing compliance claim must distinguish an
 * explicit OSM tag from a road-class fallback.  The latter is useful to a
 * matcher, but it is not a confirmed legal limit.
 */
enum class RoadLimitProvenance {
	/** A parseable `maxspeed=*` tag on the selected OSM way. */
	EXPLICIT_OSM_TAG,
	/** A local road-class default, not a confirmed limit. */
	ROAD_CLASS_HEURISTIC,
	/** The source did not provide enough information to classify the limit. */
	UNKNOWN,
	/** The source provided a limit value which cannot support compliance output. */
	UNSUPPORTED,
}

/**
 * Matching status for an edge-shaped result. The offline matcher preserves
 * declared gaps and no-path transitions as non-[MATCHED] edges so adapters can
 * retain their unavailable reason without turning it into a normal compliance
 * colour. [AMBIGUOUS] is reserved for a future matcher policy that has an
 * explicitly selected ambiguity contract.
 */
enum class RoadMatchStatus {
	MATCHED,
	AMBIGUOUS,
	NO_PATH,
	GAP,
}

/**
 * One matched span of travel between two consecutive observations, snapped to
 * real road geometry.
 *
 * [fromIndex]/[toIndex] index back into the observation list passed to
 * [RoadMatcher.match] so callers can recover per-sample data (e.g. speed).
 * [path] follows the actual road centre-line — it starts at the snapped
 * position of [fromIndex], walks the intermediate OSM way vertices, and ends
 * at the snapped position of [toIndex] (always >= 2 points). [maxspeedKmh] is
 * the resolved speed limit of the road this span lies on.  Its origin is
 * explicit in [limitProvenance], because a road-class default is not suitable
 * for a confirmed compliance assertion.
 *
 * Spans that could not be matched carry [matchStatus] with an empty [path].
 * They are diagnostic/unavailable results, not renderable road geometry.
 */
data class MatchedEdge(
	val fromIndex: Int,
	val toIndex: Int,
	val path: List<RoadPoint>,
	val maxspeedKmh: Int? = null,
	val limitProvenance: RoadLimitProvenance = RoadLimitProvenance.UNKNOWN,
	val matchStatus: RoadMatchStatus = RoadMatchStatus.MATCHED,
	/** Selected local OSM import when the matcher can identify it. */
	val importId: Long? = null,
	/** Selected OSM way when the matcher can identify it. */
	val osmWayId: Long? = null,
)

/**
 * Offline map matcher: maps a sequence of GPS observations onto the locally
 * imported OSM road graph and returns the road-snapped spans of travel.
 *
 * Implementations MUST be offline-only (the tracker has a hard no-network
 * rule) and MUST return an empty list when no road data is available, so the
 * caller can render an explicit "no road data" state rather than guessing.
 */
fun interface RoadMatcher {
	suspend fun match(observations: List<RoadObservation>): List<MatchedEdge>
}
