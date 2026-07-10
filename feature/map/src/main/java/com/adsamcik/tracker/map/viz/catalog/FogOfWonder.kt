package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.viz.AggContext
import com.adsamcik.tracker.map.viz.Aggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.fill
import com.adsamcik.tracker.map.viz.mapViz
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao

/**
 * **Fog-of-Wonder** — the explored world emerging from the fog. Every S2 cell you've touched is
 * revealed, but *how strongly* depends on how thoroughly you got to know it: a place you merely passed
 * through is a faint haze, while somewhere you explored deeply burns a solid warm gold. Unexplored
 * cells simply aren't drawn — they stay fog. It's the classic "fog of war" reveal turned into a record
 * of curiosity.
 *
 * Reuses the shared [explorationCellSource] + [SpatialData.FillCells] shape + [fill] encoder with **no
 * engine changes** — the only new piece is a quality-driven aggregator. The reveal strength is baked
 * into each tile's colour *alpha* (via [FOG_RAMP]) so a single fill layer shows per-cell opacity.
 */

/** Discovery quality is a 0..4 tier; normalise to `[0, 1]` for the ramp. */
private const val MAX_QUALITY_TIER = 4.0

/**
 * **Aggregator**: each cell -> a square tile whose weight is its normalised discovery quality, so the
 * [FOG_RAMP]'s rising alpha reveals well-known places more strongly than barely-touched ones.
 */
class QualityFogAggregator : Aggregator<ExplorationCellFeature, SpatialData.FillCells> {
	override fun aggregate(
		features: List<ExplorationCellFeature>,
		ctx: AggContext,
	): SpatialData.FillCells {
		val tiles = features.map { cell ->
			val quality = (cell.quality / MAX_QUALITY_TIER).coerceIn(0.0, 1.0)
			cell.toTile(quality)
		}
		return SpatialData.FillCells(tiles)
	}
}

/**
 * A single warm-gold "wonder" hue whose **alpha rises with discovery quality**: passed-through cells
 * are a faint haze, thoroughly-explored cells a near-solid glow. Encoding the reveal in alpha (with a
 * fully-opaque fill layer) gives per-cell opacity from the one Fill shape.
 */
internal val FOG_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0x33FFE082,
	0.25f to 0x66FFD54F,
	0.5f to 0x99FFCA28.toInt(),
	0.75f to 0xCCFFB300.toInt(),
	1.0f to 0xF0FF8F00.toInt(),
)

/** Explorer's fog overlay: explored cells revealed in proportion to how thoroughly you know them. */
fun fogOfWonder(dao: ExplorationCellDao): VizPipeline<ExplorationCellFeature, SpatialData.FillCells> =
	mapViz("fog_of_wonder")
		.source(explorationCellSource(dao))
		.aggregate(QualityFogAggregator())
		.fill(colorStops = FOG_RAMP, opacity = 1f, outlineColorArgb = 0x11000000)
