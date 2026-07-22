package com.adsamcik.tracker.osm.io

import com.adsamcik.tracker.osm.OsmRoadClass

/**
 * In-memory representation of one driveable OSM way produced by
 * [OsmPbfStreamingParser]. Worker code converts these into Room entities and
 * writes them in batched transactions.
 *
 * The polyline is pre-encoded so the worker doesn't have to re-traverse the
 * coordinate arrays on the database thread.
 */
data class ParsedOsmWay(
	val osmId: Long,
	val name: String?,
	val roadClass: OsmRoadClass,
	val maxspeedKmh: Int,
	val maxspeedExplicit: Boolean,
	val isOneway: Boolean,
	val geomPolylineE7: ByteArray,
	val bboxMinLatE7: Int,
	val bboxMaxLatE7: Int,
	val bboxMinLonE7: Int,
	val bboxMaxLonE7: Int,
	val cellKeys: LongArray,
) {
	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is ParsedOsmWay) return false
		return osmId == other.osmId &&
			name == other.name &&
			roadClass == other.roadClass &&
			maxspeedKmh == other.maxspeedKmh &&
			maxspeedExplicit == other.maxspeedExplicit &&
			isOneway == other.isOneway &&
			geomPolylineE7.contentEquals(other.geomPolylineE7) &&
			bboxMinLatE7 == other.bboxMinLatE7 &&
			bboxMaxLatE7 == other.bboxMaxLatE7 &&
			bboxMinLonE7 == other.bboxMinLonE7 &&
			bboxMaxLonE7 == other.bboxMaxLonE7 &&
			cellKeys.contentEquals(other.cellKeys)
	}

	override fun hashCode(): Int {
		var r = osmId.hashCode()
		r = 31 * r + (name?.hashCode() ?: 0)
		r = 31 * r + roadClass.hashCode()
		r = 31 * r + maxspeedKmh
		r = 31 * r + maxspeedExplicit.hashCode()
		r = 31 * r + isOneway.hashCode()
		r = 31 * r + geomPolylineE7.contentHashCode()
		r = 31 * r + bboxMinLatE7
		r = 31 * r + bboxMaxLatE7
		r = 31 * r + bboxMinLonE7
		r = 31 * r + bboxMaxLonE7
		r = 31 * r + cellKeys.contentHashCode()
		return r
	}
}

/** Aggregate statistics for one completed PBF import. */
data class OsmParseStats(
	val nodeCount: Long,
	val wayCount: Long,
	/** Independent extrema for import diagnostics; never a spatial bbox. */
	val diagnosticMinLatitudeE7: Int,
	val diagnosticMaxLatitudeE7: Int,
	/** Ordered longitude extrema for diagnostics; not a circular interval. */
	val diagnosticMinLongitudeE7: Int,
	val diagnosticMaxLongitudeE7: Int,
	/** Aggregate count of target PBF nodes rejected before unsafe E7 narrowing. */
	val rejectedInvalidCoordinateNodes: Long = 0L,
	/** Aggregate count of ways whose circular longitude interval was full. */
	val rejectedInvalidLongitudeCoverageWays: Long = 0L,
	/** Aggregate count of ways whose bounded cell coverage was too large. */
	val rejectedExcessiveCellCoverageWays: Long = 0L,
)

/** Phase identifier reported to [OsmParseProgress] callbacks. */
enum class OsmParsePhase {
	SCAN_WAYS,
	SCAN_NODES,
	EMIT_WAYS,
}

/** Coarse-grained progress update emitted by the parser. */
data class OsmParseProgress(
	val phase: OsmParsePhase,
	val itemsProcessed: Long,
)
