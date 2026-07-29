package com.adsamcik.tracker.app.settings

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.adsamcik.tracker.activity.api.ActivityRecognitionApi
import com.adsamcik.tracker.app.maintenance.RetentionPipelineWorker
import com.adsamcik.tracker.impexp.importer.DataImporter
import com.adsamcik.tracker.impexp.exporter.automation.ExportAutomationController
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.points.event.PointsDomainEventConsumer
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.data.worker.AchievementWorker
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.resilience.PendingSignalDrainWork
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import com.adsamcik.tracker.tracker.worker.HistoricalTrajectoryReconstructionWorker
import com.adsamcik.tracker.osm.imp.OsmImportWorker
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

interface CollectedDataDeletionService {
	suspend fun deleteAll()

	suspend fun reconcilePendingDeletion()
}

interface CollectedDataWriterQuiescer {
	suspend fun quiesce()

	fun resume()
}

class DefaultCollectedDataWriterQuiescer(
	private val context: Context,
	private val trackerStateReader: TrackerStateReader,
	private val activityWatcherController: ActivityWatcherController,
	private val exportAutomationController: ExportAutomationController,
) : CollectedDataWriterQuiescer {
	private val workManager = WorkManager.getInstance(context)
	private var restoreRetentionSchedule = false
	private var restoreDatabaseMaintenance = false

	override suspend fun quiesce() {
		restoreRetentionSchedule = hasActiveUniqueWork(RetentionPipelineWorker.WORK_NAME) ||
			hasActiveUniqueWork(RetentionPipelineWorker.LEGACY_WORK_NAME)
		restoreDatabaseMaintenance = hasActiveUniqueWork(DatabaseMaintenanceWorker.MAINTENANCE_UNIQUE_ID)
		activityWatcherController.pauseForDataDeletion()
		TrackerServiceApi.stopService(context)
		try {
			withTimeout(TRACKER_STOP_TIMEOUT_MS) {
				if (trackerStateReader.isServiceRunning) {
					trackerStateReader.isServiceRunningFlow.first { isRunning -> !isRunning }
				}
			}
			awaitCancellation(
				ActivityRecognitionApi.cancelPendingWork(context),
				"activity recognition",
			)
			awaitCancellation(
				workManager.cancelAllWorkByTag(PointsDomainEventConsumer.POINTS_WORK_TAG),
				"points",
			)
			awaitCancellation(
				workManager.cancelAllWorkByTag(AchievementWorker.TAG),
				"achievement",
			)
			awaitCancellation(
				workManager.cancelUniqueWork(DailySummaryMaterializationWorker.UNIQUE_WORK_ID),
				"daily summary",
			)
			awaitCancellation(
				workManager.cancelUniqueWork(
					HistoricalTrajectoryReconstructionWorker.UNIQUE_WORK_NAME,
				),
				"historical reconstruction",
			)
			awaitCancellation(
				workManager.cancelUniqueWork(OsmImportWorker.UNIQUE_WORK_NAME),
				"OSM import",
			)
			awaitCancellation(
				DataImporter.cancel(context),
				"data import",
			)
			awaitCancellation(
				PendingSignalDrainWork.cancel(context),
				"pending-signal recovery",
			)
			awaitCancellation(
				workManager.cancelUniqueWork(RetentionPipelineWorker.WORK_NAME),
				"data retention",
			)
			awaitCancellation(
				workManager.cancelUniqueWork(RetentionPipelineWorker.LEGACY_WORK_NAME),
				"legacy data retention",
			)
			awaitCancellation(
				DatabaseMaintenanceWorker.cancel(context),
				"database maintenance",
			)
			exportAutomationController.pauseForDataDeletion()
		} catch (error: TimeoutCancellationException) {
			throw DatabaseMigrationBackupException(
				"Timed out while stopping collected-data writers",
				error,
			)
		}
	}

	override fun resume() {
		if (restoreRetentionSchedule) {
			RetentionPipelineWorker.ensureScheduled(context)
		}
		if (restoreDatabaseMaintenance) {
			DatabaseMaintenanceWorker.schedule(context)
		}
		restoreRetentionSchedule = false
		restoreDatabaseMaintenance = false
		DailySummaryMaterializationWorker.schedule(context)
		exportAutomationController.resumeAfterDataDeletion()
		activityWatcherController.resumeAfterDataDeletion()
	}

	private fun awaitCancellation(operation: Operation, writerName: String) {
		try {
			operation.result.get()
		} catch (error: InterruptedException) {
			Thread.currentThread().interrupt()
			throw DatabaseMigrationBackupException(
				"Interrupted while stopping $writerName work",
				error,
			)
		} catch (error: ExecutionException) {
			throw DatabaseMigrationBackupException(
				"Could not stop $writerName work",
				error.cause ?: error,
			)
		}
	}

	private fun hasActiveUniqueWork(uniqueWorkName: String): Boolean = try {
		workManager.getWorkInfosForUniqueWork(uniqueWorkName).get().any { workInfo ->
			workInfo.state in ACTIVE_WORK_STATES
		}
	} catch (error: InterruptedException) {
		Thread.currentThread().interrupt()
		throw DatabaseMigrationBackupException(
			"Interrupted while checking $uniqueWorkName work",
			error,
		)
	} catch (error: ExecutionException) {
		throw DatabaseMigrationBackupException(
			"Could not check $uniqueWorkName work",
			error.cause ?: error,
		)
	}

	private companion object {
		const val TRACKER_STOP_TIMEOUT_MS = 30_000L
		val ACTIVE_WORK_STATES = setOf(
			WorkInfo.State.ENQUEUED,
			WorkInfo.State.RUNNING,
			WorkInfo.State.BLOCKED,
		)
	}
}

