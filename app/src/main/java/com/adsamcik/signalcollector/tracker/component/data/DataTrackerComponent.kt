package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.tracker.component.TrackerComponent
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.google.android.gms.location.LocationResult

interface DataTrackerComponent : TrackerComponent {
	fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData)
}