package com.adsamcik.signalcollector.tracker.component.post

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.tracker.component.TrackerComponent
import com.adsamcik.signalcollector.tracker.data.collection.CollectionData

interface PostTrackerComponent : TrackerComponent {
	fun onNewData(context: Context, session: TrackerSession, location: Location, collectionData: CollectionData)
}