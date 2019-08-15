package com.adsamcik.tracker.tracker.component

import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData

internal interface PreTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	suspend fun onNewData(data: MutableCollectionTempData): Boolean
}
