package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import android.location.Location
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants
import com.adsamcik.tracker.shared.base.data.LocationAcquisitionMode
import com.adsamcik.tracker.shared.base.data.LocationData
import com.adsamcik.tracker.shared.base.data.LocationFixMetadata
import com.adsamcik.tracker.shared.base.data.LocationIngressDisposition
import com.adsamcik.tracker.shared.base.data.LocationPermissionPrecision
import com.adsamcik.tracker.shared.base.data.LocationProviderObservation
import com.adsamcik.tracker.shared.base.data.LocationRequestPriority
import com.adsamcik.tracker.shared.base.extension.hasPreciseLocationPermission
import com.adsamcik.tracker.tracker.component.CollectionTriggerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.data.collection.TrackingCycleBuilder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Abstract collection trigger component to provide common operations and methods for location triggers.
 */
internal abstract class LocationCollectionTrigger : CollectionTriggerComponent {
	override val isLocationTrigger: Boolean get() = true

	protected abstract val acquisitionMode: LocationAcquisitionMode
	protected abstract val requestPriority: LocationRequestPriority

	@Volatile
	protected var receiver: TrackerTimerReceiver? = null
		private set

	protected var previousLocation: Location? = null
		private set

	private var startedAtElapsedRealtimeNanos: Long = Long.MIN_VALUE
	private var permissionPrecision: LocationPermissionPrecision = LocationPermissionPrecision.UNKNOWN

	protected val newDataLock = ReentrantLock()

	private fun isLocationFreshEnough(location: Location): Boolean {
		val elapsedRealtimeNanos = location.elapsedRealtimeNanos
		if (elapsedRealtimeNanos <= 0L || startedAtElapsedRealtimeNanos == Long.MIN_VALUE) {
			return true
		}

		return elapsedRealtimeNanos + MAX_LOCATION_AGE_IN_NANOS > startedAtElapsedRealtimeNanos
	}

	private fun isLocationValid(location: Location): Boolean {
		// Reject NaN/infinite coordinates plus out-of-range values. Fused/native
		// providers can deliver NaN on cold start before the first real fix is ready.
		return location.latitude.isFinite() && location.longitude.isFinite() &&
				location.latitude >= CoordinateConstants.MIN_LATITUDE &&
				location.latitude <= CoordinateConstants.MAX_LATITUDE &&
				location.longitude >= CoordinateConstants.MIN_LONGITUDE &&
				location.longitude <= CoordinateConstants.MAX_LONGITUDE
	}

	protected fun onNewData(locations: List<Location>) {
		require(locations.isNotEmpty())
		newDataLock.withLock {
			val receiver = receiver ?: return
			val receivedAtMs = Time.nowMillis
			val receivedElapsedRealtimeNanos = Time.elapsedRealtimeNanos
			val batchSize = locations.size
			val metadata = locations.indices.map { index ->
				LocationFixMetadata(
					receivedAtMs = receivedAtMs,
					receivedElapsedRealtimeNanos = receivedElapsedRealtimeNanos,
					acquisitionMode = acquisitionMode,
					requestPriority = requestPriority,
					permissionPrecision = permissionPrecision,
					batchIndex = index,
					batchSize = batchSize,
				)
			}
			val observations = locations.mapIndexed { index, location ->
				val disposition = when {
					!isLocationValid(location) -> LocationIngressDisposition.REJECTED_INVALID_COORDINATE
					!isLocationFreshEnough(location) -> LocationIngressDisposition.REJECTED_STALE
					else -> LocationIngressDisposition.DELIVERED_VALID
				}
				LocationProviderObservation(
					location = Location(location),
					metadata = metadata[index],
					ingressDisposition = disposition,
				)
			}

			// Filter every location, not just the last. A batched delivery whose final
			// fix is valid but containing earlier NaN/cold-start samples would otherwise
			// poison persistence with garbage coordinates.
			val filtered = observations.mapIndexedNotNull { index, observation ->
				locations[index].takeIf {
					observation.ingressDisposition == LocationIngressDisposition.DELIVERED_VALID
				}?.let { it to observation.metadata }
			}
			val cycle = if (filtered.isEmpty()) {
				TrackingCycle(
					timestampMs = receivedAtMs,
					elapsedRealtimeNanos = receivedElapsedRealtimeNanos,
					locationObservations = observations,
				)
			} else {
				createTrackingCycle(
					locations = filtered.map { it.first },
					metadata = filtered.map { it.second },
					observations = observations,
				)
			}
			receiver.onUpdate(cycle)

			if (filtered.isNotEmpty()) previousLocation = filtered.last().first
		}
	}

	private fun createTrackingCycle(
		locations: List<Location>,
		metadata: List<LocationFixMetadata>,
		observations: List<LocationProviderObservation>,
	): TrackingCycle {
		require(locations.isNotEmpty())
		require(metadata.size == locations.size)
		val location = locations.last()
		val builder = TrackingCycleBuilder(location.time, location.elapsedRealtimeNanos)

		val locationBuilder = LocationData.Builder()
		locationBuilder.setLocations(locations, metadata)

		val previousLocation = previousLocation
		if (previousLocation != null) {
			val distance = location.distanceTo(previousLocation)
			locationBuilder.setPreviousLocation(previousLocation, distance)
		}

		builder.location = locationBuilder.build()
		builder.locationObservations = observations
		return builder.build()
	}

	@CallSuper
	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		newDataLock.withLock {
			this.receiver = receiver
			previousLocation = null
			startedAtElapsedRealtimeNanos = Time.elapsedRealtimeNanos
			permissionPrecision = if (context.hasPreciseLocationPermission) {
				LocationPermissionPrecision.PRECISE
			} else {
				LocationPermissionPrecision.APPROXIMATE
			}
		}
	}

	@CallSuper
	override fun onDisable(context: Context) {
		newDataLock.withLock {
			this.receiver = null
			previousLocation = null
			permissionPrecision = LocationPermissionPrecision.UNKNOWN
		}
	}

	companion object {
		private const val MAX_LOCATION_AGE_IN_NANOS = 10 * Time.SECOND_IN_NANOSECONDS
	}

}
