package com.adsamcik.tracker.geocoder.osm

import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ceil
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
        val cosLat = cos(latE7 / E7_PER_DEGREE * Math.PI / 180.0).coerceAtLeast(0.0)
        val cellKeys = OsmGridIndex.cellAndNeighbors(
            latE7 = latE7,
            lonE7 = lonE7,
            lonRadius = longitudeCellRadius(cosLat),
        ).toList()
        val candidateIds = findWayIdsInCells(cellKeys)
        if (candidateIds.isEmpty()) return null
        val ways = findWaysByIds(candidateIds)

        var bestDistSq = SNAP_THRESHOLD_M * SNAP_THRESHOLD_M
        var bestName: String? = null
        var bestWayId = Long.MAX_VALUE
        for (way in ways) {
            val name = way.name?.takeIf { it.isNotBlank() } ?: continue
            if (!bboxWithin(way, latE7, lonE7, cosLat)) continue
            val d = nearestSegmentDistanceMetersSq(way, latE7, lonE7, cosLat)
            if (
                isWithinSnapThreshold(d) &&
                (
                    d < bestDistSq - DISTANCE_TIE_EPSILON_SQ ||
                        // DAO row order is unspecified, so equal-distance roads use the lowest stable OSM id.
                        (abs(d - bestDistSq) <= DISTANCE_TIE_EPSILON_SQ && way.id < bestWayId)
                    )
            ) {
                bestDistSq = d
                bestName = name
                bestWayId = way.id
            }
        }
        return bestName
    }

    private suspend fun findWayIdsInCells(cellKeys: List<Long>): Set<Long> =
        buildSet {
            cellKeys.chunked(MAX_SQL_BIND_PARAMETERS).forEach { cellChunk ->
                addAll(osmWayCellDao.findWayIdsInCells(cellChunk))
            }
        }

    private suspend fun findWaysByIds(ids: Set<Long>): List<OsmWayEntity> =
        buildList {
            ids.chunked(MAX_SQL_BIND_PARAMETERS).forEach { idChunk ->
                addAll(osmWayDao.findByIds(idChunk))
            }
        }

    private fun bboxWithin(
        way: OsmWayEntity,
        latE7: Int,
        lonE7: Int,
        cosLat: Double,
    ): Boolean {
        if (
            latE7.toLong() < way.bboxMinLatE7.toLong() - LATITUDE_PADDING_E7 ||
            latE7.toLong() > way.bboxMaxLatE7.toLong() + LATITUDE_PADDING_E7
        ) {
            return false
        }
        return longitudeIsWithin(
            lonE7 = lonE7,
            minLonE7 = way.bboxMinLonE7,
            maxLonE7 = way.bboxMaxLonE7,
            paddingE7 = longitudePaddingE7(cosLat),
        )
    }

    private fun longitudeCellRadius(cosLat: Double): Int =
        min(
            HALF_LON_CELL_COUNT,
            max(
                1,
                ceil(
                    SNAP_THRESHOLD_M /
                        (OsmGridIndex.CELL_E7 * METRES_PER_E7_DEG * effectiveLongitudeCosine(cosLat)),
                ).toInt(),
            ),
        )

    /**
     * Uses the query latitude because one E7 longitude unit is
     * [METRES_PER_E7_DEG] * cos(latitude) metres. The cosine floor is the
     * value at which 60 m spans half the world; the resulting capped padding
     * searches every longitude at either pole instead of risking division by
     * zero or a too-small degree margin.
     */
    private fun longitudePaddingE7(cosLat: Double): Long =
        min(
            HALF_WORLD_E7,
            ceil(SNAP_THRESHOLD_M / (METRES_PER_E7_DEG * effectiveLongitudeCosine(cosLat))).toLong(),
        )

    private fun effectiveLongitudeCosine(cosLat: Double): Double =
        max(cosLat, MIN_LONGITUDE_COSINE)

    private fun longitudeIsWithin(
        lonE7: Int,
        minLonE7: Int,
        maxLonE7: Int,
        paddingE7: Long,
    ): Boolean {
        if (paddingE7 >= HALF_WORLD_E7) return true
        val span = positiveLongitudeDeltaE7(maxLonE7.toLong() - minLonE7.toLong())
        if (span + 2 * paddingE7 >= WORLD_E7) return true
        val start = normalizeLongitudeE7(minLonE7.toLong() - paddingE7)
        return positiveLongitudeDeltaE7(lonE7.toLong() - start) <= span + 2 * paddingE7
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
        val bx = signedLongitudeDeltaE7(bLonE7.toLong() - aLonE7.toLong()).toDouble() *
            METRES_PER_E7_DEG * cosLat
        val by = (bLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
        val px = signedLongitudeDeltaE7(pLonE7.toLong() - aLonE7.toLong()).toDouble() *
            METRES_PER_E7_DEG * cosLat
        val py = (pLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
        val lenSq = bx * bx + by * by
        if (lenSq < EPSILON) return px * px + py * py
        val t = max(0.0, min(1.0, (px * bx + py * by) / lenSq))
        val dx = px - t * bx
        val dy = py - t * by
        return dx * dx + dy * dy
    }

    private fun normalizeLongitudeE7(value: Long): Long =
        Math.floorMod(value + HALF_WORLD_E7, WORLD_E7) - HALF_WORLD_E7

    private fun positiveLongitudeDeltaE7(delta: Long): Long =
        Math.floorMod(delta, WORLD_E7)

    private fun signedLongitudeDeltaE7(delta: Long): Long =
        normalizeLongitudeE7(delta)

    companion object {
        private const val SNAP_THRESHOLD_M = 60.0
        private const val METRES_PER_E7_DEG = 0.01112
        private const val EPSILON = 1e-9
        private const val E7_PER_DEGREE = 1e7
        private const val WORLD_E7 = 3_600_000_000L
        private const val HALF_WORLD_E7 = WORLD_E7 / 2
        private const val HALF_LON_CELL_COUNT = 18_000
        private const val MAX_SQL_BIND_PARAMETERS = 900
        private const val DISTANCE_TIE_EPSILON_SQ = 1e-6
        private val LATITUDE_PADDING_E7 = ceil(SNAP_THRESHOLD_M / METRES_PER_E7_DEG).toLong()
        private val MIN_LONGITUDE_COSINE =
            SNAP_THRESHOLD_M / (METRES_PER_E7_DEG * HALF_WORLD_E7)

        /** “Within 60 m” is inclusive, so a squared distance of exactly 3,600 wins. */
        internal fun isWithinSnapThreshold(distanceSq: Double): Boolean =
            distanceSq <= SNAP_THRESHOLD_M * SNAP_THRESHOLD_M
    }
}
