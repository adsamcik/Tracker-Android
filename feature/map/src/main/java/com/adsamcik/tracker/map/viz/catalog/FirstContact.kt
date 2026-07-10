package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.viz.AggContext
import com.adsamcik.tracker.map.viz.Aggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.fill
import com.adsamcik.tracker.map.viz.mapViz
import com.adsamcik.tracker.shared.base.database.dao.ExplorationCellDao

/**
 * **First Contact** — a chronological map of your discoveries. Each explored S2 cell is coloured by
 * *how recently* you first set foot in it: the places you found long ago fade to deep indigo, the
 * ground you broke this week glows bright cyan. Panning across the map you can read the story of your
 * exploration outward from the places you've always known to your newest frontiers.
 *
 * Reuses the shared [explorationCellSource] + [SpatialData.FillCells] shape + [fill] encoder with **no
 * engine changes** — the only new piece is a recency-driven aggregator. Recency is measured against a
 * **fixed window** ([RECENCY_WINDOW_MS]) rather than the viewport, so a cell's colour never "breathes"
 * as you pan (the engine's stable-colour invariant).
 */

/** Discoveries older than this read as the oldest colour; two years gives a meaningful spread. */
internal const val RECENCY_WINDOW_MS = 2L * 365 * 24 * 60 * 60 * 1_000

/**
 * **Aggregator**: each cell -> a square tile whose weight is its first-discovery recency in `[0, 1]`
 * (1 = just now, 0 = [RECENCY_WINDOW_MS] ago or older), so the [FIRST_CONTACT_RAMP] paints newer
 * ground hotter. [nowProvider] is injectable for deterministic tests.
 */
class FirstContactAggregator(
	private val nowProvider: () -> Long = System::currentTimeMillis,
) : Aggregator<ExplorationCellFeature, SpatialData.FillCells> {
	override fun aggregate(
		features: List<ExplorationCellFeature>,
		ctx: AggContext,
	): SpatialData.FillCells {
		val now = nowProvider()
		val tiles = features.map { cell ->
			cell.toTile(recencyWeight(cell.firstDiscoveredAt, now))
		}
		return SpatialData.FillCells(tiles)
	}
}

/**
 * Recency weight in `[0, 1]`: `1` for a discovery at [now], falling linearly to `0` at
 * [RECENCY_WINDOW_MS] before now (and clamped there for anything older). A future/zero timestamp
 * clamps to the extremes rather than producing an out-of-range weight.
 */
internal fun recencyWeight(firstDiscoveredAt: Long, now: Long): Double {
	val ageMs = (now - firstDiscoveredAt).toDouble()
	if (ageMs <= 0.0) return 1.0
	return (1.0 - ageMs / RECENCY_WINDOW_MS).coerceIn(0.0, 1.0)
}

/** Old-ground indigo → recent-frontier cyan. Opaque: every explored cell is real, collected data. */
internal val FIRST_CONTACT_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0xFF311B92.toInt(),
	0.5f to 0xFF7E57C2.toInt(),
	1.0f to 0xFF00E5FF.toInt(),
)

/** First-contact overlay: explored cells coloured by how recently you first discovered them. */
fun firstContact(dao: ExplorationCellDao): VizPipeline<ExplorationCellFeature, SpatialData.FillCells> =
	mapViz("first_contact")
		.source(explorationCellSource(dao))
		.aggregate(FirstContactAggregator())
		.fill(colorStops = FIRST_CONTACT_RAMP, opacity = 0.6f, outlineColorArgb = 0x22000000)
