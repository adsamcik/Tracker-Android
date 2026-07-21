package com.adsamcik.tracker.stats.data.scheduler

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.adsamcik.tracker.stats.data.worker.AchievementWorker
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class WorkManagerAchievementEvaluationSchedulerTest {

	@Test
	fun replacementScheduling_doesNotCancelActiveEvaluation() {
		val workManager = mockk<WorkManager>(relaxed = true)
		val workRequest = mockk<OneTimeWorkRequest>()
		val scheduler = WorkManagerAchievementEvaluationScheduler(mockk<Context>(relaxed = true))

		scheduler.enqueue(workManager, workRequest)

		verify(exactly = 1) {
			workManager.enqueueUniqueWork(
				AchievementWorker.UNIQUE_WORK_NAME,
				ExistingWorkPolicy.KEEP,
				workRequest,
			)
		}
	}
}
