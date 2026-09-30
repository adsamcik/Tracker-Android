package com.adsamcik.tracker.tracker.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportAttemptResult
import com.adsamcik.tracker.tracker.source.ambient.steps.AmbientStepsImportRunner
import com.google.common.util.concurrent.SettableFuture
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmbientStepsImportWorkerTest {
	@Test
	fun `hourly schedule keeps its cadence through settings changes and requests one enable copy`() = runTest {
		val workManager = mockk<WorkManager>()
		val periodic = slot<PeriodicWorkRequest>()
		val immediate = slot<OneTimeWorkRequest>()
		every {
			workManager.enqueueUniquePeriodicWork(
				AmbientStepsImportWorkScheduler.PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, capture(periodic),
			)
		} returns successfulOperation()
		every {
			workManager.enqueueUniqueWork(
				AmbientStepsImportWorkScheduler.IMMEDIATE_WORK, ExistingWorkPolicy.KEEP, capture(immediate),
			)
		} returns successfulOperation()
		val scheduler = AmbientStepsImportWorkScheduler(workManager)

		scheduler.reconcile(backgroundAllowed = true, requestNow = true)
		scheduler.reconcile(backgroundAllowed = true, requestNow = false)

		periodic.captured.workSpec.intervalDuration shouldBe TimeUnit.HOURS.toMillis(1)
		periodic.captured.workSpec.workerClassName shouldBe AmbientStepsImportWorker::class.java.name
		immediate.captured.workSpec.workerClassName shouldBe AmbientStepsImportWorker::class.java.name
		verify(exactly = 2) {
			workManager.enqueueUniquePeriodicWork(any(), ExistingPeriodicWorkPolicy.KEEP, any())
		}
		verify(exactly = 1) { workManager.enqueueUniqueWork(any(), ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
	}

	@Test
	fun `foreground only provider cancels hourly reads but keeps an explicit foreground copy`() = runTest {
		val workManager = mockk<WorkManager>()
		every { workManager.cancelUniqueWork(any()) } returns successfulOperation()
		every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns successfulOperation()
		val scheduler = AmbientStepsImportWorkScheduler(workManager)

		scheduler.reconcile(backgroundAllowed = false, requestNow = true)

		verify(exactly = 1) { workManager.cancelUniqueWork(AmbientStepsImportWorkScheduler.PERIODIC_WORK) }
		verify(exactly = 0) { workManager.enqueueUniquePeriodicWork(any(), any(), any()) }
		verify(exactly = 1) {
			workManager.enqueueUniqueWork(AmbientStepsImportWorkScheduler.IMMEDIATE_WORK,
				ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>())
		}
	}

	@Test
	fun `source stop and full clear cancel both pending copy paths`() = runTest {
		val workManager = mockk<WorkManager>()
		every { workManager.cancelUniqueWork(any()) } returns successfulOperation()

		AmbientStepsImportWorkScheduler(workManager).cancel()

		verify(exactly = 1) { workManager.cancelUniqueWork(AmbientStepsImportWorkScheduler.PERIODIC_WORK) }
		verify(exactly = 1) { workManager.cancelUniqueWork(AmbientStepsImportWorkScheduler.IMMEDIATE_WORK) }
	}

	@Test
	fun `permission or missing provider does not create a continuous retry loop`() = runTest {
		listOf(AmbientStepsImportAttemptResult.COMPLETE, AmbientStepsImportAttemptResult.DEFERRED).forEach { result ->
			worker(result).doWork() shouldBe ListenableWorker.Result.success()
		}
	}

	@Test
	fun `temporary failure retries twice then leaves durable progress for the next hourly or open copy`() = runTest {
		worker(AmbientStepsImportAttemptResult.RETRY, attempts = 0).doWork() shouldBe ListenableWorker.Result.retry()
		worker(AmbientStepsImportAttemptResult.RETRY, attempts = 1).doWork() shouldBe ListenableWorker.Result.retry()
		worker(AmbientStepsImportAttemptResult.RETRY, attempts = 2).doWork() shouldBe ListenableWorker.Result.success()
	}

	@Test
	fun `worker cancellation is not converted into success or retry`() = runTest {
		val runner = mockk<AmbientStepsImportRunner>()
		coEvery { runner.run() } throws CancellationException("stopped")
		var cancelled = false
		try {
			AmbientStepsImportWorker(context(), mockk(relaxed = true), runner).doWork()
		} catch (_: CancellationException) {
			cancelled = true
		}
		cancelled shouldBe true
	}

	private fun worker(result: AmbientStepsImportAttemptResult, attempts: Int = 0): AmbientStepsImportWorker {
		val runner = mockk<AmbientStepsImportRunner>()
		coEvery { runner.run() } returns result
		val parameters = mockk<WorkerParameters>(relaxed = true)
		every { parameters.runAttemptCount } returns attempts
		return AmbientStepsImportWorker(context(), parameters, runner)
	}

	private fun context(): Context = ApplicationProvider.getApplicationContext()

	private fun successfulOperation(): Operation = mockk {
		every { result } returns SettableFuture.create<Operation.State.SUCCESS>().apply { set(Operation.SUCCESS) }
	}
}
