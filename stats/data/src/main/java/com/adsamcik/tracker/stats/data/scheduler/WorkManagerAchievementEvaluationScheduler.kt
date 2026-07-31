package com.adsamcik.tracker.stats.data.scheduler

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.adsamcik.tracker.stats.api.scheduler.AchievementEvaluationScheduler
import com.adsamcik.tracker.stats.data.worker.AchievementWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkManagerAchievementEvaluationScheduler @Inject constructor(
	@ApplicationContext private val context: Context,
) : AchievementEvaluationScheduler {
	override fun scheduleEvaluation() {
		val workRequest = OneTimeWorkRequestBuilder<AchievementWorker>()
			.addTag(AchievementWorker.WORK_TAG)
			.setConstraints(
				Constraints.Builder()
					.setRequiresBatteryNotLow(true)
					.build(),
			)
			.build()

		enqueue(WorkManager.getInstance(context), workRequest)
	}

	internal fun enqueue(workManager: WorkManager, workRequest: androidx.work.OneTimeWorkRequest) {
		workManager.enqueueUniqueWork(
			AchievementWorker.UNIQUE_WORK_NAME,
			ExistingWorkPolicy.KEEP,
			workRequest,
		)
	}
}
