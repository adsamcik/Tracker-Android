package com.adsamcik.signalcollector.tracker.component

import androidx.annotation.WorkerThread
import com.adsamcik.signalcollector.common.data.MutableCollectionData
import com.adsamcik.signalcollector.tracker.data.collection.CollectionTempData

internal interface DataTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	@WorkerThread
	suspend fun onDataUpdated(tempData: CollectionTempData,
	                          collectionData: MutableCollectionData)
}