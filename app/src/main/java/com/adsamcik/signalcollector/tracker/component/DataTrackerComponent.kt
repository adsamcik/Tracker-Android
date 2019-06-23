package com.adsamcik.signalcollector.tracker.component

import android.location.Location
import androidx.annotation.WorkerThread
import com.adsamcik.signalcollector.common.data.ActivityInfo
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult

interface DataTrackerComponent : TrackerComponent {
	@WorkerThread
	suspend fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData)
}