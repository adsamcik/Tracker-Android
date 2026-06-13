package com.adsamcik.tracker.geocoder.osm

import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Street-level reverse-geocoding helper backed by the user's imported OSM road
 * data. Returns the name of the nearest *named* driveable way within
 * [SNAP_THRESHOLD_M] metres, or `null` when no OSM data is imported for the area
 * or the nearest road is unnamed.
 *
 * Reuses the same spatial grid + nearest-segment math as
 * `OsmSpeedLimitSource`, but resolves the way's `name` rather than its speed limit.
 */
@Singleton
class OsmStreetResolver @Inject constructor(
    private val osmWayDao: OsmWayDao,
    private val osmWayCellDao: OsmWayCellDao,
) {

    /** Nearest named road to ([latE7], [lonE7]) within [SNAP_THRESHOLD_M] m, or null. */
    suspend fun nearestRoadName(latE7: Int, lonE7: Int): String? {
        val cellKeys = OsmGridIndex.cellAnd8Neighbors(latE7, lonE7).toList()
        val candidateIds = osmWayCellDao.findWayIdsInCells(cellKeys)
        if (candidateIds.isEmpty()) return null
        val ways = osmWayDao.findByIds(candidateIds.toSet())

        var bestDistSq = SNAP_THRESHOLD_M * SNAP_THRESHOLD_M
        var bestName: String? = null
        val cosLat = cos(latE7 / 1e7 * Math.PI / 180.0)
        for (way in ways) {
            val name = way.name?.takeIf { it.isNotBlank() } ?: continue
            if (!bboxWithin(way, latE7, lonE7)) continue
            val d = nearestSegmentDistanceMetersSq(way, latE7, lonE7, cosLat)
            if (d < bestDistSq) {
                bestDistSq = d
                bestName = name
            }
        }
        return bestName
    }

    private fun bboxWithin(way: OsmWayEntity, latE7: Int, lonE7: Int): Boolean =
        latE7 >= way.bboxMinLatE7 - SNAP_THRESHOLD_E7 &&
            latE7 <= way.bboxMaxLatE7 + SNAP_THRESHOLD_E7 &&
            lonE7 >= way.bboxMinLonE7 - SNAP_THRESHOLD_E7 &&
            lonE7 <= way.bboxMaxLonE7 + SNAP_THRESHOLD_E7

    private fun nearestSegmentDistanceMetersSq(
        way: OsmWayEntity,
        latE7: Int,
        lonE7: Int,
        cosLat: Double,
    ): Double {
        val (lats, lons) = PolylineE7Codec.decode(way.geomPolylineE7)
        if (lats.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until lats.size - 1) {
            val d = perpendicularDistMetersSq(
                lats[i], lons[i], lats[i + 1], lons[i + 1], latE7, lonE7, cosLat,
            )
            if (d < best) best = d
        }
        return best
    }

    private fun perpendicularDistMetersSq(
        aLatE7: Int, aLonE7: Int,
        bLatE7: Int, bLonE7: Int,
        pLatE7: Int, pLonE7: Int,
        cosLat: Double,
    ): Double {
        val bx = (bLonE7 - aLonE7).toDouble() * METRES_PER_E7_DEG * cosLat
        val by = (bLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
        val px = (pLonE7 - aLonE7).toDouble() * METRES_PER_E7_DEG * cosLat
        val py = (pLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
        val lenSq = bx * bx + by * by
        if (lenSq < EPSILON) return px * px + py * py
        val t = max(0.0, min(1.0, (px * bx + py * by) / lenSq))
        val dx = px - t * bx
        val dy = py - t * by
        return dx * dx + dy * dy
    }

    private companion object {
        const val SNAP_THRESHOLD_M = 60.0
        const val SNAP_THRESHOLD_E7 = 6_000
        const val METRES_PER_E7_DEG = 0.01112
        const val EPSILON = 1e-9
    }
}
