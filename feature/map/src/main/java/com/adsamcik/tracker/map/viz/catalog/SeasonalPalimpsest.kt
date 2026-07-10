package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.viz.AggContext
import com.adsamcik.tracker.map.viz.Aggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.fill
import com.adsamcik.tracker.map.viz.mapViz
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao

/**
 * **Seasonal Palimpsest** — the first new-data visualization on the map-visualization engine. It maps
 * the exploration fog-of-war's [season bitmask][com.adsamcik.tracker.shared.base.database.data.ExplorationCellEntity.seasonBitmask]:
 * every explored S2 cell is tinted by the primary (earliest-in-year) season it was visited in, so the
 * map becomes a patchwork of seasonal memory — the trail you only ever walk in summer glows green, the
 * winter-only street icy blue.
 *
 * It proves the engine's extensibility axes with **no engine changes**: a shared [explorationCellSource]
 * backed by a DAO (not `GeoRepository`), the shared [ExplorationCellFeature] flowing through the
 * generic pipeline, and a season-specific [Aggregator] — reusing the [SpatialData.FillCells] shape and
 * [fill][com.adsamcik.tracker.map.viz.fill] encoder. This is the whole viz: one declarative factory.
 */

/** Season bits (matches ExplorationCellDao.updateVisit): spring=1, summer=2, autumn=4, winter=8. */
private const val SEASON_MASK = 0b1111

/**
 * **Aggregator**: each cell -> a lat/lon square tile coloured by its primary season. Cells with no
 * recorded season are skipped, since a *seasonal* map has nothing to say about them.
 */
class SeasonTileAggregator : Aggregator<ExplorationCellFeature, SpatialData.FillCells> {
	override fun aggregate(
		features: List<ExplorationCellFeature>,
		ctx: AggContext,
	): SpatialData.FillCells {
		val tiles = features.mapNotNull { cell ->
			val seasonScalar = primarySeasonScalar(cell.seasonBitmask) ?: return@mapNotNull null
			cell.toTile(seasonScalar)
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