class DefaultCollectedDataDeletionService(
	private val context: Context,
	private val pointsAwardedDao: PointsAwardedDao,
	private val exportPlanStore: ExportPlanStore,
	private val writerQuiescer: CollectedDataWriterQuiescer,
	private val collectedDataLifecycleStore: CollectedDataLifecycleStore,
	private val appDatabaseDeletion: suspend (Context, Long, Long?, Long) -> Unit =
		{ context, epoch, retainedFromMs, updatedAtMs ->
			AppDatabase.deleteAllCollectedData(
				context = context,
				collectedDataEpoch = epoch,
				retainedFromMs = retainedFromMs,
				updatedAtMs = updatedAtMs,
			)
		},
	private val markerFile: File = File(
		context.noBackupFilesDir,
		"collected-data-deletion-pending",
	),
	private val directorySync: (File) -> Unit = ::syncDirectory,
) : CollectedDataDeletionService {
	private val deletionMutex = Mutex()

	override suspend fun deleteAll() {
		deletionMutex.withLock {
			runDeletion(writeMarker = true)
		}
	}

	override suspend fun reconcilePendingDeletion() {
		deletionMutex.withLock {
			if (!markerFile.exists()) return@withLock
			runDeletion(writeMarker = false)
		}
	}

	private suspend fun runDeletion(writeMarker: Boolean) {
		try {
			if (writeMarker) writeDeletionMarker()
			// This transition is deliberately outside collected Room rows and happens
			// before writers are stopped or the database is cleared.  Any work that
			// captured the old epoch can no longer publish after this point.
			val lifecycleUpdatedAtMs = System.currentTimeMillis()
			val lifecycle = collectedDataLifecycleStore.beginFullDeletion(lifecycleUpdatedAtMs)
			writerQuiescer.quiesce()
			performDeletion(
				epoch = lifecycle.epoch,
				retainedFromMs = lifecycle.retainedFromMs,
				updatedAtMs = lifecycleUpdatedAtMs,
			)
			exportPlanStore.resetAllWatermarks()
			clearDeletionMarker()
		} finally {
			writerQuiescer.resume()
		}
	}

	private suspend fun performDeletion(
		epoch: Long,
		retainedFromMs: Long?,
		updatedAtMs: Long,
	) {
		pointsAwardedDao.deleteAll()
		appDatabaseDeletion(context, epoch, retainedFromMs, updatedAtMs)
		RETIRED_DATABASE_NAMES.forEach(::deleteRetiredDatabase)
	}

	private fun deleteRetiredDatabase(databaseName: String) {
		context.deleteDatabase(databaseName)
		val database = context.getDatabasePath(databaseName)
		val remainingFiles = listOf(
			database,
			File("${database.path}-wal"),
			File("${database.path}-shm"),
			File("${database.path}-journal"),
		).filter(File::exists)
		if (remainingFiles.isNotEmpty()) {
			throw DatabaseMigrationBackupException(
				"Could not delete retired collected-data database $databaseName",
			)
		}
	}

	private fun writeDeletionMarker() {
		val parent = checkNotNull(markerFile.parentFile)
		if (!parent.isDirectory && !parent.mkdirs()) {
			throw DatabaseMigrationBackupException("Could not prepare collected-data deletion")
		}
		val temporary = File(parent, "${markerFile.name}.tmp")
		try {
			FileOutputStream(temporary).use { output ->
				output.write("pending".encodeToByteArray())
				output.fd.sync()
			}
			if (markerFile.exists() && !markerFile.delete()) {
				throw DatabaseMigrationBackupException(
					"Could not replace collected-data deletion marker",
				)
			}
			if (!temporary.renameTo(markerFile)) {
				throw DatabaseMigrationBackupException(
					"Could not persist collected-data deletion marker",
				)
			}
			directorySync(parent)
		} finally {
			temporary.delete()
		}
	}

	private fun clearDeletionMarker() {
		if (markerFile.exists() && !markerFile.delete()) {
			throw DatabaseMigrationBackupException(
				"Could not clear collected-data deletion marker",
			)
		}
		directorySync(checkNotNull(markerFile.parentFile))
	}

	private companion object {
		val RETIRED_DATABASE_NAMES = listOf(
			"stats_database",
			"challenge_database",
		)

		fun syncDirectory(directory: File) {
			val descriptor = Os.open(
				directory.path,
				OsConstants.O_RDONLY,
				0,
			)
			try {
				Os.fsync(descriptor)
			} finally {
				Os.close(descriptor)
			}
		}
	}
}
