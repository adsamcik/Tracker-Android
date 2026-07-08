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
 * One matched span of travel between two consecutive observations, snapped to
 * real road geometry.
 *
 * [fromIndex]/[toIndex] index back into the observation list passed to
 * [RoadMatcher.match] so callers can recover per-sample data (e.g. speed).
 * [path] follows the actual road centre-line — it starts at the snapped
 * position of [fromIndex], walks the intermediate OSM way vertices, and ends
 * at the snapped position of [toIndex] (always >= 2 points). [maxspeedKmh] is
 * the resolved speed limit of the road this span lies on.
 *
 * Spans that could not be matched (off-road, beyond the snap radius, across a
 * trip gap, or between two unconnected roads) are simply absent from the
 * result — there is no "unmatched" edge.
 */
data class MatchedEdge(
	val fromIndex: Int,
	val toIndex: Int,
	val path: List<RoadPoint>,
	val maxspeedKmh: Int,
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
