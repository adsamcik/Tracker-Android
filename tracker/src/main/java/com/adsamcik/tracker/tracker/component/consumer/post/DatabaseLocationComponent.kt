package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

internal class DatabaseLocationComponent : PostTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var locationDao: LocationDataDao? = null

	// Lightweight buffer to prepare for future batching (size 1 currently) and offload DB writes to IO
	private val pending = ArrayList<DatabaseLocation>(16)
	private val enqueueCount = AtomicInteger(0)
	private var scope: CoroutineScope? = null


	override fun onNewData(
			context: Context,
			session: TrackerSession,
			collectionData: CollectionData,
			tempData: CollectionTempData
	) {
		val location = collectionData.location ?: return
		val activity = collectionData.activity ?: ActivityInfo.UNKNOWN
		saveLocation(location, activity)
	}

	private fun saveLocation(location: Location, activityInfo: ActivityInfo) {
		val dao = locationDao ?: return
		val item = DatabaseLocation(location, activityInfo)
		pending.add(item)
		if (pending.size >= 1) { // threshold 1: behaves same as before, placeholder for future batching
			val toInsert = ArrayList(pending)
			pending.clear()
			scope?.launch(Dispatchers.IO) {
				val start = System.nanoTime()
				try {
					dao.insert(toInsert)
				} catch (t: Throwable) {
					// Swallow here; reporter is higher level (avoid dependency cycle)
				} finally {
					val durationMs = (System.nanoTime() - start) / 1_000_000
					// Simple coarse instrumentation via atomic counter every 100 inserts
					val c = enqueueCount.incrementAndGet()
					if (c % 100 == 0) {
						// Placeholder: hook to a diagnostics logger (left as comment to avoid overhead)
						// Logger.d("LocationDB", "Inserted $c locations avg batch=${toInsert.size} lastDurationMs=$durationMs")
					}
				}
			}
		}
	}

	override suspend fun onDisable(context: Context) {
		scope?.cancel()
		scope = null
		locationDao = null
		pending.clear()
	}

	override suspend fun onEnable(context: Context) {
		locationDao = AppDatabase.database(context).locationDao()
		scope = CoroutineScope(Job() + Dispatchers.Default)
	}
}

