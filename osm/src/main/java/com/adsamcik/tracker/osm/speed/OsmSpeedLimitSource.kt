package com.adsamcik.tracker.osm.speed

import com.adsamcik.tracker.osm.io.OsmGridIndex
import com.adsamcik.tracker.osm.io.PolylineE7Codec
import com.adsamcik.tracker.shared.base.database.dao.OsmWayCellDao
import com.adsamcik.tracker.shared.base.database.dao.OsmWayDao
import com.adsamcik.tracker.shared.base.database.data.OsmWayEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 2a OSM-backed road speed-limit lookup. Maps a GPS sample to the
 * nearest driveable [OsmWayEntity] within [SNAP_THRESHOLD_M] metres and
 * returns its `maxspeed_kmh` converted to m/s.
 *
 * Pipeline per call:
 *  1. Compute the 3x3 grid neighborhood ([OsmGridIndex.cellAnd8Neighbors]).
 *  2. Look up candidate way ids via the `osm_way_cell` index.
 *  3. Load those ways' bbox + polyline blob and compute the minimum
 *     perpendicular distance from the sample to any segment.
 *  4. Keep the nearest way under the snap threshold and return its limit.
 *
 * A small LRU cache (capacity [CACHE_SIZE]) memoizes the result keyed on a
 * ~5 m coarse grid, which is sufficient for the streaming case where
 * consecutive GPS samples are typically only metres apart.
 *
 * NOT a [com.adsamcik.tracker.stats.api.speed.SpeedLimitSource] itself —
 * `:stats-data` owns the dispatcher and decides when to fall back to the
 * configured fixed baseline. Keeping this class as a plain @Singleton lookup
 * avoids tying `:osm`'s lifecycle to `:stats-data`'s preference repository.
 */
@Singleton
class OsmSpeedLimitSource @Inject constructor(
	private val osmWayDao: OsmWayDao,
	private val osmWayCellDao: OsmWayCellDao,
) {

	private val cache = LruCache<Long, CachedLookup>(CACHE_SIZE)
	private val mutex = Mutex()

	/**
	 * Returns the resolved speed limit in metres per second for the OSM way
	 * nearest to `(latE7, lonE7)` within [SNAP_THRESHOLD_M] metres, or `null`
	 * if no driveable way is close enough.
	 */
	suspend fun findRoadLimitMps(latE7: Int, lonE7: Int): Double? {
		val cacheKey = cacheKey(latE7, lonE7)
		val cached = mutex.withLock { cache.get(cacheKey) }
		if (cached != null) return cached.limitMps

		val cellKeys = OsmGridIndex.cellAnd8Neighbors(latE7, lonE7).toList()
		val candidateIds = osmWayCellDao.findWayIdsInCells(cellKeys)
		if (candidateIds.isEmpty()) {
			mutex.withLock { cache.put(cacheKey, CachedLookup(null)) }
			return null
		}
		val ways = osmWayDao.findByIds(candidateIds.toSet())
		val nearest = pickNearest(ways, latE7, lonE7)
		val limitMps = nearest?.maxspeedKmh?.let { kmhToMps(it) }
		mutex.withLock { cache.put(cacheKey, CachedLookup(limitMps)) }
		return limitMps
	}

	/** Visible to tests so the cache can be cleared between scenarios. */
	internal suspend fun clearCache() {
		mutex.withLock { cache.evictAll() }
	}

	private fun pickNearest(
		ways: List<OsmWayEntity>,
		latE7: Int,
		lonE7: Int,
	): OsmWayEntity? {
		var bestDist = Double.MAX_VALUE
		var best: OsmWayEntity? = null
		val cosLat = cos(degrees(latE7) * Math.PI / 180.0)
		val thresholdSq = SNAP_THRESHOLD_M * SNAP_THRESHOLD_M
		for (way in ways) {
			if (!bboxWithin(way, latE7, lonE7, SNAP_THRESHOLD_E7)) continue
			val dist = nearestSegmentDistanceMetersSq(way, latE7, lonE7, cosLat)
			if (dist < bestDist) {
				bestDist = dist
				best = way
			}
		}
		return if (best != null && bestDist <= thresholdSq) best else null
	}

	private fun bboxWithin(
		way: OsmWayEntity,
		latE7: Int,
		lonE7: Int,
		marginE7: Int,
	): Boolean {
		return latE7 >= way.bboxMinLatE7 - marginE7 &&
			latE7 <= way.bboxMaxLatE7 + marginE7 &&
			lonE7 >= way.bboxMinLonE7 - marginE7 &&
			lonE7 <= way.bboxMaxLonE7 + marginE7
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
				lats[i], lons[i],
				lats[i + 1], lons[i + 1],
				latE7, lonE7,
				cosLat,
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
		// Convert to local planar metres relative to A using an equirectangular
		// approximation. Valid for the sub-100 m distances we care about here.
		val bx = (bLonE7 - aLonE7).toDouble() * METRES_PER_E7_DEG * cosLat
		val by = (bLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG
		val px = (pLonE7 - aLonE7).toDouble() * METRES_PER_E7_DEG * cosLat
		val py = (pLatE7 - aLatE7).toDouble() * METRES_PER_E7_DEG

		val lenSq = bx * bx + by * by
		if (lenSq < EPSILON) {
			return px * px + py * py
		}
		val t = max(0.0, min(1.0, (px * bx + py * by) / lenSq))
		val cx = t * bx
		val cy = t * by
		val dx = px - cx
		val dy = py - cy
		return dx * dx + dy * dy
	}

	private data class CachedLookup(val limitMps: Double?)

	private class LruCache<K, V>(private val capacity: Int) {
		private val map = LinkedHashMap<K, V>(16, 0.75f, true)

		fun get(key: K): V? = map[key]

		fun put(key: K, value: V) {
			map[key] = value
			if (map.size > capacity) {
				val it = map.entries.iterator()
				if (it.hasNext()) {
					it.next()
					it.remove()
				}
			}
		}

		fun evictAll() = map.clear()
	}

	companion object {
		const val SNAP_THRESHOLD_M: Double = 50.0
		private const val CACHE_SIZE: Int = 64

		/** Cache key bucket size — ~5 m at the equator. */
		private const val CACHE_BUCKET_E7: Int = 500

		/** Snap threshold in E7 lat-degrees (~1.1 cm per E7 sec lat; 50 m ≈ 4_500 E7). */
		private const val SNAP_THRESHOLD_E7: Int = 5_000

		/** Metres per 1e-7 degree of latitude (great-circle, ~constant at 1.1132e-2 m). */
		private const val METRES_PER_E7_DEG: Double = 0.01112
		private const val EPSILON: Double = 1e-9

		internal fun cacheKey(latE7: Int, lonE7: Int): Long {
			val lat = (latE7 / CACHE_BUCKET_E7).toLong()
			val lon = (lonE7 / CACHE_BUCKET_E7).toLong()
			return (lat shl 32) or (lon and 0xFFFFFFFFL)
		}

		internal fun kmhToMps(kmh: Int): Double = kmh.toDouble() * (1000.0 / 3600.0)

		internal fun degrees(e7: Int): Double = e7.toDouble() / 1e7
	}
}
