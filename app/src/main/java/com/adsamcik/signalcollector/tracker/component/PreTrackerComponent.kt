package com.adsamcik.signalcollector.tracker.component

import android.location.Location
import com.google.android.gms.location.LocationResult

interface PreTrackerComponent : TrackerComponent {
	suspend fun onNewLocation(locationResult: LocationResult, previousLocation: Location?, distance: Float): Boolean
}