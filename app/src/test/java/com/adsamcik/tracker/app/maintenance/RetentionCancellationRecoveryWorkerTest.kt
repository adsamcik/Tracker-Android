package com.adsamcik.tracker.app.maintenance

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionConfigRead
import com.adsamcik.tracker.shared.preferences.retention.ExactApprovedRetentionConfigUnavailableReason
import com.adsamcik.tracker.shared.preferences.retention.RetentionConfigStore
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
	fun `storage unavailable remains retryable recovery debt`() = runTest {
		val retentionConfigStore = mockk<RetentionConfigStore>()
		val scheduler = mockk<RetentionWorkScheduler> {
			coEvery { recoverCurrentPreference(retentionConfigStore) } throws
				RetentionScheduleAuthorityUnavailableException(
					ExactApprovedRetentionConfigRead.Unavailable(
						ExactApprovedRetentionConfigUnavailableReason.STORAGE_UNAVAILABLE,
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
					retentionConfigStore,
				)
			})
			.build() as RetentionCancellationRecoveryWorker

		worker.doWork() shouldBe ListenableWorker.Result.retry()
	}

	@Test
	fun `stale intent and preference revision inputs cannot override current recovery`() = runTest {
		val retentionConfigStore = mockk<RetentionConfigStore>()
		val scheduler = mockk<RetentionWorkScheduler> {
			coEvery { recoverCurrentPreference(retentionConfigStore) } throws
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
		listOf("ENABLED", "DISABLED").forEach { staleMode ->
			val worker =
				TestListenableWorkerBuilder<RetentionCancellationRecoveryWorker>(context)
					.setWorkerFactory(object : WorkerFactory() {
						override fun createWorker(
							appContext: Context,
							workerClassName: String,
							workerParameters: WorkerParameters,
						): ListenableWorker = RetentionCancellationRecoveryWorker(
							appContext,
							workerParameters,
							scheduler,
							retentionConfigStore,
						)
					})
					.setInputData(
						workDataOf(
							"retention_schedule_mode" to staleMode,
							"retention_configuration_generation" to 3L,
							"retention_approval_revision" to 5L,
						),
					)
					.build() as RetentionCancellationRecoveryWorker

			worker.doWork() shouldBe ListenableWorker.Result.retry()
		}
		coVerify(exactly = 2) {
			scheduler.recoverCurrentPreference(retentionConfigStore)
		}
	}
}
