package com.adsamcik.tracker.shared.base.data

import android.location.Location

/**
 * Location data from raw provider in a single collection.
 */
data class LocationData(
		val locations: List<Location>,
		val previousLocation: Location?,
		val distance: Float?,
		val fixMetadata: List<LocationFixMetadata> = locations.map { LocationFixMetadata() },
) {
	init {
		require(locations.isNotEmpty()) { "LocationData requires at least one location" }
		require(fixMetadata.size == locations.size) {
			"Location metadata count (${fixMetadata.size}) must match location count (${locations.size})"
		}
	}

	val lastLocation: Location get() = locations.last()
	val lastFixMetadata: LocationFixMetadata get() = fixMetadata.last()

	/**
	 * Builder for creating location data instance.
	 */
	class Builder {
		private var locationList: List<Location>? = null
		private var metadataList: List<LocationFixMetadata>? = null

		private var previousLocation: Location? = null

		private var distance: Float? = null

		/**
		 * Set a single location.
		 *
		 * @param location Location
		 */
		fun setLocation(location: Location) {
			setLocations(listOf(location))
		}

		/**
		 * Set a list of locations since last collection.
		 *
		 * @param locations List of locations
		 */
		fun setLocations(
			locations: List<Location>,
			metadata: List<LocationFixMetadata> = locations.map { LocationFixMetadata() },
		) {
			require(locationList == null)
			require(locations.isNotEmpty())
			require(metadata.size == locations.size)
			locationList = locations
			metadataList = metadata
		}

		/**
		 * Set previous location. Should be a location of previous collection,
		 * or any last location if this is first collection.
		 */
		fun setPreviousLocation(previousLocation: Location, distance: Float) {
			require(this.previousLocation == null)
			require(this.distance == null)

			this.previousLocation = previousLocation
			this.distance = distance
		}

		/**
		 * Build new instance of location data.
		 */
		fun build(): LocationData {
			val locationList = requireNotNull(locationList)
			return LocationData(
				locations = locationList,
				previousLocation = previousLocation,
				distance = distance,
				fixMetadata = requireNotNull(metadataList),
			)
		}
	}
}

/**
 * Delivery and request context attached to one provider fix.
 *
 * Metadata is parallel to [LocationData.locations] instead of being attached to the delivery as a
 * whole because the dispatcher may merge multiple callbacks before splitting them into individual
 * tracking cycles. Keeping one record per fix preserves the original callback boundary and request
 * semantics through that merge/split path.
 */
data class LocationFixMetadata(
	/** Identifier shared by every fix in the provider callback that delivered this one. */
	val callbackId: String? = null,
	/** Immutable provider-fix identity, distinct from any persistence/WAL identity. */
	val sourceEventId: String? = null,
	/** Conservative session-scoped monotonic-clock domain for this provider delivery. */
	val clockDomainId: String? = null,
	/** Stable device-boot domain for elapsed-realtime correlation across service sessions. */
	val bootClockDomainId: String? = null,
	/** Wall-clock time when the callback containing this fix reached the app. */
	val receivedAtMs: Long = 0L,
	/** Monotonic time when the callback containing this fix reached the app. */
	val receivedElapsedRealtimeNanos: Long = 0L,
	/** Backend that produced the request, for example [LocationAcquisitionMode.FUSED]. */
	val acquisitionMode: LocationAcquisitionMode = LocationAcquisitionMode.UNKNOWN,
	/** Power/accuracy priority active when the fix was requested. */
	val requestPriority: LocationRequestPriority = LocationRequestPriority.UNKNOWN,
	/** Permission precision in effect when the callback was received. */
	val permissionPrecision: LocationPermissionPrecision = LocationPermissionPrecision.UNKNOWN,
	/** Zero-based position in the original provider callback. */
	val batchIndex: Int = 0,
	/** Number of fixes delivered in the original provider callback. */
	val batchSize: Int = 1,
)

/** One provider-delivered fix, including fixes rejected before the curated tracking pipeline. */
data class LocationProviderObservation(
	val location: Location,
	val metadata: LocationFixMetadata,
	val ingressDisposition: LocationIngressDisposition,
)

enum class LocationIngressDisposition {
	/** Coordinate and age passed the trigger's ingress checks; later stages may still reject it. */
	DELIVERED_VALID,
	REJECTED_STALE,
	REJECTED_INVALID_COORDINATE,
	/** The raw provider time is retained for audit, but cannot enter the typed epoch-time pipeline. */
	REJECTED_INVALID_TIMESTAMP,
}

enum class LocationAcquisitionMode {
	UNKNOWN,
	FUSED,
	PLATFORM_GPS,
	PLATFORM_PASSIVE,
}

enum class LocationRequestPriority {
	UNKNOWN,
	PASSIVE,
	LOW_POWER,
	BALANCED,
	HIGH_ACCURACY,
}

enum class LocationPermissionPrecision {
	UNKNOWN,
	APPROXIMATE,
	PRECISE,
}
