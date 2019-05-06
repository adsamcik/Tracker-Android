package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult

class ActivityTrackerComponent : DataTrackerComponent {
	override fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData) {
		//todo decide whether better approximation is useful
		/*ar resolvedActivity = activity
		val location = locationResult.lastLocation
		if (location.hasSpeed()) {
			val speed = location.speed
			val accuracy = location.accuracy
			val speedLowerBound = (speed - (accuracy / 2f)).coerceAtLeast(0f)
			if (speedLowerBound > 2f && activity.activity)
		}*/
		collectionData.setActivity(activity)
	}

	override fun onDisable(context: Context) {}

	override fun onEnable(context: Context) {}

}