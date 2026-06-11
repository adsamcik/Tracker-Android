package com.adsamcik.tracker.activity.api

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.activity.ACTIVITY_LOG_SOURCE
import com.adsamcik.tracker.activity.ActivityRecognitionWorker
import com.adsamcik.tracker.activity.logActivity
import com.adsamcik.tracker.logger.LogData

/**
 * Activity recognition API class, providing access to special activity recognition functions.
 */
object ActivityRecognitionApi {
    /**
     * Enqueues a single batch worker that fetches all unrecognized sessions
     * and processes them with O(3) queries instead of O(N*3).
     */
    fun rerunRecognitionForAll(context: Context) {
        logActivity(LogData(message = "requesting recognition rerun", source = ACTIVITY_LOG_SOURCE))
        val data = Data.Builder()
            .putBoolean(ActivityRecognitionWorker.ARG_BATCH_MODE, true)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<ActivityRecognitionWorker>()
            .addTag(ActivityRecognitionWorker.WORK_TAG)
            .setInputData(data)
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()

        WorkManager.getInstance(context).enqueue(workRequest)
    }
}
