package com.adsamcik.signalcollector.tracker.component

import androidx.annotation.WorkerThread
import com.adsamcik.signalcollector.tracker.data.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.MutableCollectionData

//todo redesign this so it can be used without location and reused in low power mode
internal interface DataTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	@WorkerThread
	suspend fun onDataUpdated(tempData: CollectionTempData,
	                          collectionData: MutableCollectionData)
}