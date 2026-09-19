package com.adsamcik.tracker.app.maintenance

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.RetentionFloorDestructivePlan
import com.adsamcik.tracker.shared.base.database.RetentionWorkCancellationTarget
import com.adsamcik.tracker.shared.base.database.RetentionWorkExecutionStartResult
import com.adsamcik.tracker.shared.base.database.beginOrResumeRetentionWorkExecution
import com.adsamcik.tracker.shared.base.database.requestRetentionWorkExecutionCancellations
import com.google.common.util.concurrent.SettableFuture
import io.kotest.matchers.shouldBe
import io.mockk.capture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.TimeUnit
import java.util.UUID
import javax.inject.Provider
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetentionWorkSchedulerTest {
	private lateinit var context: Application
	private lateinit var database: AppDatabase
	private lateinit var workManager: WorkManager
	private lateinit var scheduler: RetentionWorkScheduler

	@Before
	fun setUp() {
		context = ApplicationProvider.getApplicationContext()
		WorkManagerTestInitHelper.initializeTestWorkManager(
			context,
			Configuration.Builder()
				.setExecutor(SynchronousExecutor())
				.setTaskExecutor(SynchronousExecutor())
				.build(),
		)
		database = AppDatabase.testDatabase(context)
		workManager = WorkManager.getInstance(context)
		scheduler = RetentionWorkScheduler(Provider { database }, workManager)
	}

	@After
	fun tearDown() {
		workManager.cancelAllWork().result.get()
		database.close()
	}

	@Test
	fun `pipeline migration abandons legacy receipt before replacing its schedule`() = runTest {
		val legacyRequest = OneTimeWorkRequestBuilder<NoOpWorker>()
			.setInitialDelay(1, TimeUnit.DAYS)
			.build()
		workManager.enqueueUniqueWork(
			RetentionPipelineWorker.LEGACY_WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			legacyRequest,
		).result.get()
		val legacyExecution = (
			database.beginOrResumeRetentionWorkExecution(
				workRequestId = legacyRequest.id.toString(),
				workerKind = RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
				runAttemptCount = 0,
				startedAtMs = 1_000L,
			) as RetentionWorkExecutionStartResult.Open
		).receipt

		scheduler.ensureScheduled()

		database.retentionWorkExecutionReceiptDao().get(legacyExecution.executionId)?.state shouldBe
			"ABANDONED"
		workManager.getWorkInfoById(legacyRequest.id).get()?.state shouldBe WorkInfo.State.CANCELLED
		workManager.getWorkInfosForUniqueWork(RetentionPipelineWorker.WORK_NAME).get()
			.any { it.state == WorkInfo.State.ENQUEUED } shouldBe true
	}

	@Test
	fun `cancellation API failure keeps durable cancellation requested and never schedules takeover`() =
		runTest {
			val requestId = UUID.randomUUID()
			val info = mockk<WorkInfo> {
				every { id } returns requestId
				every { state } returns WorkInfo.State.RUNNING
			}
			val cancellation = mockk<Operation>()
			val failed = SettableFuture.create<Operation.State.SUCCESS>()
			failed.setException(IllegalStateException("cancellation unavailable"))
			every { cancellation.result } returns failed
			val recoveryEnqueue = successfulOperation()
			val failingWorkManager = mockk<WorkManager> {
				every {
					getWorkInfosForUniqueWorkFlow(RetentionPipelineWorker.LEGACY_WORK_NAME)
				} returns flowOf(listOf(info))
				every { cancelWorkById(requestId) } returns cancellation
				every {
					enqueueUniqueWork(
						RetentionCancellationRecoveryWorker.UNIQUE_WORK_NAME,
						ExistingWorkPolicy.REPLACE,
						any<OneTimeWorkRequest>(),
					)
				} returns recoveryEnqueue
			}
			val failingScheduler = RetentionWorkScheduler(
				Provider { database },
				failingWorkManager,
			)

			val failure = assertFailsWith<RetentionWorkCancellationPendingException> {
				failingScheduler.ensureScheduled()
			}

			val cancellationFailure =
				failure.debt.failure as RetentionWorkCancellationFailure.CancellationApiFailed
			cancellationFailure.workRequestId shouldBe requestId.toString()
			database.retentionWorkExecutionReceiptDao().latest(requestId.toString())?.state shouldBe
				"CANCELLATION_REQUESTED"
			database.beginOrResumeRetentionWorkExecution(
				workRequestId = requestId.toString(),
				workerKind = RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
				runAttemptCount = 0,
				startedAtMs = System.currentTimeMillis(),
			) shouldBe RetentionWorkExecutionStartResult.CancellationRequested
			verify(exactly = 0) {
				failingWorkManager.enqueueUniquePeriodicWork(
					RetentionPipelineWorker.WORK_NAME,
					any(),
					any(),
				)
			}
			verify(exactly = 1) {
				failingWorkManager.enqueueUniqueWork(
					RetentionCancellationRecoveryWorker.UNIQUE_WORK_NAME,
					ExistingWorkPolicy.REPLACE,
					any<OneTimeWorkRequest>(),
				)
			}
		}

	@Test
	fun `failed cancellation confirmation keeps durable debt and never schedules takeover`() =
		runTest {
			val requestId = UUID.randomUUID()
			val info = mockk<WorkInfo> {
				every { id } returns requestId
				every { state } returns WorkInfo.State.RUNNING
			}
			val cancellation = mockk<Operation>()
			val completed = SettableFuture.create<Operation.State.SUCCESS>()
			completed.set(mockk())
			every { cancellation.result } returns completed
			val recoveryEnqueue = successfulOperation()
			val unconfirmedWorkManager = mockk<WorkManager> {
				every {
					getWorkInfosForUniqueWorkFlow(RetentionPipelineWorker.LEGACY_WORK_NAME)
				} returns flowOf(listOf(info))
				every { cancelWorkById(requestId) } returns cancellation
				every {
					enqueueUniqueWork(
						RetentionCancellationRecoveryWorker.UNIQUE_WORK_NAME,
						ExistingWorkPolicy.REPLACE,
						any<OneTimeWorkRequest>(),
					)
				} returns recoveryEnqueue
			}
			val unconfirmedScheduler = RetentionWorkScheduler(
				Provider { database },
				unconfirmedWorkManager,
			)

			val failure = assertFailsWith<RetentionWorkCancellationPendingException> {
				unconfirmedScheduler.ensureScheduled()
			}

			(failure.debt.failure is
				RetentionWorkCancellationFailure.ConfirmationUnavailable) shouldBe true
			database.retentionWorkExecutionReceiptDao().latest(requestId.toString())?.state shouldBe
				"CANCELLATION_REQUESTED"
			verify(exactly = 0) {
				unconfirmedWorkManager.enqueueUniquePeriodicWork(
					RetentionPipelineWorker.WORK_NAME,
					any(),
					any(),
				)
			}
			verify(exactly = 1) {
				unconfirmedWorkManager.enqueueUniqueWork(
					RetentionCancellationRecoveryWorker.UNIQUE_WORK_NAME,
					ExistingWorkPolicy.REPLACE,
					any<OneTimeWorkRequest>(),
				)
			}
		}

	@Test
	fun `scheduler restart confirms previously requested legacy cancellation before current enqueue`() =
		runTest {
			val legacyRequest = OneTimeWorkRequestBuilder<NoOpWorker>()
				.setInitialDelay(1, TimeUnit.DAYS)
				.build()
			workManager.enqueueUniqueWork(
				RetentionPipelineWorker.LEGACY_WORK_NAME,
				ExistingWorkPolicy.REPLACE,
				legacyRequest,
			).result.get()
			val legacyExecution = (
				database.beginOrResumeRetentionWorkExecution(
					workRequestId = legacyRequest.id.toString(),
					workerKind = RetentionFloorDestructivePlan.WORKER_DATA_RETENTION,
					runAttemptCount = 0,
					startedAtMs = 2_000L,
				) as RetentionWorkExecutionStartResult.Open
			).receipt
			database.requestRetentionWorkExecutionCancellations(
				targets = listOf(
					RetentionWorkCancellationTarget(
						legacyExecution.workRequestId,
						legacyExecution.workerKind,
					),
				),
				activeWorkRequestIds = listOf(legacyExecution.workRequestId),
				workerKinds = listOf(legacyExecution.workerKind),
				requestedAtMs = 2_100L,
			)
			database.retentionWorkExecutionReceiptDao().get(legacyExecution.executionId)?.state shouldBe
				"CANCELLATION_REQUESTED"

			RetentionWorkScheduler(Provider { database }, workManager).ensureScheduled()

			database.retentionWorkExecutionReceiptDao().get(legacyExecution.executionId)?.state shouldBe
				"ABANDONED"
			workManager.getWorkInfoById(legacyRequest.id).get()?.state shouldBe
				WorkInfo.State.CANCELLED
			workManager.getWorkInfosForUniqueWork(RetentionPipelineWorker.WORK_NAME).get()
				.any { it.state == WorkInfo.State.ENQUEUED } shouldBe true
		}

	@Test
	fun `re-enable cancels exact active pipeline debt before scheduling replacement`() = runTest {
		val cancelledRequest = OneTimeWorkRequestBuilder<NoOpWorker>()
			.setInitialDelay(1, TimeUnit.DAYS)
			.build()
		workManager.enqueueUniqueWork(
			RetentionPipelineWorker.WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			cancelledRequest,
		).result.get()
		val execution = (
			database.beginOrResumeRetentionWorkExecution(
				workRequestId = cancelledRequest.id.toString(),
				workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
				runAttemptCount = 0,
				startedAtMs = 3_000L,
			) as RetentionWorkExecutionStartResult.Open
		).receipt
		database.requestRetentionWorkExecutionCancellations(
			targets = listOf(
				RetentionWorkCancellationTarget(
					execution.workRequestId,
					execution.workerKind,
				),
			),
			activeWorkRequestIds = listOf(execution.workRequestId),
			workerKinds = listOf(execution.workerKind),
			requestedAtMs = 3_100L,
		)

		RetentionWorkScheduler(Provider { database }, workManager).ensureScheduled()

		database.retentionWorkExecutionReceiptDao().get(execution.executionId)?.state shouldBe
			"ABANDONED"
		workManager.getWorkInfoById(cancelledRequest.id).get()?.state shouldBe
			WorkInfo.State.CANCELLED
		workManager.getWorkInfosForUniqueWork(RetentionPipelineWorker.WORK_NAME).get()
			.any { it.id != cancelledRequest.id && it.state == WorkInfo.State.ENQUEUED } shouldBe true
	}

	@Test
	fun `re-enable confirms terminal pipeline debt before scheduling replacement`() = runTest {
		val terminalRequest = OneTimeWorkRequestBuilder<NoOpWorker>()
			.setInitialDelay(1, TimeUnit.DAYS)
			.build()
		workManager.enqueueUniqueWork(
			RetentionPipelineWorker.WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			terminalRequest,
		).result.get()
		workManager.cancelWorkById(terminalRequest.id).result.get()
		workManager.getWorkInfoById(terminalRequest.id).get()?.state shouldBe WorkInfo.State.CANCELLED
		val execution = (
			database.beginOrResumeRetentionWorkExecution(
				workRequestId = terminalRequest.id.toString(),
				workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
				runAttemptCount = 0,
				startedAtMs = 4_000L,
			) as RetentionWorkExecutionStartResult.Open
		).receipt
		database.requestRetentionWorkExecutionCancellations(
			targets = listOf(
				RetentionWorkCancellationTarget(
					execution.workRequestId,
					execution.workerKind,
				),
			),
			activeWorkRequestIds = emptyList(),
			workerKinds = listOf(execution.workerKind),
			requestedAtMs = 4_100L,
		)

		RetentionWorkScheduler(Provider { database }, workManager).ensureScheduled()

		database.retentionWorkExecutionReceiptDao().get(execution.executionId)?.state shouldBe
			"ABANDONED"
		workManager.getWorkInfoById(terminalRequest.id).get()?.state shouldBe WorkInfo.State.CANCELLED
		workManager.getWorkInfosForUniqueWork(RetentionPipelineWorker.WORK_NAME).get()
			.any { it.id != terminalRequest.id && it.state == WorkInfo.State.ENQUEUED } shouldBe true
	}

	@Test
	fun `re-enable never cancels healthy current generation unrelated to terminal debt`() = runTest {
		val healthyRequest = OneTimeWorkRequestBuilder<NoOpWorker>()
			.setInitialDelay(1, TimeUnit.DAYS)
			.build()
		workManager.enqueueUniqueWork(
			RetentionPipelineWorker.WORK_NAME,
			ExistingWorkPolicy.REPLACE,
			healthyRequest,
		).result.get()
		val terminalDebtRequestId = UUID.randomUUID().toString()
		val debt = (
			database.beginOrResumeRetentionWorkExecution(
				workRequestId = terminalDebtRequestId,
				workerKind = RetentionFloorDestructivePlan.WORKER_RETENTION_PIPELINE,
				runAttemptCount = 0,
				startedAtMs = 5_000L,
			) as RetentionWorkExecutionStartResult.Open
		).receipt
		database.requestRetentionWorkExecutionCancellations(
			targets = listOf(
				RetentionWorkCancellationTarget(debt.workRequestId, debt.workerKind),
			),
			activeWorkRequestIds = emptyList(),
			workerKinds = listOf(debt.workerKind),
			requestedAtMs = 5_100L,
		)

		RetentionWorkScheduler(Provider { database }, workManager).ensureScheduled()

		database.retentionWorkExecutionReceiptDao().get(debt.executionId)?.state shouldBe "ABANDONED"
		workManager.getWorkInfoById(healthyRequest.id).get()?.state shouldBe WorkInfo.State.ENQUEUED
		workManager.getWorkInfosForUniqueWork(RetentionPipelineWorker.WORK_NAME).get()
			.count { it.state == WorkInfo.State.ENQUEUED } shouldBe 1
	}

	@Test
	fun `recovery enqueue is unique latest-intent with bounded exponential backoff`() = runTest {
		val requestId = UUID.randomUUID()
		val info = mockk<WorkInfo> {
			every { id } returns requestId
			every { state } returns WorkInfo.State.RUNNING
		}
		val cancellation = mockk<Operation>()
		val failed = SettableFuture.create<Operation.State.SUCCESS>()
		failed.setException(IllegalStateException("transient cancellation failure"))
		every { cancellation.result } returns failed
		val recoveryRequests = mutableListOf<OneTimeWorkRequest>()
		val recoveryEnqueue = successfulOperation()
		val failingWorkManager = mockk<WorkManager> {
			every {
				getWorkInfosForUniqueWorkFlow(RetentionPipelineWorker.LEGACY_WORK_NAME)
			} returns flowOf(listOf(info))
			every { cancelWorkById(requestId) } returns cancellation
			every {
				enqueueUniqueWork(
					RetentionCancellationRecoveryWorker.UNIQUE_WORK_NAME,
					ExistingWorkPolicy.REPLACE,
					capture(recoveryRequests),
				)
			} returns recoveryEnqueue
		}
		val subject = RetentionWorkScheduler(Provider { database }, failingWorkManager)

		assertFailsWith<RetentionWorkCancellationPendingException> {
			subject.ensureScheduled()
		}
		assertFailsWith<RetentionWorkCancellationPendingException> {
			subject.ensureScheduled()
		}

		recoveryRequests.size shouldBe 2
		recoveryRequests.forEach { request ->
			request.workSpec.backoffPolicy shouldBe BackoffPolicy.EXPONENTIAL
			request.workSpec.backoffDelayDuration shouldBe
				TimeUnit.SECONDS.toMillis(RetentionWorkScheduler.RECOVERY_BACKOFF_SECONDS)
			request.workSpec.input.getString(RetentionCancellationRecoveryWorker.MODE_KEY) shouldBe
				RetentionCancellationRecoveryWorker.MODE_ENABLED
		}
		verify(exactly = 2) {
			failingWorkManager.enqueueUniqueWork(
				RetentionCancellationRecoveryWorker.UNIQUE_WORK_NAME,
				ExistingWorkPolicy.REPLACE,
				any<OneTimeWorkRequest>(),
			)
		}
	}

	@Test
	fun `failed quiescence replaces stale recovery with exact disabled intent`() = runTest {
		val requestId = UUID.randomUUID()
		val info = mockk<WorkInfo> {
			every { id } returns requestId
			every { state } returns WorkInfo.State.RUNNING
		}
		val cancellation = mockk<Operation>()
		val failed = SettableFuture.create<Operation.State.SUCCESS>()
		failed.setException(IllegalStateException("transient cancellation failure"))
		every { cancellation.result } returns failed
		val recoveryRequests = mutableListOf<OneTimeWorkRequest>()
		val failingWorkManager = mockk<WorkManager> {
			every {
				getWorkInfosForUniqueWorkFlow(RetentionPipelineWorker.WORK_NAME)
			} returns flowOf(listOf(info))
			every {
				getWorkInfosForUniqueWorkFlow(RetentionPipelineWorker.LEGACY_WORK_NAME)
			} returns flowOf(emptyList())
			every { cancelWorkById(requestId) } returns cancellation
			every {
				enqueueUniqueWork(
					RetentionCancellationRecoveryWorker.UNIQUE_WORK_NAME,
					ExistingWorkPolicy.REPLACE,
					capture(recoveryRequests),
				)
			} returns successfulOperation()
		}

		assertFailsWith<RetentionWorkCancellationPendingException> {
			RetentionWorkScheduler(Provider { database }, failingWorkManager).cancel()
		}

		recoveryRequests.single().workSpec.input.getString(
			RetentionCancellationRecoveryWorker.MODE_KEY,
		) shouldBe RetentionCancellationRecoveryWorker.MODE_DISABLED
	}

	private fun successfulOperation(): Operation {
		val completed = SettableFuture.create<Operation.State.SUCCESS>()
		completed.set(mockk())
		return mockk {
			every { result } returns completed
		}
	}

	class NoOpWorker(
		context: Context,
		params: WorkerParameters,
	) : Worker(context, params) {
		override fun doWork(): Result = Result.success()
	}
}
