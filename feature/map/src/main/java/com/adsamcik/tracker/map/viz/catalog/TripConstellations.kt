package com.adsamcik.tracker.map.viz.catalog

import com.adsamcik.tracker.map.data.Bounds
import com.adsamcik.tracker.map.viz.ArcAggregator
import com.adsamcik.tracker.map.viz.ArcFeature
import com.adsamcik.tracker.map.viz.SpatialData
import com.adsamcik.tracker.map.viz.VizPipeline
import com.adsamcik.tracker.map.viz.VizSource
import com.adsamcik.tracker.map.viz.ArcEnvelope
import com.adsamcik.tracker.map.viz.curveEnvelope
import com.adsamcik.tracker.map.viz.flowArcs
import com.adsamcik.tracker.map.viz.mapViz
import com.adsamcik.tracker.shared.base.database.dao.FrequentPlaceDao
import com.adsamcik.tracker.shared.base.database.dao.InferredTripDao
import java.util.Locale

private const val E7 = 1e7
private const val MAX_PLACES = 5_000

/** Joins local inferred trips to their local frequent-place endpoints without any schema change. */
fun tripConstellationSource(
	tripDao: InferredTripDao,
	placeDao: FrequentPlaceDao,
): VizSource<ArcFeature> = VizSource { request ->
	val trips = tripDao.getAllBetween(request.dateRange.first, request.dateRange.last)
	if (trips.isEmpty()) {
		emptyList()
	} else {
		val places = placeDao.getAll(MAX_PLACES).associateBy { it.id }
		trips.mapNotNull { trip ->
			val departureId = trip.departurePlaceId ?: return@mapNotNull null
			val arrivalId = trip.arrivalPlaceId ?: return@mapNotNull null
			if (departureId == arrivalId) return@mapNotNull null
			val departure = places[departureId] ?: return@mapNotNull null
			val arrival = places[arrivalId] ?: return@mapNotNull null
			val startLat = departure.centerLatE7 / E7
			val startLon = departure.centerLonE7 / E7
			val endLat = arrival.centerLatE7 / E7
			val endLon = arrival.centerLonE7 / E7
			val category = trip.transportMode.toArcCategory()
			val feature = ArcFeature(
				startLat = startLat,
				startLon = startLon,
				endLat = endLat,
				endLon = endLon,
				time = trip.startTimeMs,
				groupKey = "$departureId:$arrivalId:$category",
				category = category,
			)
			val envelope = feature.curveEnvelope() ?: return@mapNotNull null
			if (request.bounds?.intersects(envelope) == false) return@mapNotNull null
			feature
		}
	}
}

private fun String.toArcCategory(): String {
	val normalized = lowercase(Locale.ROOT)
	val token = normalized.replace('-', '_').replace(' ', '_')
	return when {
		token == "walk" || token == "run" || "foot" in normalized -> "walk"
		token == "cycle" || "bike" in normalized || "cycl" in normalized -> "bike"
		token == "drive" || "car" in normalized || "vehicle" in normalized -> "car"
		token == "transit" || token == "high_speed_rail" ||
			"bus" in normalized || "train" in normalized || "transit" in normalized ||
			"tram" in normalized || "metro" in normalized -> "transit"
		else -> "other"
	}
}

private fun Bounds.intersects(envelope: ArcEnvelope): Boolean {
	if (envelope.maxLat < south || envelope.minLat > north) return false
	return intersectsLongitudeRange(envelope.minLon, envelope.maxLon)
}

fun tripConstellations(
	tripDao: InferredTripDao,
	placeDao: FrequentPlaceDao,
): VizPipeline<ArcFeature, SpatialData.Arcs> =
	mapViz("trip_constellations")
		.source(tripConstellationSource(tripDao, placeDao))
		.aggregate(ArcAggregator())
		.flowArcs()
