package com.adsamcik.tracker.stats.engine.place

import com.adsamcik.tracker.stats.api.TransportMode

/**
 * Result of trip enrichment: a trip with place matching and leg breakdown.
 */
data class EnrichedTrip(
	val startTimeMs: Long,
	val endTimeMs: Long,
	val distanceM: Float,
	val steps: Int?,
	val primaryActivity: Int?,
	val transportMode: TransportMode,
	val departurePlaceMatch: PlaceMatchResult?,
	val arrivalPlaceMatch: PlaceMatchResult?,
	val legs: List<TripLeg>,
	val source: String,
	val inferenceVersion: String?,
	val segmentId: Long
)

/**
 * A single leg of a trip with a uniform transport mode.
 */
data class TripLeg(
	val sequenceIndex: Int,
	val startTimeMs: Long,
	val endTimeMs: Long,
	val distanceM: Float,
	val transportMode: TransportMode
)
