package com.adsamcik.tracker.tracker.component

import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle

internal interface DataTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	@WorkerThread
	suspend fun onDataUpdated(
		cycle: TrackingCycle,
		collectionData: MutableCollectionData
	)
}

