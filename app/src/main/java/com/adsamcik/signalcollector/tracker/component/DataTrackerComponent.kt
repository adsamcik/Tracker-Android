package com.adsamcik.signalcollector.tracker.component

import android.location.Location
import androidx.annotation.WorkerThread
import com.adsamcik.signalcollector.tracker.data.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult

//todo redesign this so it can be used without location and reused in low power mode
interface DataTrackerComponent : TrackerComponent {
	@WorkerThread
	suspend fun onLocationUpdated(locationResult: LocationResult,
	                              previousLocation: Location?,
	                              collectionData: MutableCollectionData,
	                              tempData: CollectionTempData)
}