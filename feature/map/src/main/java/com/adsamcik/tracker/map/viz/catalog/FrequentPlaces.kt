package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.viz.MarkerAggregator
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.VizSource
import com.adsamcik.tracker.map.viz.circles
import com.adsamcik.tracker.map.viz.mapViz
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import kotlin.math.ln

/**
 * **Frequent Places** — your important places as circle markers, each sized and coloured by how
 * often you visit it. Home and work become big warm dots; a place you went once is a small cool
 * dot. Uses the engine's [Circle][com.adsamcik.tracker.map.viz.circles] shape over a DAO source, so
 * it needs no engine changes — just a source + a declarative factory.
 */

private const val E7 = 1e7

/** Visit count that maps to full size/heat; log-scaled so home/work don't dwarf everything else. */
private const val VISIT_REFERENCE = 50.0

/** How many places to load (frequent places are few; a generous cap is plenty). */
private const val MAX_PLACES = 500

/**
 * **Source**: canonical frequent places from the local DAO, weighted by a stable log-scaled visit
 * count. Places are cumulative, so the viewport/date filters are intentionally ignored.
 */
fun frequentPlaceSource(dao: FrequentPlaceDao): VizSource<WeightedGeoFeature> = VizSource {
	dao.getAll(MAX_PLACES).map { place ->
		WeightedGeoFeature(
			lat = place.centerLatE7 / E7,
			lon = place.centerLonE7 / E7,
			time = place.lastVisitMs,
			weight = visitWeight(place.visitCount),
		)
	}
}

/** Log-scaled, absolute (non-viewport) visit weight in [0, 1] — stable as the user pans. */
internal fun visitWeight(visitCount: Int): Double =
	(ln(1.0 + visitCount) / ln(1.0 + VISIT_REFERENCE)).coerceIn(0.0, 1.0)

/** Cool teal (rare) -> warm amber -> hot pink (your anchors). Opaque: markers are discrete points. */
internal val PLACES_RAMP: List<Pair<Float, Int>> = listOf(
	0.0f to 0xFF26A69A.toInt(),
	0.5f to 0xFFFFB300.toInt(),
	1.0f to 0xFFEC407A.toInt(),
)

/** Frequent-place markers sized/coloured by visit frequency. */
fun frequentPlaces(dao: FrequentPlaceDao): VizPipeline<WeightedGeoFeature, SpatialData.Markers> =
	mapViz("frequent_places")
		.source(frequentPlaceSource(dao))
		.aggregate(MarkerAggregator())
		.circles(colorStops = PLACES_RAMP, minRadiusDp = 6f, maxRadiusDp = 26f, opacity = 0.85f)
