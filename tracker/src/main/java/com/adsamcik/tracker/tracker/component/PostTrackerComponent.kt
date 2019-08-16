package com.adsamcik.tracker.tracker.component

import android.content.Context
import androidx.annotation.MainThread
import com.adsamcik.tracker.common.data.CollectionData
import com.adsamcik.tracker.common.data.TrackerSession
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal interface PostTrackerComponent : TrackerComponent, TrackerDataConsumerComponent {
	@MainThread
	fun onNewData(context: Context,
	              session: TrackerSession,
	              collectionData: CollectionData,
	              tempData: CollectionTempData)
}

