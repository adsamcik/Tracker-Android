package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal interface PostTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	@WorkerThread
	fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	)
}

