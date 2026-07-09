package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GridTile
import com.adsamcik.tracker.map.viz.AggContext
import com.adsamcik.tracker.map.viz.Aggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.VizSource
import com.adsamcik.tracker.map.viz.fill
import com.adsamcik.tracker.map.viz.mapViz
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * **Seasonal Palimpsest** — the first new-data visualization on the map-visualization engine. It maps
 * the exploration fog-of-war's [season bitmask][com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity.seasonBitmask]:
 * every explored S2 cell is tinted by the primary (earliest-in-year) season it was visited in, so the
 * map becomes a patchwork of seasonal memory — the trail you only ever walk in summer glows green, the
 * winter-only street icy blue.
 *
 * It proves the engine's extensibility axes with **no engine changes**: a new [VizSource] backed by a
 * DAO (not `GeoRepository`), a new feature type ([ExplorationCellFeature]) flowing through the generic
 * pipeline, and a new [Aggregator] — reusing the existing [SpatialData.FillCells] shape and
 * [fill][com.adsamcik.tracker.map.viz.fill] encoder. This is the whole viz: one declarative factory.
 */

/** An exploration S2 cell reduced to what the seasonal overlay needs. */
data class ExplorationCellFeature(
	val lat: Double,
	val lon: Double,
	val level: Int,
	val seasonBitmask: Int,
	val quality: Int,
)

/** S2 level used by the exploration system (~0.3–0.8 km² cells). */
private const val EXPLORATION_LEVEL = 14

/** Safety cap on rendered cells per viewport refresh. */
private const val MAX_SEASONAL_CELLS = 20_000

private const val E7 = 1e7
private const val EARTH_AREA_KM2 = 510_072_000.0
private const val KM_PER_DEG_LAT = 111.32

/** Season bits (matches ExplorationCellDao.updateVisit): spring=1, summer=2, autumn=4, winter=8. */
private const val SEASON_MASK = 0b1111

/**
 * **Source**: discovered exploration cells whose centre falls in the viewport, at [level]. Reads the
 * local Room DAO only — no network. Exploration is cumulative, so the map's date filter is
 * intentionally ignored for this layer.
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
	dao.getCellsInBounds(level, minLatE7, maxLatE7, minLonE7, maxLonE7, MAX_SEASONAL_CELLS)
		.map { cell ->
			ExplorationCellFeature(
				lat = cell.centerLatE7 / E7,
				lon = cell.centerLonE7 / E7,
				level = cell.level,
				seasonBitmask = cell.seasonBitmask,
				quality = cell.quality,
			)
		}
}

/**
 * **Aggregator**: each cell -> a lat/lon square tile (an approximation of the S2 cell footprint sized
 * from its level) coloured by its primary season. Cells with no recorded season are skipped, since a
 * *seasonal* map has nothing to say about them.
 */
class SeasonTileAggregator : Aggregator<ExplorationCellFeature, SpatialData.FillCells> {
	override fun aggregate(
		features: List<ExplorationCellFeature>,
		ctx: AggContext,
	): SpatialData.FillCells {
		val tiles = features.mapNotNull { cell ->
			val seasonScalar = primarySeasonScalar(cell.seasonBitmask) ?: return@mapNotNull null
			val (halfLat, halfLon) = cellHalfSpanDeg(cell.level, cell.lat)
			GridTile(
				west = cell.lon - halfLon,
				south = cell.lat - halfLat,
				east = cell.lon + halfLon,
				north = cell.lat + halfLat,
				weight = seasonScalar,
				count = 1,
			)
		}
		return SpatialData.FillCells(tiles)
	}
}

/**
 * Primary-season scalar in `{0, 1/3, 2/3, 1}` from the lowest set season bit (spring→…→winter), or
 * null when no season is recorded. The four discrete values land exactly on the [SEASONAL_RAMP] stops,
 * so each tile gets a clean seasonal hue with no muddy interpolation.
 */
internal fun primarySeasonScalar(seasonBitmask: Int): Double? {
	val seasons = seasonBitmask and SEASON_MASK
	if (seasons == 0) return null
	val primaryIndex = Integer.numberOfTrailingZeros(seasons) // 0=spring … 3=winter
	return primaryIndex / 3.0
}

/** Approximate half-span (degrees) of an S2 cell at [level], corrected for longitude at [lat]. */
private fun cellHalfSpanDeg(level: Int, lat: Double): Pair<Double, Double> {
	val cellCount = 6.0 * 4.0.pow(level)
	val edgeKm = sqrt(EARTH_AREA_KM2 / cellCount)
	val halfLat = (edgeKm / 2.0) / KM_PER_DEG_LAT
	val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(1e-6)
	return halfLat to (halfLat / cosLat)
}

/** Spring green → summer gold → autumn amber → winter blue. Opaque: every tile is collected data. */
internal val SEASONAL_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0xFF4CAF50.toInt(),
	0.3333f to 0xFFFFC107.toInt(),
	0.6667f to 0xFFFF7043.toInt(),
	1.0f to 0xFF42A5F5.toInt(),
)

/** Seasonal exploration overlay: explored cells tinted by their primary season. */
fun seasonalPalimpsest(dao: ExplorationCellDao): VizPipeline<ExplorationCellFeature, SpatialData.FillCells> =
	mapViz("seasonal_palimpsest")
		.source(explorationCellSource(dao))
		.aggregate(SeasonTileAggregator())
		.fill(colorStops = SEASONAL_RAMP, opacity = 0.55f, outlineColorArgb = 0x22000000)
