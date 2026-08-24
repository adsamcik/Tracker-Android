package com.adsamcik.tracker.tracker.source.runtime

import android.location.Location
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants

/** Canonical source-field ordering for provider batches, independent of callback list permutation. */
internal fun normalizeLocationBatch(locations: List<Location>): List<Location> = locations
	.withIndex()
	.map { indexed ->
		IndexedProviderObservation(
			indexed.index,
			indexed.value,
			indexed.value.providerObservationIdentity(),
		)
	}
	.sortedWith(
		compareBy<IndexedProviderObservation>(
			{ it.identity.elapsedRealtimeNanos },
			{ it.identity.wallTimeMs },
			{ it.identity.provider },
			{ it.identity.latitude },
			{ it.identity.longitude },
			{ it.identity.accuracy },
			{ it.identity.altitude },
			{ it.identity.verticalAccuracy },
			{ it.identity.speed },
			{ it.identity.bearing },
			// Only exact provider-observation duplicates can reach this tie-breaker; distinctBy
			// removes them, so their incoming order cannot affect delivery bytes or unit order.
			{ it.originalIndex },
		),
	)
	.distinctBy(IndexedProviderObservation::identity)
	.map(IndexedProviderObservation::location)

private data class IndexedProviderObservation(
	val originalIndex: Int,
	val location: Location,
	val identity: ProviderObservationIdentity,
)

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

/**
 * Returns only provider-observed monotonic time from the current boot domain.
 *
 * Receipt time is deliberately not a fallback: doing so would turn an undated cached fix into a
 * fresh observation. Future provider time is likewise unverifiable and is rejected before any
 * source identity or sequence is allocated.
 */
internal fun Location.qualifiedObservedElapsedRealtimeNanos(
	receivedElapsedRealtimeNanos: Long,
): Long? = elapsedRealtimeNanos.takeIf { observed ->
	receivedElapsedRealtimeNanos >= 0L && observed > 0L && observed <= receivedElapsedRealtimeNanos
}
