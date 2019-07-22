package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.tracker.component.DataTrackerComponent
import com.adsamcik.signalcollector.tracker.data.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData
import com.google.android.gms.location.LocationResult

class LocationTrackerComponent : DataTrackerComponent {
	override suspend fun onDisable(context: Context) {}
	override suspend fun onEnable(context: Context) {}

	override suspend fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, collectionData: MutableCollectionData, tempData: CollectionTempData) {
		collectionData.setLocation(locationResult.lastLocation)
	}
}