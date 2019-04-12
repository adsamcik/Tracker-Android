package com.adsamcik.signalcollector.tracker.component.data

import android.location.Location
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.google.android.gms.location.LocationResult

interface DataTrackerComponent {
	fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, collectionData: MutableCollectionData)
}