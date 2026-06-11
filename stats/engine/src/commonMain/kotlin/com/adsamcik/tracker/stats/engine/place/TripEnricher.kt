package com.adsamcik.tracker.stats.engine.place

import com.adsamcik.tracker.stats.api.DetectedActivityType
import com.adsamcik.tracker.stats.api.SegmentEvent
import com.adsamcik.tracker.stats.api.TransportMode
import com.adsamcik.tracker.stats.engine.segment.SessionSegmentDetector

/**
 * Stateless enricher that converts a [SegmentEvent.TripEnded] into an [EnrichedTrip]
 * with place matching and leg breakdown.
 *
 * Place matching uses [SessionSegmentDetector.approximateDistanceE7] for distance
 * calculation against existing clusters.
 */
class TripEnricher(
	private val config: PlaceClusterConfig = PlaceClusterConfig()
) {

	/**
	 * Enrich a completed trip with place matching and leg breakdown.
	 *
	 * @param event The trip-ended event from the detector
	 * @param departureLatE7 Departure latitude in E7, if available
	 * @param departureLonE7 Departure longitude in E7, if available
	 * @param arrivalLatE7 Arrival latitude in E7, if available
	 * @param arrivalLonE7 Arrival longitude in E7, if available
	 * @param existingClusters Known place clusters for matching
	 * @param source Trip source classification string
	 * @param inferenceVersion Version of the inference algorithm
	 * @param segmentId ID of the persisted SessionSegment row
	 * @return Enriched trip with place matches and legs
	 */
	fun enrich(
		event: SegmentEvent.TripEnded,
		departureLatE7: Int?,
		departureLonE7: Int?,
		arrivalLatE7: Int?,
		arrivalLonE7: Int?,
		existingClusters: List<PlaceCluster>,
		source: String,
		inferenceVersion: String?,
		segmentId: Long = 0L
	): EnrichedTrip {
		val departurePlaceMatch = if (departureLatE7 != null && departureLonE7 != null) {
			matchPlace(departureLatE7, departureLonE7, existingClusters)
		} else {
			null
		}

		val arrivalPlaceMatch = if (arrivalLatE7 != null && arrivalLonE7 != null) {
			matchPlace(arrivalLatE7, arrivalLonE7, existingClusters)
		} else {
			null
		}

		// Single-leg trip (multi-leg breakdown is a future enhancement)
		val leg = TripLeg(
			sequenceIndex = 0,
			startTimeMs = event.startTimeMs,
			endTimeMs = event.endTimeMs,
			distanceM = event.totalDistanceM,
			transportMode = event.inferredTransportMode
		)

		return EnrichedTrip(
			startTimeMs = event.startTimeMs,
			endTimeMs = event.endTimeMs,
			distanceM = event.totalDistanceM,
			steps = if (event.totalSteps > 0) event.totalSteps else null,
			primaryActivity = event.primaryActivity?.let { mapActivityTypeToInt(it) },
			transportMode = event.inferredTransportMode,
			departurePlaceMatch = departurePlaceMatch,
			arrivalPlaceMatch = arrivalPlaceMatch,
			legs = listOf(leg),
			source = source,
			inferenceVersion = inferenceVersion,
			segmentId = segmentId
		)
	}

	internal fun matchPlace(
		latE7: Int,
		lonE7: Int,
		clusters: List<PlaceCluster>
	): PlaceMatchResult {
		var bestCluster: PlaceCluster? = null
		var bestDistance = Float.MAX_VALUE

		for (cluster in clusters) {
			val distance = SessionSegmentDetector.approximateDistanceE7(
				latE7, lonE7, cluster.centerLatE7, cluster.centerLonE7
			)
			if (distance <= config.matchRadiusM && distance < bestDistance) {
				bestDistance = distance
				bestCluster = cluster
			}
		}

		return if (bestCluster != null) {
			PlaceMatchResult.Matched(bestCluster)
		} else {
			PlaceMatchResult.NewPlace(latE7, lonE7)
		}
	}

	companion object {
		/**
		 * Map [DetectedActivityType] to integer for database storage.
		 * Values match Google Play Services DetectedActivity constants.
		 */
		internal fun mapActivityTypeToInt(type: DetectedActivityType): Int = when (type) {
			DetectedActivityType.IN_VEHICLE -> 0
			DetectedActivityType.ON_BICYCLE -> 1
			DetectedActivityType.ON_FOOT -> 2
			DetectedActivityType.STILL -> 3
			DetectedActivityType.TILTING -> 5
			DetectedActivityType.WALKING -> 7
			DetectedActivityType.RUNNING -> 8
			DetectedActivityType.UNKNOWN -> 4
		}
	}
}
