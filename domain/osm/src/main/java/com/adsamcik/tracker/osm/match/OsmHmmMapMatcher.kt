package com.adsamcik.tracker.osm.match

import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.OsmCellCoverage
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import com.adsamcik.tracker.shared.model.geo.CheckedCoordinateE7
import com.adsamcik.tracker.stats.api.roadmatch.MatchedEdge
import com.adsamcik.tracker.stats.api.roadmatch.RoadLimitProvenance
import com.adsamcik.tracker.stats.api.roadmatch.RoadMatchStatus
import com.adsamcik.tracker.stats.api.roadmatch.RoadObservation
import com.adsamcik.tracker.stats.api.roadmatch.RoadPoint
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Offline HMM/Viterbi map matcher over the locally imported OSM road graph.
 *
 * Pipeline:
 *  1. Batch-load every candidate way near the track (grid-cell lookup ->
 *     way ids -> way rows), decoding each polyline + arc-length table once.
 *  2. Per observation, project onto nearby ways and keep the closest few
 *     within [SNAP_RADIUS_M] as HMM states.
 *  3. Split the observation stream into runs at gaps (no candidate, or a large
 *     time/space jump = separate trip).
 *  4. Run Viterbi per run (Newson & Krumm 2009 emission/transition, adapted to
 *     work without a routing graph) to pick the most likely road segment for
 *     each observation.
 *  5. Emit [MatchedEdge]s that follow the real road centre-line between
 *     consecutive matched observations.
 *
 * Mirrors [com.adsamcik.tracker.osm.speed.OsmSpeedLimitSource]'s role as a
 * plain `@Singleton` lookup: it does NOT implement the `RoadMatcher` contract
 * itself — `:stats-data`'s `DefaultRoadMatcher` owns the OSM-import gating so
 * this class stays free of preference/lifecycle concerns.
 *
 * Strictly offline: every input comes from Room and every computation is a
 * pure function.
 */
