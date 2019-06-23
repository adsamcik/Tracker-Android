package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import android.location.Location
import androidx.annotation.MainThread
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.tracker.data.collection.CollectionData

interface PostTrackerComponent : TrackerComponent {
	@MainThread
	fun onNewData(context: Context, session: TrackerSession, location: Location, collectionData: CollectionData)
}