package com.adsamcik.tracker.app.settings

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.adsamcik.tracker.activity.api.ActivityRecognitionApi
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationFailureCode
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import com.adsamcik.tracker.app.maintenance.RetentionPipelineWorker
import com.adsamcik.tracker.app.startup.TrackingStartupDeletionBarrier
import com.adsamcik.tracker.impexp.importer.DataImporter
import com.adsamcik.tracker.impexp.exporter.automation.ExportAutomationController
import com.adsamcik.tracker.impexp.exporter.automation.ExportPlanStore
import com.adsamcik.tracker.points.database.PointsAwardedDao
import com.adsamcik.tracker.points.event.PointsDomainEventConsumer
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.legacy.LEGACY_DATABASE_NAME
import com.adsamcik.tracker.shared.base.database.migration.DatabaseMigrationBackupException
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.stats.data.worker.AchievementWorker
import com.adsamcik.tracker.maintenance.DatabaseMaintenanceWorker
import com.adsamcik.tracker.tracker.api.TrackerServiceApi
import com.adsamcik.tracker.tracker.api.TrackingStopQuiescenceResult
import com.adsamcik.tracker.tracker.resilience.PendingSignalDrainWork
import com.adsamcik.tracker.tracker.controller.TrackerStateReader
import com.adsamcik.tracker.tracker.service.ActivityWatcherController
import com.adsamcik.tracker.tracker.worker.DailySummaryMaterializationWorker
import com.adsamcik.tracker.tracker.worker.HistoricalTrajectoryReconstructionWorker
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Provider

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
	private val workManager: WorkManager = WorkManager.getInstance(context),
	private val quiescenceTimeoutMs: Long = WRITER_QUIESCENCE_TIMEOUT_MS,
	private val awaitTrackerQuiescence: suspend (Context) -> TrackingStopQuiescenceResult =
		{ stopContext -> TrackerServiceApi.stopServiceAndAwaitQuiescence(stopContext) },
) : CollectedDataWriterQuiescer {
	private var restoreRetentionSchedule = false
	private var restoreDatabaseMaintenance = false

	override suspend fun quiesce() {
		try {
			withTimeout(quiescenceTimeoutMs) {
				restoreRetentionSchedule = restoreRetentionSchedule ||
					hasActiveUniqueWork(RetentionPipelineWorker.WORK_NAME) ||
					hasActiveUniqueWork(RetentionPipelineWorker.LEGACY_WORK_NAME)
				restoreDatabaseMaintenance = restoreDatabaseMaintenance ||
					hasActiveUniqueWork(DatabaseMaintenanceWorker.MAINTENANCE_UNIQUE_ID)
				activityWatcherController.pauseForDataDeletion()
				val trackerStop = awaitTrackerQuiescence(context)
				if (trackerStop != TrackingStopQuiescenceResult.HANDLED) {
					throw DatabaseMigrationBackupException(
						"Could not establish tracker writer quiescence: $trackerStop",
					)
				}
				// Presentation state is not the writer boundary. Once the durable STOP is handled it
				// may still lag briefly, so retain this wait only as UI-state cleanup.
				if (trackerStateReader.isServiceRunning) {
					trackerStateReader.isServiceRunningFlow.first { isRunning -> !isRunning }
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
					workManager.cancelAllWorkByTag(AchievementWorker.WORK_TAG),
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
			}
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

	private suspend fun awaitCancellation(operation: Operation, writerName: String) {
		try {
			operation.await()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (error: Exception) {
			throw DatabaseMigrationBackupException(
				"Could not stop $writerName work",
				error,
			)
		}
	}

	private suspend fun hasActiveUniqueWork(uniqueWorkName: String): Boolean = try {
		workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first().any { workInfo ->
			workInfo.state in ACTIVE_WORK_STATES
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (error: Exception) {
		throw DatabaseMigrationBackupException(
			"Could not check $uniqueWorkName work",
			error,
		)
	}

	private companion object {
		const val WRITER_QUIESCENCE_TIMEOUT_MS = 30_000L
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
	private val startupDeletionBarrier: TrackingStartupDeletionBarrier =
		TrackingStartupDeletionBarrier(),
	private val activityRegistrationArbiterProvider: Provider<ActivityRegistrationArbiter>? = null,
	private val automaticControlRestorer: PostDeletionAutomaticControlRestorer,
	private val traceboxDataDeletion: suspend () -> Boolean,
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
		var activityRegistrationArbiter: ActivityRegistrationArbiter? = null
		var deletionCompleted = false
		// The on-disk marker is the crash authority. Persist it before closing the in-process gate so
		// a process death at this boundary cannot forget a user-confirmed deletion request.
		if (writeMarker) writeDeletionMarker()
		startupDeletionBarrier.closeAdmission()
		try {
			// This transition is deliberately outside collected Room rows and happens
			// before writers are stopped or the database is cleared.  Any work that
			// captured the old epoch can no longer publish after this point.
			val lifecycleUpdatedAtMs = System.currentTimeMillis()
			val lifecycle = collectedDataLifecycleStore.beginFullDeletion(lifecycleUpdatedAtMs)
			if (!writeMarker) {
				// A cold-process reconciliation has no in-process writer to quiesce yet. Remove the
				// retired vaults before resolving the Activity arbiter, whose Room observer would
				// otherwise be able to open/create the v28 target before deletion wins.
				deleteRetiredDatabases()
			}
			activityRegistrationArbiter = activityRegistrationArbiterProvider?.get()
			fenceCollectedDataWriters(activityRegistrationArbiter)
			// Admission is already closed and writers/providers have now received cancellation. Only
			// after the admitted startup operation unwinds may destructive Room deletion begin. An
			// operation admitted before closeAdmission may have resumed providers while unwinding, so
			// deletion takes the final fence after the barrier reaches quiescence.
			startupDeletionBarrier.awaitQuiescence()
			fenceCollectedDataWriters(activityRegistrationArbiter)
			performDeletion(
				epoch = lifecycle.epoch,
				retainedFromMs = lifecycle.retainedFromMs,
				updatedAtMs = lifecycleUpdatedAtMs,
				removeRetiredDatabases = writeMarker,
			)
			exportPlanStore.resetAllWatermarks()
			deleteDiagnostics()
			// Enqueue the durable recovery owner while the deletion marker and process barrier still
			// fence Room/providers. It will make one attempt only after this generation is Ready.
			automaticControlRestorer.schedule(lifecycle.epoch)
			clearDeletionMarker()
			deletionCompleted = true
		} finally {
			if (deletionCompleted) {
				startupDeletionBarrier.reopen()
			}
		}
	}

	private suspend fun fenceCollectedDataWriters(
		activityRegistrationArbiter: ActivityRegistrationArbiter?,
	) {
		activityRegistrationArbiter?.closeForCollectedDataDeletion()?.let { result ->
			val cleanupIsDurablyDeferred =
				result.status == ActivityRegistrationStatus.DEGRADED &&
					result.failureCode == ActivityRegistrationFailureCode.PROVIDER_REMOVAL_FAILED &&
					result.retryable
			if (result.status != ActivityRegistrationStatus.APPLIED && !cleanupIsDurablyDeferred) {
				throw DatabaseMigrationBackupException(
					"Could not fence activity-recognition callbacks: ${result.failureCode}",
				)
			}
		}
		writerQuiescer.quiesce()
	}

	/** Completes Tracebox deletion inside the same durable, retryable deletion transaction. */
	private suspend fun deleteDiagnostics() {
		val traceboxComplete = try {
			traceboxDataDeletion()
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			throw DatabaseMigrationBackupException(
				"Tracebox diagnostic data deletion remains pending",
				error,
			)
		}
		if (!traceboxComplete) {
			throw DatabaseMigrationBackupException(
				"Tracebox diagnostic data deletion remains pending",
			)
		}
	}

	private suspend fun performDeletion(
		epoch: Long,
		retainedFromMs: Long?,
		updatedAtMs: Long,
		removeRetiredDatabases: Boolean,
	) {
		pointsAwardedDao.deleteAll()
		// A durable full-delete request must remove the v26 vault before any operation can
		// create/open v27 and trigger its one-shot import callback.
		if (removeRetiredDatabases) deleteRetiredDatabases()
		appDatabaseDeletion(context, epoch, retainedFromMs, updatedAtMs)
	}

	private fun deleteRetiredDatabases() {
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
		// The marker represents an idempotent "full deletion remains pending" obligation.
		// Preserving an existing durable marker avoids a process-death window where deleting it
		// before replacement could incorrectly make startup reconciliation believe the transaction
		// completed.
		if (markerFile.exists()) return
		val temporary = File(parent, "${markerFile.name}.tmp")
		try {
			FileOutputStream(temporary).use { output ->
				output.write("pending".encodeToByteArray())
				output.fd.sync()
			}
			if (!temporary.renameTo(markerFile) && !markerFile.exists()) {
				throw DatabaseMigrationBackupException(
					"Could not persist collected-data deletion marker",
				)
			}
			try {
				directorySync(parent)
			} catch (error: Exception) {
				throw DatabaseMigrationBackupException(
					"Could not durably persist collected-data deletion marker",
					error,
				)
			}
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
			LEGACY_DATABASE_NAME,
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