@Singleton
class OsmHmmMapMatcher @Inject constructor(
	private val osmWayDao: OsmWayDao,
	private val osmWayCellDao: OsmWayCellDao,
) {

	suspend fun match(observations: List<RoadObservation>): List<MatchedEdge> {
		if (observations.size < 2) return emptyList()

		val decodedWays = loadCandidateWays(observations)
		if (decodedWays.isEmpty()) return emptyList()

		val cellToWayIdx = buildCellIndex(decodedWays)
		val candidatesPerObs = observations.map { obs -> candidatesFor(obs, decodedWays, cellToWayIdx) }

		val edges = ArrayList<MatchedEdge>()
		var start = 0
		while (start < observations.lastIndex) {
			if (!isMatchableTransition(observations, candidatesPerObs, start)) {
				edges += unavailableEdge(start, start + 1, RoadMatchStatus.GAP)
				start++
				continue
			}
			var end = start + 1
			while (end < observations.lastIndex && isMatchableTransition(observations, candidatesPerObs, end)) {
				end++
			}
			edges.addAll(matchRun(observations, candidatesPerObs, decodedWays, start, end))
			start = end
		}
		return edges
	}

	private fun isMatchableTransition(
		observations: List<RoadObservation>,
		candidatesPerObs: List<List<RoadCandidate>>,
		fromIndex: Int,
	): Boolean = candidatesPerObs[fromIndex].isNotEmpty() &&
		candidatesPerObs[fromIndex + 1].isNotEmpty() &&
		!isGap(observations[fromIndex], observations[fromIndex + 1])

	// region candidate loading

	private suspend fun loadCandidateWays(observations: List<RoadObservation>): List<DecodedWay> {
		val cellKeys = LinkedHashSet<Long>()
		for (obs in observations) {
			for (cell in candidateCellsFor(obs) ?: LongArray(0)) {
				cellKeys.add(cell)
			}
		}

		val wayIds = LinkedHashSet<Long>()
		cellKeys.chunked(SQLITE_CHUNK).forEach { chunk ->
			wayIds.addAll(osmWayCellDao.findWayIdsInCells(chunk))
		}
		if (wayIds.isEmpty()) return emptyList()

		val decoded = ArrayList<DecodedWay>(wayIds.size)
		wayIds.chunked(SQLITE_CHUNK).forEach { chunk ->
			for (entity in osmWayDao.findByIds(chunk)) {
				decodeWay(entity)?.let { decoded.add(it) }
			}
		}
		return decoded
	}

	private fun decodeWay(entity: OsmWayEntity): DecodedWay? {
		val (lats, lons) = PolylineE7Codec.decode(entity.geomPolylineE7)
		if (lats.size < 2) return null
		val coverage = OsmGridIndex.cellCoverageForBbox(
			minLatE7 = entity.bboxMinLatE7,
			maxLatE7 = entity.bboxMaxLatE7,
			startLonE7 = entity.bboxMinLonE7,
			endLonE7 = entity.bboxMaxLonE7,
		)
		val cells = (coverage as? OsmCellCoverage.Available)?.cellKeys ?: return null
		return DecodedWay(
			osmWayId = entity.id,
			importId = entity.importId,
			latsE7 = lats,
			lonsE7 = lons,
			cumArcLenM = OsmGeometry.cumulativeArcLengthM(lats, lons),
			maxspeedKmh = entity.maxspeedKmh,
			limitProvenance = when {
				entity.maxspeedKmh <= 0 -> RoadLimitProvenance.UNSUPPORTED
				entity.maxspeedExplicit == 1 -> RoadLimitProvenance.EXPLICIT_OSM_TAG
				else -> RoadLimitProvenance.ROAD_CLASS_HEURISTIC
			},
			cells = cells,
		)
	}

	private fun buildCellIndex(decodedWays: List<DecodedWay>): Map<Long, MutableList<Int>> {
		val map = HashMap<Long, MutableList<Int>>()
		decodedWays.forEachIndexed { idx, way ->
			for (cell in way.cells) {
				map.getOrPut(cell) { ArrayList() }.add(idx)
			}
		}
		return map
	}

	private fun candidatesFor(
		obs: RoadObservation,
		decodedWays: List<DecodedWay>,
		cellToWayIdx: Map<Long, MutableList<Int>>,
	): List<RoadCandidate> {
		val wayIndices = LinkedHashSet<Int>()
		for (cell in candidateCellsFor(obs) ?: LongArray(0)) {
			cellToWayIdx[cell]?.let { wayIndices.addAll(it) }
		}
		if (wayIndices.isEmpty()) return emptyList()

		val candidates = ArrayList<RoadCandidate>()
		for (wi in wayIndices) {
			val way = decodedWays[wi]
			val proj = OsmGeometry.projectToPolyline(
				way.latsE7, way.lonsE7, way.cumArcLenM, obs.latE7, obs.lonE7,
			) ?: continue
			if (proj.distanceM <= SNAP_RADIUS_M) {
				candidates.add(RoadCandidate(wi, proj))
			}
		}
		candidates.sortBy { it.proj.distanceM }
		return if (candidates.size > MAX_CANDIDATES) candidates.subList(0, MAX_CANDIDATES) else candidates
	}

	/**
	 * Uses the same conservative metre-radius coverage as the other OSM
	 * consumers. Invalid coordinates and polar/full coverage abstain rather than
	 * throwing or guessing from a truncated fixed neighborhood.
	 */
	private fun candidateCellsFor(observation: RoadObservation): LongArray? {
		val coordinate = CheckedCoordinateE7.fromE7OrNull(
			latitudeE7 = observation.latE7.toLong(),
			longitudeE7 = observation.lonE7.toLong(),
		) ?: return null
		return (OsmGridIndex.cellCoverageForRadius(coordinate, SNAP_RADIUS_M) as? OsmCellCoverage.Available)
			?.cellKeys
	}

	// endregion

	// region viterbi per run

	private fun matchRun(
		observations: List<RoadObservation>,
		candidatesPerObs: List<List<RoadCandidate>>,
		decodedWays: List<DecodedWay>,
		start: Int,
		end: Int,
	): List<MatchedEdge> {
		val length = end - start + 1
		val counts = IntArray(length) { candidatesPerObs[start + it].size }

		val chosen = ViterbiMatcher.decode(
			candidateCounts = counts,
			emissionLog = { localI, j ->
				val obs = observations[start + localI]
				val cand = candidatesPerObs[start + localI][j]
				val sigma = obs.accuracyM.toDouble().coerceIn(MIN_SIGMA_M, MAX_SIGMA_M)
				val z = cand.proj.distanceM / sigma
				-0.5 * z * z
			},
			transitionLog = { localI, k, j ->
				val prevObs = observations[start + localI - 1]
				val curObs = observations[start + localI]
				val prev = candidatesPerObs[start + localI - 1][k]
				val cur = candidatesPerObs[start + localI][j]
				transitionLog(prevObs, curObs, prev, cur)
			},
		)

		if (chosen.isEmpty()) {
			return (1 until length).map { localI ->
				unavailableEdge(start + localI - 1, start + localI, RoadMatchStatus.NO_PATH)
			}
		}

		val edges = ArrayList<MatchedEdge>(length - 1)
		for (localI in 1 until length) {
			val prev = candidatesPerObs[start + localI - 1][chosen[localI - 1]]
			val cur = candidatesPerObs[start + localI][chosen[localI]]
			val fromIndex = start + localI - 1
			val toIndex = start + localI
			val built = buildEdgePath(prev, cur, decodedWays)
			if (built == null) {
				edges += unavailableEdge(fromIndex, toIndex, RoadMatchStatus.NO_PATH)
			} else {
				edges += MatchedEdge(
					fromIndex = fromIndex,
					toIndex = toIndex,
					path = built.path,
					maxspeedKmh = built.way.maxspeedKmh,
					limitProvenance = built.way.limitProvenance,
					importId = built.way.importId,
					osmWayId = built.way.osmWayId,
				)
			}
		}
		return edges
	}

	private fun unavailableEdge(
		fromIndex: Int,
		toIndex: Int,
		status: RoadMatchStatus,
	): MatchedEdge = MatchedEdge(
		fromIndex = fromIndex,
		toIndex = toIndex,
		path = emptyList(),
		matchStatus = status,
	)

	private fun transitionLog(
		prevObs: RoadObservation,
		curObs: RoadObservation,
		prev: RoadCandidate,
		cur: RoadCandidate,
	): Double {
		val gcGps = OsmGeometry.distanceM(prevObs.latE7, prevObs.lonE7, curObs.latE7, curObs.lonE7)
		val sameWay = prev.wayIndex == cur.wayIndex
		val route = if (sameWay) {
			abs(cur.proj.arcLengthM - prev.proj.arcLengthM)
		} else {
			OsmGeometry.distanceM(
				prev.proj.snappedLatE7, prev.proj.snappedLonE7,
				cur.proj.snappedLatE7, cur.proj.snappedLonE7,
			)
		}
		var logProb = -abs(gcGps - route) / BETA_M
		if (!sameWay) logProb -= SWITCH_PENALTY
		return logProb
	}

	// endregion

	// region edge geometry

	private fun buildEdgePath(
		prev: RoadCandidate,
		cur: RoadCandidate,
		decodedWays: List<DecodedWay>,
	): BuiltEdge? {
		return if (prev.wayIndex == cur.wayIndex) {
			val way = decodedWays[prev.wayIndex]
			val (lats, lons) = OsmGeometry.slicePolyline(
				way.latsE7, way.lonsE7, way.cumArcLenM,
				prev.proj.arcLengthM, cur.proj.arcLengthM,
				prev.proj.snappedLatE7, prev.proj.snappedLonE7,
				cur.proj.snappedLatE7, cur.proj.snappedLonE7,
			)
			val points = dedupConsecutive(lats, lons)
			if (points.size >= 2) BuiltEdge(points, way) else null
		} else {
			val gap = OsmGeometry.distanceM(
				prev.proj.snappedLatE7, prev.proj.snappedLonE7,
				cur.proj.snappedLatE7, cur.proj.snappedLonE7,
			)
			if (gap > JUNCTION_TOLERANCE_M) {
				null
			} else {
				val points = dedupConsecutive(
					intArrayOf(prev.proj.snappedLatE7, cur.proj.snappedLatE7),
					intArrayOf(prev.proj.snappedLonE7, cur.proj.snappedLonE7),
				)
				if (points.size >= 2) BuiltEdge(points, decodedWays[prev.wayIndex]) else null
			}
		}
	}

	private fun dedupConsecutive(latsE7: IntArray, lonsE7: IntArray): List<RoadPoint> {
		val out = ArrayList<RoadPoint>(latsE7.size)
		for (i in latsE7.indices) {
			val p = RoadPoint(latsE7[i], lonsE7[i])
			if (out.isEmpty() || out.last() != p) out.add(p)
		}
		return out
	}

	// endregion

	private fun isGap(a: RoadObservation, b: RoadObservation): Boolean {
		if (b.timeMs - a.timeMs > MAX_GAP_MS) return true
		return OsmGeometry.distanceM(a.latE7, a.lonE7, b.latE7, b.lonE7) > MAX_GAP_M
	}

	private class DecodedWay(
		val osmWayId: Long,
		val importId: Long,
		val latsE7: IntArray,
		val lonsE7: IntArray,
		val cumArcLenM: DoubleArray,
		val maxspeedKmh: Int,
		val limitProvenance: RoadLimitProvenance,
		val cells: LongArray,
	)

	private data class BuiltEdge(
		val path: List<RoadPoint>,
		val way: DecodedWay,
	)

	private class RoadCandidate(
		val wayIndex: Int,
		val proj: OsmGeometry.Projection,
	)

	companion object {
		/** Max perpendicular snap distance for a candidate, in metres. */
		const val SNAP_RADIUS_M: Double = 50.0

		/** Keep at most this many nearest candidates per observation. */
		private const val MAX_CANDIDATES: Int = 6

		/** Emission Gaussian sigma clamp (GPS noise), in metres. */
		private const val MIN_SIGMA_M: Double = 4.0
		private const val MAX_SIGMA_M: Double = 30.0

		/** Transition scale for |gcDistance - routeDistance|, in metres. */
		private const val BETA_M: Double = 8.0

		/** Log-penalty applied when a transition switches to a different way (hysteresis). */
		private const val SWITCH_PENALTY: Double = 0.6

		/** Max distance between two snapped points to bridge a cross-way turn with a chord. */
		private const val JUNCTION_TOLERANCE_M: Double = 30.0

		/** Split into separate trips when consecutive samples are farther apart than this. */
		private const val MAX_GAP_MS: Long = 60_000L
		private const val MAX_GAP_M: Double = 200.0

		/** SQLite has a ~999 bound-parameter limit; stay safely under it. */
		private const val SQLITE_CHUNK: Int = 900
	}
}
