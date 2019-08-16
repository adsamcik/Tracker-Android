package com.adsamcik.tracker.tracker.component

import androidx.annotation.WorkerThread
import com.adsamcik.tracker.common.data.MutableCollectionData
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal interface DataTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	@WorkerThread
	suspend fun onDataUpdated(tempData: CollectionTempData,
	                          collectionData: MutableCollectionData
	)
}

