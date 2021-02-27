package com.adsamcik.tracker.shared.utils.module

import android.content.Context
import androidx.annotation.WorkerThread
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.TrackerSession

/**
 * Public interface used for subscribing to collection events.
 */
interface PostTrackerPublicComponent {
	/**
	 * Called when new data is tracked.
	 */
	@WorkerThread
	fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData
	)
}
