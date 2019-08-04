package com.adsamcik.signalcollector.tracker.component

import android.content.Context
import androidx.annotation.MainThread
import com.adsamcik.signalcollector.common.data.TrackerSession
import com.adsamcik.signalcollector.tracker.data.collection.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.CollectionData

internal interface PostTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	@MainThread
	fun onNewData(context: Context, session: TrackerSession, collectionData: CollectionData, tempData: CollectionTempData)
}