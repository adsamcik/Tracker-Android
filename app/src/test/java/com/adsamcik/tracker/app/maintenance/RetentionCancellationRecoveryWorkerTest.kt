package com.adsamcik.tracker.app.maintenance

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionCancellationRecoveryWorkerTest {
	private val context: Application = ApplicationProvider.getApplicationContext()

	@Test
	fun `transient cancellation failure retries the durable recovery owner`() = runTest {
		val scheduler = mockk<RetentionWorkScheduler> {
			coEvery { recoverCurrentPreference(enabled = false) } throws
				RetentionWorkCancellationPendingException(
					RetentionWorkCancellationDebt(
						uniqueWorkNames = setOf(RetentionPipelineWorker.WORK_NAME),
						executionIds = setOf("execution"),
						failure = RetentionWorkCancellationFailure.SnapshotUnavailable(
							IllegalStateException("transient"),
						),
					),
				)
		}
		val worker = TestListenableWorkerBuilder<RetentionCancellationRecoveryWorker>(context)
			.setWorkerFactory(object : WorkerFactory() {
				override fun createWorker(
					appContext: Context,
					workerClassName: String,
					workerParameters: WorkerParameters,
				): ListenableWorker = RetentionCancellationRecoveryWorker(
					appContext,
					workerParameters,
					scheduler,
				)
			})
			.setInputData(
				workDataOf(
					RetentionCancellationRecoveryWorker.MODE_KEY to
						RetentionCancellationRecoveryWorker.MODE_DISABLED,
				),
			)
			.build() as RetentionCancellationRecoveryWorker

		worker.doWork() shouldBe ListenableWorker.Result.retry()
		coVerify(exactly = 1) { scheduler.recoverCurrentPreference(enabled = false) }
	}
}
