package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ActivitySnapshot
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Activity snapshot writer for sessionless architecture.
 * Records activity recognition transitions and periodic updates.
 *
 * Contract:
 * - Writes snapshot on activity type change (transition)
 * - Periodically writes current activity (every N collections)
 * - Tracks activity history for later session inference
 */
internal class ActivitySnapshotWriter : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList() // Optional data

	private lateinit var database: AppDatabase
	private var scope: CoroutineScope? = null
	
	private var lastActivityType: Int = -1
	private var periodicCounter: Int = 0

	override suspend fun onEnable(context: Context) {
		database = AppDatabase.database(context)
		scope = CoroutineScope(Job() + Dispatchers.Default)
		lastActivityType = -1
		periodicCounter = 0
	}

	override suspend fun onDisable(context: Context) {
		lastActivityType = -1
		scope?.cancel()
		scope = null
	}

	override fun onNewData(
		context: Context,
		session: com.adsamcik.tracker.shared.base.data.TrackerSession,
		collectionData: CollectionData,
		tempData: com.adsamcik.tracker.tracker.data.collection.CollectionTempData
	) {
		val currentActivity = collectionData.activity ?: return
		val currentTime = Time.nowMillis
		
		val isTransition = lastActivityType >= 0 && lastActivityType != currentActivity.activityType
		
		// Write on transitions or periodically
		if (isTransition || periodicCounter >= PERIODIC_WRITE_INTERVAL) {
			val snapshot = ActivitySnapshot(
				timeMs = currentTime,
				activityType = currentActivity.activityType,
				confidence = currentActivity.confidence,
				isTransition = isTransition,
				createdAt = currentTime
			)

			scope?.launch(Dispatchers.IO) {
				database.activitySnapshotDao().insert(snapshot)
			}

			periodicCounter = 0
		} else {
			periodicCounter++
		}

		lastActivityType = currentActivity.activityType
	}

	companion object {
		// Write non-transition snapshots every N collections (e.g., every 5-10 minutes if collecting every minute)
		private const val PERIODIC_WRITE_INTERVAL = 5
	}
}
