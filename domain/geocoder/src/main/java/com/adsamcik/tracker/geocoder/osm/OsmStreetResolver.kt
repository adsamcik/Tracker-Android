package com.adsamcik.tracker.geocoder.osm

import com.adsamcik.tracker.osm.io.OsmCellCoverage
import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import com.adsamcik.tracker.shared.model.geo.CheckedCoordinateE7
import com.adsamcik.tracker.shared.model.geo.CircularLongitude
import com.adsamcik.tracker.shared.model.geo.CircularLongitudeInterval
import com.adsamcik.tracker.shared.model.geo.ConservativeRadiusBounds
import com.adsamcik.tracker.shared.model.geo.GeoCoordinates
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Street-level reverse-geocoding helper backed by the user's imported OSM road
 * data. It returns the nearest named driveable way within [SNAP_THRESHOLD_M]
 * metres, or abstains when bounded candidate coverage cannot be formed.
 */
@Singleton
class OsmStreetResolver @Inject constructor(
	private val osmWayDao: OsmWayDao,
	private val osmWayCellDao: OsmWayCellDao,
) {

	/** Nearest named road to ([latE7], [lonE7]) within [SNAP_THRESHOLD_M] m, or null. */
	suspend fun nearestRoadName(latE7: Int, lonE7: Int): String? {
		val coordinate = CheckedCoordinateE7.fromE7OrNull(latE7.toLong(), lonE7.toLong()) ?: return null
		val bounds = ConservativeRadiusBounds.around(coordinate, SNAP_THRESHOLD_M)
		val coverage = OsmGridIndex.cellCoverageForBounds(
			minLatE7 = bounds.minLatitudeE7,
			maxLatE7 = bounds.maxLatitudeE7,
			longitude = bounds.longitude,
		)
		val cellKeys = (coverage as? OsmCellCoverage.Available)?.cellKeys
			?: return null // Do not silently truncate a polar/full-longitude search.
		val candidateIds = findWayIdsInCells(cellKeys)
		if (candidateIds.isEmpty()) return null
		val ways = findWaysByIds(candidateIds)
		val cosLat = cos(coordinate.latitudeDegrees * Math.PI / 180.0)

		var bestDistSq = SNAP_THRESHOLD_M * SNAP_THRESHOLD_M
		var bestName: String? = null
		var bestWayId = Long.MAX_VALUE
		for (way in ways) {
			val name = way.name?.takeIf { it.isNotBlank() } ?: continue
			if (!bboxIntersects(way, bounds)) continue
			val distanceSq = nearestSegmentDistanceMetersSq(
				way,
				coordinate.latitudeE7,
				coordinate.longitudeE7,
				cosLat,
			)
			if (
				isWithinSnapThreshold(distanceSq) &&
				(
					distanceSq < bestDistSq - DISTANCE_TIE_EPSILON_SQ ||
					// DAO row order is unspecified, so equal-distance roads use the lowest stable OSM id.
					(abs(distanceSq - bestDistSq) <= DISTANCE_TIE_EPSILON_SQ && way.id < bestWayId)
				)
			) {
				bestDistSq = distanceSq
				bestName = name
				bestWayId = way.id
			}
		}
		return bestName
	}

	private suspend fun findWayIdsInCells(cellKeys: LongArray): Set<Long> = buildSet {
		cellKeys.asList().chunked(OsmGridIndex.MAX_SQL_IN_BINDINGS).forEach { cellChunk ->
			addAll(osmWayCellDao.findWayIdsInCells(cellChunk))
		}
	}

	private suspend fun findWaysByIds(ids: Set<Long>): List<OsmWayEntity> = buildList {
		ids.chunked(OsmGridIndex.MAX_SQL_IN_BINDINGS).forEach { idChunk ->
			addAll(osmWayDao.findByIds(idChunk))
		}
	}

	private fun bboxIntersects(way: OsmWayEntity, bounds: ConservativeRadiusBounds): Boolean {
		if (
			way.bboxMinLatE7 > way.bboxMaxLatE7 ||
			way.bboxMinLatE7.toLong() !in GeoCoordinates.MIN_LATITUDE_E7..GeoCoordinates.MAX_LATITUDE_E7 ||
			way.bboxMaxLatE7.toLong() !in GeoCoordinates.MIN_LATITUDE_E7..GeoCoordinates.MAX_LATITUDE_E7 ||
			way.bboxMinLonE7.toLong() !in GeoCoordinates.MIN_LONGITUDE_E7..GeoCoordinates.MAX_LONGITUDE_INPUT_E7 ||
			way.bboxMaxLonE7.toLong() !in GeoCoordinates.MIN_LONGITUDE_E7..GeoCoordinates.MAX_LONGITUDE_INPUT_E7
		) {
			return false
		}
		if (way.bboxMaxLatE7 < bounds.minLatitudeE7 || way.bboxMinLatE7 > bounds.maxLatitudeE7) return false
		return CircularLongitudeInterval.fromDirectedEndpoints(
			way.bboxMinLonE7.toLong(),
			way.bboxMaxLonE7.toLong(),
		).intersects(bounds.longitude)
	}

	private fun nearestSegmentDistanceMetersSq(
		way: OsmWayEntity,
		latE7: Int,
		lonE7: Int,
		cosLat: Double,
	): Double {
		val (lats, lons) = PolylineE7Codec.decode(way.geomPolylineE7)
		if (lats.size < 2) return Double.MAX_VALUE
		var best = Double.MAX_VALUE
		for (index in 0 until lats.size - 1) {
			val distanceSq = perpendicularDistMetersSq(
				lats[index],
				lons[index],
				lats[index + 1],
				lons[index + 1],
				latE7,
				lonE7,
				cosLat,
			)
			if (distanceSq < best) best = distanceSq
		}
		return best
	}

	private fun perpendicularDistMetersSq(
		aLatE7: Int,
		aLonE7: Int,
		bLatE7: Int,
		bLonE7: Int,
		pLatE7: Int,
		pLonE7: Int,
		cosLat: Double,
	): Double {
		val bx = CircularLongitude.shortestDeltaE7(aLonE7.toLong(), bLonE7.toLong()).toDouble() *
			METRES_PER_E7_DEG * cosLat
		val by = (bLatE7.toLong() - aLatE7.toLong()).toDouble() * METRES_PER_E7_DEG
		val px = CircularLongitude.shortestDeltaE7(aLonE7.toLong(), pLonE7.toLong()).toDouble() *
			METRES_PER_E7_DEG * cosLat
		val py = (pLatE7.toLong() - aLatE7.toLong()).toDouble() * METRES_PER_E7_DEG
		val lengthSq = bx * bx + by * by
		if (lengthSq < EPSILON) return px * px + py * py
		val t = max(0.0, min(1.0, (px * bx + py * by) / lengthSq))
		val dx = px - t * bx
		val dy = py - t * by
		return dx * dx + dy * dy
	}

	companion object {
		private const val SNAP_THRESHOLD_M = 60.0
		private const val METRES_PER_E7_DEG = 0.01112
		private const val EPSILON = 1e-9
		private const val DISTANCE_TIE_EPSILON_SQ = 1e-6

		/** “Within 60 m” is inclusive, so a squared distance of exactly 3,600 wins. */
		internal fun isWithinSnapThreshold(distanceSq: Double): Boolean =
			distanceSq <= SNAP_THRESHOLD_M * SNAP_THRESHOLD_M
	}
}
