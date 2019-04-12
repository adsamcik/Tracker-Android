package com.adsamcik.signalcollector.tracker.component.post

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.tracker.data.CollectionData

interface PostTrackerComponent {
	fun onNewData(context: Context, location: Location, collectionData: CollectionData)
}