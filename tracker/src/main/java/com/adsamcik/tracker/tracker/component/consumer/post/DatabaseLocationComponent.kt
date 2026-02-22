package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import android.util.Log
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.tracker.component.PostTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

internal class DatabaseLocationComponent : PostTrackerComponent {
	companion object {
		private const val TAG = "DatabaseLocationComponent"
		private const val BATCH_SIZE = 10
		private const val FLUSH_INTERVAL_MS = 5_000L
	}

	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	private var locationDao: LocationDataDao? = null
	private var errorCollector: PersistenceErrorCollector? = null

	// Batching buffer (flush on size or time). Thread safety ensured by service-level serialization.
	private val pending = ArrayList<DatabaseLocation>(16)
	private val enqueueCount = AtomicInteger(0)
	private var scope: CoroutineScope? = null
	private var scheduledFlushJob: Job? = null


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
		pending.add(DatabaseLocation(location, activityInfo))
		if (pending.size >= BATCH_SIZE) {
			// Flush immediately; cancel any scheduled delayed flush.
			scheduledFlushJob?.cancel(); scheduledFlushJob = null
			flushAsync(dao)
		} else if (scheduledFlushJob == null) {
			// Schedule time-based flush if not already scheduled.
			scheduledFlushJob = scope?.launch {
				delay(FLUSH_INTERVAL_MS)
				val d = locationDao // re-check after delay
				if (d != null) flushAsync(d)
			}
		}
	}

	private fun flushAsync(dao: LocationDataDao) {
		if (pending.isEmpty()) return
		val toInsert = ArrayList(pending)
		pending.clear()
		// Insert on IO dispatcher
		scope?.launch(Dispatchers.IO) {
			val start = System.nanoTime()
			try {
				dao.insert(toInsert)
			} catch (e: Throwable) {
				Log.e(TAG, "Failed to batch insert locations: ${e.message}", e)
				errorCollector?.reportErrorAsync(
					PersistenceError(
						source = TAG,
						operation = "batch insert locations",
						recordCount = toInsert.size,
						cause = e
					)
				)
			} finally {
				val c = enqueueCount.addAndGet(toInsert.size)
				if (c % 100 == 0) { /* optional diagnostic hook */ }
			}
		}
	}

	private suspend fun flushImmediate() {
		val dao = locationDao ?: return
		if (pending.isEmpty()) return
		val toInsert = ArrayList(pending)
		pending.clear()
		withContext(Dispatchers.IO) {
			try {
				dao.insert(toInsert)
			} catch (e: Throwable) {
				Log.e(TAG, "Failed to flush locations: ${e.message}", e)
				errorCollector?.reportError(
					PersistenceError(
						source = TAG,
						operation = "flush locations",
						recordCount = toInsert.size,
						cause = e
					)
				)
			}
		}
	}

	// Exposed for service shutdown to guarantee persistence before component disable.
	suspend fun flushPending() = flushImmediate()

	/**
	 * Sets the error collector for reporting persistence failures.
	 * Should be called before onEnable.
	 */
	fun setErrorCollector(collector: PersistenceErrorCollector) {
		this.errorCollector = collector
	}

	override suspend fun onDisable(context: Context) {
		// Ensure any scheduled flush does not run after disable and flush remaining immediately.
		scheduledFlushJob?.cancel(); scheduledFlushJob = null
		flushImmediate()
		scope?.coroutineContext?.get(Job)?.children?.toList()?.forEach { it.join() }
		scope?.cancel(); scope = null
		errorCollector = null
		locationDao = null
		pending.clear()
	}

	override suspend fun onEnable(context: Context) {
		locationDao = AppDatabase.database(context).locationDao()
		scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
		scheduledFlushJob = null
	}
}

