package com.adsamcik.tracker.tracker.component.timer

import android.content.Context
import android.location.Location
import androidx.annotation.CallSuper
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.constant.CoordinateConstants
import com.adsamcik.tracker.common.data.LocationData
import com.adsamcik.tracker.tracker.component.TrackerTimerComponent
import com.adsamcik.tracker.tracker.component.TrackerTimerReceiver
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal abstract class LocationTrackerTimer : TrackerTimerComponent {
	protected var receiver: TrackerTimerReceiver? = null
		private set

	protected var previousLocation: Location? = null
		private set

	private var startedAtElapsedRealtimeNanos: Long = Long.MIN_VALUE

	protected val newDataLock = ReentrantLock()

	private fun isLocationYoungEnough(location: Location, previousLocation: Location): Boolean {
		val locationAge = location.elapsedRealtimeNanos - previousLocation.elapsedRealtimeNanos
		val maxAge = PREVIOUS_LOCATION_MAX_AGE_IN_SECONDS * Time.SECOND_IN_NANOSECONDS
		return locationAge < maxAge
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
			if (lastLocation.elapsedRealtimeNanos + MAX_LOCATION_AGE_IN_NANOS > startedAtElapsedRealtimeNanos &&
					isLocationValid(lastLocation)) {
				val tempData = createCollectionTempData(locations)
				receiver.onUpdate(tempData)

				previousLocation = lastLocation
			}
		}
	}

	private fun createCollectionTempData(locations: List<Location>): MutableCollectionTempData {
		require(locations.isNotEmpty())
		val location = locations.last()
		return MutableCollectionTempData(location.time, location.elapsedRealtimeNanos).apply {
			val builder = LocationData.Builder()
			builder.setLocations(locations)

			val previousLocation = previousLocation
			if (previousLocation != null &&
					isLocationYoungEnough(location, previousLocation)) {
				val distance = location.distanceTo(previousLocation)
				builder.setPreviousLocation(previousLocation, distance)
			}

			setLocationData(builder.build())
		}
	}

	@CallSuper
	override fun onEnable(context: Context, receiver: TrackerTimerReceiver) {
		newDataLock.withLock {
			this.receiver = receiver
			startedAtElapsedRealtimeNanos = Time.elapsedRealtimeNanos
		}
	}

	@CallSuper
	override fun onDisable(context: Context) {
		newDataLock.withLock {
			this.receiver = null
		}
	}

	companion object {
		private const val PREVIOUS_LOCATION_MAX_AGE_IN_SECONDS = 30
		private const val MAX_LOCATION_AGE_IN_NANOS = 10 * Time.SECOND_IN_NANOSECONDS
	}

}
