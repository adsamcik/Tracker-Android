package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GridTile
import com.adsamcik.tracker.map.viz.VizSource
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Shared building blocks for exploration-fog visualizations (Seasonal Palimpsest, Fog-of-Wonder,
 * First Contact). All three read the same discovered-S2-cell store and lower each cell to a square
 * tile via the engine's [SpatialData.FillCells][com.adsamcik.tracker.map.viz.SpatialData.FillCells]
 * shape — they differ only in the [Aggregator][com.adsamcik.tracker.map.viz.Aggregator] that decides
 * a cell's colour weight (season vs quality vs recency). Keeping the source + tile geometry here means
 * a new fog visualization is just a new aggregator + ramp, with no duplicated querying or geometry.
 */

/** An exploration S2 cell reduced to everything the fog visualizations need. */
data class ExplorationCellFeature(
	val lat: Double,
	val lon: Double,
	val level: Int,
	val seasonBitmask: Int,
	/** Discovery quality tier: 0=passed-through … 4=thoroughly-explored. */
	val quality: Int,
	/** Epoch millis the cell was first discovered (drives First Contact recency colouring). */
	val firstDiscoveredAt: Long,
	val visitCount: Int,
)

/** S2 level used by the exploration system (~0.3–0.8 km² cells). */
internal const val EXPLORATION_LEVEL = 14

/** Safety cap on rendered cells per viewport refresh. */
internal const val MAX_EXPLORATION_CELLS = 20_000

private const val E7 = 1e7
private const val EARTH_AREA_KM2 = 510_072_000.0
private const val KM_PER_DEG_LAT = 111.32

/**
 * **Source**: discovered exploration cells whose centre falls in the viewport, at [level]. Reads the
 * local Room DAO only — no network. Exploration is cumulative, so the map's date filter is
 * intentionally ignored for these layers.
 */
fun explorationCellSource(
	dao: ExplorationCellDao,
	level: Int = EXPLORATION_LEVEL,
): VizSource<ExplorationCellFeature> = VizSource { request ->
	val bounds = request.bounds
	val minLatE7 = ((bounds?.south ?: -90.0) * E7).toInt()
	val maxLatE7 = ((bounds?.north ?: 90.0) * E7).toInt()
	val minLonE7 = ((bounds?.west ?: -180.0) * E7).toInt()
	val maxLonE7 = ((bounds?.east ?: 180.0) * E7).toInt()
	dao.getCellsInBounds(level, minLatE7, maxLatE7, minLonE7, maxLonE7, MAX_EXPLORATION_CELLS)
		.map { cell ->
			ExplorationCellFeature(
				lat = cell.centerLatE7 / E7,
				lon = cell.centerLonE7 / E7,
				level = cell.level,
				seasonBitmask = cell.seasonBitmask,
				quality = cell.quality,
				firstDiscoveredAt = cell.firstDiscoveredAt,
				visitCount = cell.visitCount,
			)
		}
}

/**
 * Lowers an exploration cell to a [GridTile] (an approximation of the S2 cell footprint sized from
 * its level) carrying the given `[0, 1]` colour [weight]. The single geometry path every fog
 * aggregator shares — only the weight differs.
 */
internal fun ExplorationCellFeature.toTile(weight: Double): GridTile {
	val (halfLat, halfLon) = cellHalfSpanDeg(level, lat)
	return GridTile(
		west = lon - halfLon,
		south = lat - halfLat,
		east = lon + halfLon,
		north = lat + halfLat,
		weight = weight,
		count = visitCount,
	)
}

/** Approximate half-span (degrees) of an S2 cell at [level], corrected for longitude at [lat]. */
private fun cellHalfSpanDeg(level: Int, lat: Double): Pair<Double, Double> {
	val cellCount = 6.0 * 4.0.pow(level)
	val edgeKm = sqrt(EARTH_AREA_KM2 / cellCount)
	val halfLat = (edgeKm / 2.0) / KM_PER_DEG_LAT
	val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
	return halfLat to (halfLat / cosLat)
}
