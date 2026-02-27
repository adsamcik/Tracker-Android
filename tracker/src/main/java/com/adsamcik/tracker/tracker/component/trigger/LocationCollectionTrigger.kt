package com.adsamcik.tracker.tracker.component.trigger

import android.content.Context
import android.location.Location
import androidx.annotation.CallSuper
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.constant.CoordinateConstants
import com.adsamcik.tracker.shared.base.data.LocationData
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
	@Volatile
	protected var receiver: TrackerTimerReceiver? = null
		private set

	protected var previousLocation: Location? = null
		private set

	private var startedAtElapsedRealtimeNanos: Long = Long.MIN_VALUE

	protected val newDataLock = ReentrantLock()

	private fun isLocationFreshEnough(location: Location): Boolean {
		val elapsedRealtimeNanos = location.elapsedRealtimeNanos
		if (elapsedRealtimeNanos <= 0L || startedAtElapsedRealtimeNanos == Long.MIN_VALUE) {
			return true
		}

		return elapsedRealtimeNanos + MAX_LOCATION_AGE_IN_NANOS > startedAtElapsedRealtimeNanos
	}

	private fun isLocationValid(location: Location): Boolean {
		return location.latitude >= CoordinateConstants.MIN_LATITUDE &&
				location.latitude <= CoordinateConstants.MAX_LATITUDE &&
				location.longitude >= CoordinateConstants.MIN_LONGITUDE &&
				location.longitude <= CoordinateConstants.MAX_LONGITUDE
	}

	protected fun onNewData(locations: List<Location>) {
		require(locations.isNotEmpty())
		newDataLock.withLock {
			val receiver = receiver ?: return

			val lastLocation = locations.last()
			if (isLocationFreshEnough(lastLocation) && isLocationValid(lastLocation)) {
				val cycle = createTrackingCycle(locations)
				receiver.onUpdate(cycle)

				previousLocation = lastLocation
			}
		}
	}

	private fun createTrackingCycle(locations: List<Location>): TrackingCycle {
		require(locations.isNotEmpty())
		val location = locations.last()
		val builder = TrackingCycleBuilder(location.time, location.elapsedRealtimeNanos)

		val locationBuilder = LocationData.Builder()
		locationBuilder.setLocations(locations)

		val previousLocation = previousLocation
		if (previousLocation != null) {
			val distance = location.distanceTo(previousLocation)
			locationBuilder.setPreviousLocation(previousLocation, distance)
		}

		builder.location = locationBuilder.build()
		return builder.build()
	}

	@CallSuper
	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		newDataLock.withLock {
			this.receiver = receiver
			previousLocation = null
			startedAtElapsedRealtimeNanos = Time.elapsedRealtimeNanos
		}
	}

	@CallSuper
	override fun onDisable(context: Context) {
		newDataLock.withLock {
			this.receiver = null
			previousLocation = null
		}
	}

	companion object {
		private const val MAX_LOCATION_AGE_IN_NANOS = 10 * Time.SECOND_IN_NANOSECONDS
	}

}
