package com.adsamcik.tracker.activity.api

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.activity.ACTIVITY_LOG_SOURCE
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import com.adsamcik.tracker.activity.logActivity
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.logger.LogData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Activity recognition API class, providing access to special activity recognition functions.
 */
object ActivityRecognitionApi {
	fun rerunRecognitionForAll(context: Context) {
		logActivity(LogData(message = "requesting recognition rerun", source = ACTIVITY_LOG_SOURCE))
		GlobalScope.launch(Dispatchers.Default) {
			val sessionDao = AppDatabase.database(context).sessionDao()
			val workManager = WorkManager.getInstance(context)
			sessionDao.getAll().filter { it.id < 0 }.forEach {
				val data = Data.Builder().putLong(ActivityRecognitionWorker.ARG_SESSION_ID, it.id)
						.build()
				val workRequest = OneTimeWorkRequestBuilder<ActivityRecognitionWorker>()
						.addTag(ActivityRecognitionWorker.WORK_TAG)
						.setInputData(data)
						.setConstraints(
								Constraints.Builder()
										.setRequiresBatteryNotLow(true)
										.build()
						).build()

				workManager.enqueue(workRequest)
			}
		}
	}
}
