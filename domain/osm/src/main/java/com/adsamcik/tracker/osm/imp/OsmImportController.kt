package com.adsamcik.tracker.osm.imp

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.adsamcik.tracker.shared.base.database.dao.OsmImportDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Façade over WorkManager for triggering and observing the OSM import worker.
 *
 * UI code (Phase 6 Settings screen) should depend on this controller rather
 * than [androidx.work.WorkManager] directly so future changes (queueing,
 * batching, priority, etc.) stay encapsulated.
 *
 * The work is enqueued under a unique name so a second tap on "Import" while
 * a previous import is still running REPLACES the existing job — guaranteeing
 * at most one OSM parse runs at a time.
 */
@Singleton
class OsmImportController @Inject constructor(
	@ApplicationContext private val context: Context,
	private val osmImportDao: OsmImportDao,
) {

	/** Enqueues a new OSM import. Replaces any in-flight import. */
	fun enqueue(request: OsmImportRequest) {
		val data = Data.Builder()
			.putString(OsmImportWorker.KEY_FILE_URI, request.contentUri)
			.putString(OsmImportWorker.KEY_DISPLAY_NAME, request.displayName)
			.putLong(OsmImportWorker.KEY_FILE_SIZE, request.fileSizeBytes)
			.build()
		val constraints = Constraints.Builder()
			.setRequiredNetworkType(NetworkType.NOT_REQUIRED) // strictly offline
			.setRequiresBatteryNotLow(true)
			.build()
		val work = OneTimeWorkRequestBuilder<OsmImportWorker>()
			.setInputData(data)
			.setConstraints(constraints)
			.build()
		WorkManager.getInstance(context).enqueueUniqueWork(
			OsmImportWorker.UNIQUE_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			work,
		)
	}

	/** Cancels any currently running or pending OSM import. */
	fun cancel() {
		WorkManager.getInstance(context).cancelUniqueWork(OsmImportWorker.UNIQUE_WORK_NAME)
	}

	/**
	 * Cold flow of import-job state, exposed to the UI for progress / done /
	 * failed indicators. Emits [OsmImportState.Idle] if nothing has been
	 * enqueued yet.
	 */
	fun observeImportState(): Flow<OsmImportState> {
		val flow = WorkManager.getInstance(context)
			.getWorkInfosForUniqueWorkFlow(OsmImportWorker.UNIQUE_WORK_NAME)
		return flow.map { infos -> mapToState(infos) }
	}

	/** Cold flow of currently imported regions (one row per `.osm.pbf` file). */
	fun observeImports() = osmImportDao.observeReady()

	private fun mapToState(infos: List<WorkInfo>): OsmImportState {
		val info = infos.firstOrNull() ?: return OsmImportState.Idle
		return when (info.state) {
			WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> OsmImportState.Running
			WorkInfo.State.SUCCEEDED -> OsmImportState.Success(
				wayCount = info.outputData.getLong(OsmImportWorker.KEY_OUT_WAY_COUNT, 0L),
				nodeCount = info.outputData.getLong(OsmImportWorker.KEY_OUT_NODE_COUNT, 0L),
			)
			WorkInfo.State.FAILED -> OsmImportState.Failed(
				message = info.outputData.getString(OsmImportWorker.KEY_OUT_ERROR)
					?: "Import failed",
			)
			WorkInfo.State.CANCELLED -> OsmImportState.Cancelled
			WorkInfo.State.BLOCKED -> OsmImportState.Running
		}
	}
}

/** UI-visible state of the most recent OSM import job. */
sealed interface OsmImportState {
	/** No import has been enqueued in this WorkManager instance. */
	data object Idle : OsmImportState

	/** Import is enqueued or running. */
	data object Running : OsmImportState

	/** Import completed; counts come from the worker output data. */
	data class Success(val wayCount: Long, val nodeCount: Long) : OsmImportState

	/** Import failed; message is the parser/IO error. */
	data class Failed(val message: String) : OsmImportState

	/** User cancelled the import (or it was replaced by a new request). */
	data object Cancelled : OsmImportState
}

/** Inputs needed to start an OSM import. */
data class OsmImportRequest(
	/** SAF content URI returned by the document picker. */
	val contentUri: String,
	/** User-facing region name shown in notifications and the settings list. */
	val displayName: String,
	/** Size of the file in bytes; used by the parser to fail fast on >100 MB inputs. */
	val fileSizeBytes: Long,
)
