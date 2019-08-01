package com.adsamcik.signalcollector.tracker.component

import com.adsamcik.signalcollector.tracker.data.MutableCollectionTempData

internal interface PreTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	suspend fun onNewData(data: MutableCollectionTempData): Boolean
}