package com.adsamcik.tracker.shared.base.database.migration

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class DatabaseMigrationBackupCleanupWorker(
	appContext: Context,
	workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

	override suspend fun doWork(): Result {
		return try {
			DatabaseMigrationBackupStore(
				context = applicationContext,
				scheduleExpiry = {},
				cancelExpiry = {},
			).deleteExpiredBackups()
			Result.success()
		} catch (_: DatabaseMigrationBackupException) {
			Result.retry()
		}
	}

	companion object {
		private const val UNIQUE_WORK_NAME = "database-migration-backup-expiry"

		fun schedule(context: Context, createdAtMs: Long) {
			val delayMs = (
				createdAtMs + DATABASE_MIGRATION_BACKUP_RETENTION_MS -
					System.currentTimeMillis()
				).coerceAtLeast(0L)
			val request = OneTimeWorkRequestBuilder<DatabaseMigrationBackupCleanupWorker>()
				.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
				.build()
			WorkManager.getInstance(context).enqueueUniqueWork(
				UNIQUE_WORK_NAME,
				ExistingWorkPolicy.REPLACE,
				request,
			)
		}

		fun cancel(context: Context) {
			WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
		}
	}
}
