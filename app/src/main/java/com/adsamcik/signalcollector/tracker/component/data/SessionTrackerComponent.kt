package com.adsamcik.signalcollector.tracker.component.data

import android.location.Location
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.activity.GroupedActivity
import com.adsamcik.signalcollector.app.Constants
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.TrackerSession
import com.google.android.gms.location.LocationResult
import kotlin.math.max

class SessionTrackerComponent(startTime: Long) : DataTrackerComponent {
	val session: TrackerSession = TrackerSession(startTime)
	private var minUpdateDelayInSeconds = -1

	override fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData) {
		val location = locationResult.lastLocation
		session.apply {
			distanceInM += distance
			collections++

			if (previousLocation != null &&
					(location.time - previousLocation.time < max(Constants.SECOND_IN_MILLISECONDS * 20, minUpdateDelayInSeconds * 2 * Constants.SECOND_IN_MILLISECONDS) ||
							distance <= minDistanceInMeters * 2f)) {
				when (activity.groupedActivity) {
					GroupedActivity.ON_FOOT -> distanceOnFootInM += distance
					GroupedActivity.IN_VEHICLE -> distanceInVehicleInM += distance
					else -> {
					}
				}
			}
		}
	}

}