package com.adsamcik.signalcollector.tracker.component.post

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.tracker.data.CollectionData
import com.adsamcik.signalcollector.tracker.data.TrackerSession

interface PostTrackerComponent {
	fun onNewData(context: Context, session: TrackerSession, location: Location, collectionData: CollectionData)
}