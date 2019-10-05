package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.adsamcik.tracker.common.database.AppDatabase
import com.adsamcik.tracker.common.extension.tryWithReport
import java.util.concurrent.TimeUnit

class DatabaseMaintenanceWorker(
		context: Context,
		workerParams: WorkerParameters
) : Worker(context, workerParams) {

	override fun doWork(): Result {
		tryWithReport {
			val database = AppDatabase.database(applicationContext)
			val clearInvalidSessions = database.compileStatement(
					"DELETE FROM tracker_session WHERE start >= `end` OR (collections <= 1 AND steps <= 10)"
			)
			clearInvalidSessions.executeUpdateDelete()
		}
		return Result.success()
	}

	companion object {
		private const val MAINTENANCE_UNIQUE_ID = "AppDatabaseMaintenance"
		private const val REPEAT_INTERVAL_H: Long = 6L

		fun schedule(context: Context) {
			val workManager = WorkManager.getInstance(context)
			val builder = PeriodicWorkRequestBuilder<DatabaseMaintenanceWorker>(
					REPEAT_INTERVAL_H,
					TimeUnit.HOURS
			)
			workManager.enqueueUniquePeriodicWork(
					MAINTENANCE_UNIQUE_ID,
					ExistingPeriodicWorkPolicy.REPLACE,
					builder.build()
			)
		}
	}
}

