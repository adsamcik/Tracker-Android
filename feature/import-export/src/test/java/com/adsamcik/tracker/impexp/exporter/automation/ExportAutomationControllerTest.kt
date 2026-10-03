package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.time.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.kotest.matchers.shouldBe
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExportAutomationControllerTest {

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `pause fences delayed interval and after-session enqueue at actual WorkManager calls`() = runTest {
		val taskIo = QueuedDispatcher()
		val plans = MutableStateFlow(listOf(plan(1L, ExportCadence.Interval(ExportCadence.IntervalUnit.DAY)),
			plan(2L, ExportCadence.AfterSession)))
		val store = mockk<ExportPlanStore>()
		every { store.plans } returns plans
		val manager = mockk<WorkManager>(relaxed = true)
		val cancelled = mockk<Operation> {
			every { result } returns Futures.immediateFuture(Operation.SUCCESS)
		}
		every { manager.cancelAllWorkByTag("export.plan") } returns cancelled
		val controller = ExportAutomationController(mockk(relaxed = true), store,
			object : com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider {
				override val io: CoroutineDispatcher = taskIo
				override val main: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
				override val default: CoroutineDispatcher = main
				override val unconfined: CoroutineDispatcher = main
			}, zeroClock(), backgroundScope, manager)
		runCurrent() // Observer captures admission; actual I/O scheduling remains queued.
		controller.triggerAfterSessionPlans()
		val pause = async(start = CoroutineStart.UNDISPATCHED) { controller.pauseForDataDeletion() }
		taskIo.runPending()
		runCurrent()
		pause.await()
		verify(exactly = 0) { manager.enqueueUniquePeriodicWork(any(), any(), any<PeriodicWorkRequest>()) }
		verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
		controller.triggerAfterSessionPlans()
		plans.value = plans.value.map { it.copy(name = "Changed while paused") }
		runCurrent()
		taskIo.runPending()
		verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
		controller.resumeAfterDataDeletion()
		controller.triggerAfterSessionPlans()
		taskIo.runPending()
		verify(exactly = 1) { manager.enqueueUniquePeriodicWork(any(), any(), any<PeriodicWorkRequest>()) }
		verify(exactly = 1) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `failed WorkManager cancellation retains paused scheduling until recovery resumes`() = runTest {
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val store = mockk<ExportPlanStore> { every { plans } returns flowOf(listOf(plan(2L, ExportCadence.AfterSession))) }
		val manager = mockk<WorkManager>(relaxed = true)
		every { manager.cancelAllWorkByTag("export.plan") } throws IllegalStateException("Work database busy")
		val controller = ExportAutomationController(mockk(relaxed = true), store,
			TestDispatchersProvider(dispatcher), zeroClock(), this, manager)
		runCurrent()
		runCatching { controller.pauseForDataDeletion() }.isFailure shouldBe true
		controller.triggerAfterSessionPlans()
		runCurrent()
		verify(exactly = 0) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
		controller.resumeAfterDataDeletion()
		controller.triggerAfterSessionPlans()
		runCurrent()
		verify(exactly = 1) { manager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
	}

	@OptIn(ExperimentalCoroutinesApi::class)
	@Test
	fun `after-session trigger serializes unique work keyed by plan id`() = runTest {
		val dispatcher = UnconfinedTestDispatcher(testScheduler)
		val plan = ExportBackupPlan(
			id = ExportPlanId(7L),
			name = "After session",
			format = ExportFormat.JSON,
			cadence = ExportCadence.AfterSession,
			scope = ExportScope.EntireHistory,
			destination = ExportDestination.PrivateStorage(),
			enabled = true,
			createdAtMillis = 1L,
			updatedAtMillis = 1L,
		)
		val planStore = mockk<ExportPlanStore>()
		every { planStore.plans } returns flowOf(listOf(plan))
		val workManager = mockk<WorkManager>(relaxed = true)

		val controller = ExportAutomationController(
			context = mockk<Context>(relaxed = true),
			planStore = planStore,
			dispatchers = TestDispatchersProvider(dispatcher),
			clock = object : Clock {
				override fun currentTimeMillis(): Long = 0L
				override fun elapsedRealtimeNanos(): Long = 0L
			},
			scope = this,
			workManager = workManager,
		)
		runCurrent()
		controller.triggerAfterSessionPlans()
		runCurrent()

		verify(exactly = 1) {
			workManager.enqueueUniqueWork(
				"export-plan-7",
				ExistingWorkPolicy.APPEND_OR_REPLACE,
				any<OneTimeWorkRequest>(),
			)
		}
	}

	private fun plan(id: Long, cadence: ExportCadence) = ExportBackupPlan(
		ExportPlanId(id), "Plan", ExportFormat.JSON, cadence, ExportScope.EntireHistory,
		ExportDestination.PrivateStorage(), true, 1L, 1L,
	)
	private fun zeroClock() = object : Clock {
		override fun currentTimeMillis() = 0L
		override fun elapsedRealtimeNanos() = 0L
	}
	private class QueuedDispatcher : CoroutineDispatcher() {
		private val pending = ArrayDeque<Runnable>()
		override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
		fun runPending() { while (pending.isNotEmpty()) pending.removeFirst().run() }
	}
}
