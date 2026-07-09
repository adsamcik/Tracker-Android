package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.GeoQuery
import com.adsamcik.tracker.map.data.GeoRepository
import com.adsamcik.tracker.map.data.GeoSource
import com.adsamcik.tracker.map.data.WeightedGeoFeature
import com.adsamcik.tracker.map.viz.VizRequest
import com.adsamcik.tracker.map.viz.VizSource
import com.adsamcik.tracker.shared.base.data.CellType
import kotlinx.coroutines.flow.first

/**
 * [VizSource] factories for the grid-heatmap family. Each queries local storage only (privacy: no
 * network) and maps raw column values to a per-fix weight in `[0, 1]`. The colour of a density
 * heatmap is driven by the aggregator (visit count), so for those layers this weight is retained
 * only as confidence metadata; value heatmaps (speed, signal) use it as the measured quantity.
 */

/** Builds a bounds/time-filtered [GeoQuery] from a [VizRequest]. */
internal fun VizRequest.toQuery(source: GeoSource, weightColumn: String? = null): GeoQuery =
	GeoQuery(
		source = source,
		bounds = bounds,
		timeFrom = dateRange.first.takeIf { it > 0L },
		timeTo = dateRange.last.takeIf { it < Long.MAX_VALUE },
		weight = weightColumn,
	)

/** Accuracy above this (metres) yields a confidence weight of 0 (5 m -> 0.9, 25 m -> 0.5). */
private const val MAX_ACCURACY_METERS = 50.0

/** 30 m/s (~108 km/h) saturates the speed ramp to its hottest bucket. */
private const val MAX_SPEED_MPS = 30.0

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
fun speedSource(repo: GeoRepository): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.queryWeighted(request.toQuery(GeoSource.LOCATION, "speed"), "speed").first()
		.map { it.copy(weight = (it.weight / MAX_SPEED_MPS).coerceIn(0.0, 1.0)) }
}

/**
 * Cell-tower fixes weighted by signal strength, technology-normalised so a strong LTE cell and a
 * strong GSM cell read the same. When [invert] is true the weight is flipped (`1 - strength`) so weak
 * or absent coverage reads hot — the signal dead-zone view. Inversion is per raw sample (before
 * aggregation), so a cell reads red only when its readings are *consistently* weak.
 */
fun cellSignalSource(repo: GeoRepository, invert: Boolean = false): VizSource<WeightedGeoFeature> =
	VizSource { request ->
		repo.queryCellSignals(request.toQuery(GeoSource.CELL, "asu")).first().map { feature ->
			val strength = feature.asu.toCellSignalWeight(feature.networkType)
			WeightedGeoFeature(
				lat = feature.lat,
				lon = feature.lon,
				time = feature.time,
				weight = if (invert) (1.0 - strength).coerceIn(0.0, 1.0) else strength,
			)
		}
	}

/**
 * Wi-Fi fixes weighted by signal quality: levels are stored as negative dBm and normalised into
 * `[0, 1]` (−100 dBm -> 0, −30 dBm -> 1) before the heatmap weight expression.
 */
fun wifiSignalSource(repo: GeoRepository): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.queryWeighted(request.toQuery(GeoSource.WIFI, "level"), "level").first()
		.map { it.copy(weight = it.weight.toWifiSignalWeight()) }
}

/** Wi-Fi observations as unit-weighted points; density comes from the [WifiCellAggregator]. */
fun wifiCountSource(repo: GeoRepository): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.query(request.toQuery(GeoSource.WIFI)).first()
		.map { WeightedGeoFeature(it.lat, it.lon, it.time, weight = 1.0) }
}

/**
 * Raw location fixes with their weight untouched. Used by the legacy tile heatmap, which colours
 * tiles by visit count (the per-fix weight is irrelevant), so no weight transform is applied.
 */
fun rawLocationSource(repo: GeoRepository): VizSource<WeightedGeoFeature> = VizSource { request ->
	repo.queryWeighted(request.toQuery(GeoSource.LOCATION, "hor_acc"), "hor_acc").first()
}

// ── Signal-strength normalisation helpers (moved verbatim from the old layer classes) ──────────

private const val GSM_MAX_ASU = 31.0
private const val CDMA_MAX_ASU = 16.0
private const val WCDMA_MAX_ASU = 31.0
private const val LTE_NR_MAX_ASU = 97.0

/** Normalise a raw ASU reading to `[0, 1]` by the max ASU of its radio technology. */
private fun Double.toCellSignalWeight(networkType: Int): Double {
	if (this <= 0.0) return 0.0
	val maxAsu = when (CellType.values().getOrNull(networkType)) {
		CellType.GSM -> GSM_MAX_ASU
		CellType.CDMA -> CDMA_MAX_ASU
		CellType.WCDMA -> WCDMA_MAX_ASU
		CellType.LTE,
		CellType.NR -> LTE_NR_MAX_ASU
		CellType.Unknown,
		CellType.None,
		null -> LTE_NR_MAX_ASU
	}
	return (this / maxAsu).coerceIn(0.0, 1.0)
}

/** Normalise a Wi-Fi level in dBm to `[0, 1]` (−100 dBm -> 0, −30 dBm -> 1). */
private fun Double.toWifiSignalWeight(): Double {
	if (!isFinite()) return 0.0
	val clampedDbm = coerceIn(-100.0, -30.0)
	return ((clampedDbm + 100.0) / 70.0).coerceIn(0.0, 1.0)
}
