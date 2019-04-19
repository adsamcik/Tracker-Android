package com.adsamcik.signalcollector.tracker.component.post

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.tracker.component.TrackerComponent
import com.adsamcik.signalcollector.tracker.data.collection.CollectionData
import com.adsamcik.signalcollector.tracker.data.session.TrackerSession

interface PostTrackerComponent : TrackerComponent {
	fun onNewData(context: Context, session: TrackerSession, location: Location, collectionData: CollectionData)
}