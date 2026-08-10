package com.adsamcik.tracker.tracker.source.runtime

import android.location.Location
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants

/** Stable event-time ordering for provider batches; input order breaks exact ties. */
internal fun normalizeLocationBatch(locations: List<Location>): List<Location> = locations
	.withIndex()
	.sortedWith(compareBy({ it.value.elapsedRealtimeNanos }, { it.value.time }, { it.index }))
	.distinctBy { indexed -> indexed.value.providerObservationIdentity() }
	.map { indexed -> indexed.value }

private fun Location.providerObservationIdentity() = ProviderObservationIdentity(
	provider = provider,
	elapsedRealtimeNanos = elapsedRealtimeNanos,
	wallTimeMs = time,
	latitude = latitude,
	longitude = longitude,
	accuracy = accuracy.takeIf { hasAccuracy() },
	altitude = altitude.takeIf { hasAltitude() },
	verticalAccuracy = verticalAccuracyMeters.takeIf { hasVerticalAccuracy() },
	speed = speed.takeIf { hasSpeed() },
	bearing = bearing.takeIf { hasBearing() },
)

private data class ProviderObservationIdentity(
	val provider: String?,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
	val latitude: Double,
	val longitude: Double,
	val accuracy: Float?,
	val altitude: Double?,
	val verticalAccuracy: Float?,
	val speed: Float?,
	val bearing: Float?,
)

internal fun Location.isValidLocationEvidence(): Boolean =
	latitude.isFinite() && longitude.isFinite() &&
		latitude in CoordinateConstants.MIN_LATITUDE..CoordinateConstants.MAX_LATITUDE &&
		longitude in CoordinateConstants.MIN_LONGITUDE..CoordinateConstants.MAX_LONGITUDE &&
		hasAccuracy() && accuracy.isFinite() && accuracy >= 0f
