package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.data.CellRadioGeoFeature
import com.adsamcik.tracker.map.data.WifiRadioGeoFeature
import com.adsamcik.tracker.map.data.paddedBounds
import com.adsamcik.tracker.map.viz.normalizedCellSignalWeight
import com.adsamcik.tracker.map.viz.VizRequest
import com.adsamcik.tracker.map.viz.VizSource
import kotlinx.coroutines.flow.first

/**
 * [VizSource] factories for the grid-heatmap family. Each queries local storage only (privacy: no
 * network) and maps raw column values to a per-fix weight in `[0, 1]`. The colour of a density
 * heatmap is driven by the aggregator (visit count), so for those layers this weight is retained
 * only as confidence metadata; value heatmaps (speed, signal) use it as the measured quantity.
 */

enum class SourceRowSelection {
	EvenlySampled,
	NewestOrdered,
}

/** Builds a bounds/time-filtered and source-budgeted [GeoQuery] from a [VizRequest]. */
internal fun VizRequest.toQuery(
	source: GeoSource,
	weightColumn: String? = null,
	rowSelection: SourceRowSelection = SourceRowSelection.EvenlySampled,
): GeoQuery =
	GeoQuery(
		source = source,
		bounds = bounds.takeIf { rowSelection != SourceRowSelection.NewestOrdered },
		timeFrom = dateRange.first.takeIf { it > 0L },
		timeTo = dateRange.last.takeIf { it < Long.MAX_VALUE },
		weight = weightColumn,
		sampleLimit = maxFeatures.takeIf { rowSelection == SourceRowSelection.EvenlySampled },
		newestLimit = maxFeatures.takeIf { rowSelection == SourceRowSelection.NewestOrdered },
	)

/** Accuracy above this (metres) yields a confidence weight of 0 (5 m -> 0.9, 25 m -> 0.5). */
private const val MAX_ACCURACY_METERS = 50.0

/** 30 m/s (~108 km/h) saturates the speed ramp to its hottest bucket. */
private const val MAX_SPEED_MPS = 30.0

/** Stable altitude domain used across viewports: Dead Sea vicinity through high mountain travel. */
private const val MIN_ALTITUDE_METERS = -100.0
private const val MAX_ALTITUDE_METERS = 4_000.0

/**
 * Location fixes weighted by GPS-accuracy confidence: `hor_acc` (metres, lower = better) is inverted
 * and clamped to `[0, 1]`. Feeds the density heatmap, where colour = visit count.
 */
fun locationDensitySource(repo: GeoRepository): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.queryWeighted(request.toQuery(GeoSource.LOCATION, "hor_acc"), "hor_acc").first()
		.map { it.copy(weight = (1.0 - it.weight / MAX_ACCURACY_METERS).coerceIn(0.0, 1.0)) }
}

/**
 * Location fixes weighted by speed: raw m/s normalised to `[0, 1]` against [MAX_SPEED_MPS] so the
 * colour ramp maps meaningfully and MapLibre's `heatmap-weight` doesn't saturate on one sample.
 */
fun speedSource(
	repo: GeoRepository,
	rowSelection: SourceRowSelection = SourceRowSelection.EvenlySampled,
): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.queryWeighted(request.toQuery(GeoSource.LOCATION, "speed", rowSelection), "speed").first()
		.map { it.copy(weight = (it.weight / MAX_SPEED_MPS).coerceIn(0.0, 1.0)) }
}

/** Location fixes weighted by altitude against a fixed global range, never viewport-relative. */
fun altitudeSource(repo: GeoRepository): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.queryWeighted(
		request.toQuery(GeoSource.LOCATION, "alt", SourceRowSelection.NewestOrdered),
		"alt",
	).first()
		.map { it.copy(weight = altitudeWeight(it.weight)) }
}

internal fun altitudeWeight(altitudeMeters: Double): Double =
	((altitudeMeters - MIN_ALTITUDE_METERS) / (MAX_ALTITUDE_METERS - MIN_ALTITUDE_METERS))
		.coerceIn(0.0, 1.0)

/**
 * Location fixes weighted by the persisted motion state: unknown = 0, still = 0.5, moving = 1.
 * This deliberately avoids the historically ambiguous rich activity ordinal.
 */
fun activitySource(repo: GeoRepository): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.queryWeighted(
		request.toQuery(GeoSource.LOCATION, "motion", SourceRowSelection.NewestOrdered),
		"motion",
	).first()
}

/**
 * Cell-tower fixes weighted by signal strength, technology-normalised so a strong LTE cell and a
 * strong GSM cell read the same. When [invert] is true the weight is flipped (`1 - strength`) so weak
 * or absent coverage reads hot — the signal dead-zone view. Inversion is per raw sample (before
 * aggregation), so a cell reads red only when its readings are *consistently* weak.
 */
fun cellSignalSource(repo: GeoRepository, invert: Boolean = false): VizSource<WeightedGeoFeature> =
	VizSource { request ->
		repo.queryCellSignals(request.toQuery(GeoSource.CELL, "asu")).first().mapNotNull { feature ->
			val strength = normalizedCellSignalWeight(feature.asu, feature.networkType) ?: return@mapNotNull null
			WeightedGeoFeature(
				lat = feature.lat,
				lon = feature.lon,
				time = feature.time,
				weight = if (invert) (1.0 - strength).coerceIn(0.0, 1.0) else strength,
			)
		}
	}

/**
 * Identity-bearing Wi-Fi observations for band-aware coverage, overlap, and conservative AP
 * estimation. The padded query avoids making an inferred AP jump as soon as one contributing
 * observation crosses the visible viewport edge.
 */
fun wifiRadioSource(repo: GeoRepository): VizSource<WifiRadioGeoFeature> = VizSource { request ->
	val query = request.toQuery(GeoSource.WIFI).copy(bounds = request.radioBounds())
	repo.queryWifiRadios(query).first()
}

/** Identity-bearing serving-cell observations for RAT coverage and conservative site estimation. */
fun cellRadioSource(repo: GeoRepository): VizSource<CellRadioGeoFeature> = VizSource { request ->
	val query = request.toQuery(GeoSource.CELL).copy(bounds = request.radioBounds())
	repo.queryCellRadios(query).first()
}

/**
 * Raw location fixes with their weight untouched. Used by the legacy tile heatmap, which colours
 * tiles by visit count (the per-fix weight is irrelevant), so no weight transform is applied.
 */
fun rawLocationSource(repo: GeoRepository): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.queryWeighted(request.toQuery(GeoSource.LOCATION, "hor_acc"), "hor_acc").first()
}

// ── Signal-strength normalisation helpers (moved verbatim from the old layer classes) ──────────

private fun VizRequest.radioBounds() = bounds?.let {
	paddedBounds(it.north, it.east, it.south, it.west, RADIO_QUERY_PADDING) ?: it
}

private const val RADIO_QUERY_PADDING = 1.0
