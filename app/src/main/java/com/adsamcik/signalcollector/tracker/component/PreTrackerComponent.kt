package com.adsamcik.signalcollector.tracker.component

import android.location.Location
import com.adsamcik.signalcollector.tracker.data.CollectionTempData
import com.google.android.gms.location.LocationResult

interface PreTrackerComponent : TrackerComponent {
	suspend fun onNewLocation(locationResult: LocationResult, previousLocation: Location?, data: CollectionTempData): Boolean
}