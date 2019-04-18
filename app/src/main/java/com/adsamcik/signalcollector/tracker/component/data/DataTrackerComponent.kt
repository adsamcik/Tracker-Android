package com.adsamcik.signalcollector.tracker.component.data

import android.content.Context
import android.location.Location
import androidx.lifecycle.LifecycleOwner
import com.adsamcik.signalcollector.activity.ActivityInfo
import com.adsamcik.signalcollector.tracker.data.MutableCollectionData
import com.google.android.gms.location.LocationResult

interface DataTrackerComponent {
	fun onLocationUpdated(locationResult: LocationResult, previousLocation: Location?, distance: Float, activity: ActivityInfo, collectionData: MutableCollectionData)

	fun onDisable(context: Context, owner: LifecycleOwner)
	fun onEnable(context: Context, owner: LifecycleOwner)
}