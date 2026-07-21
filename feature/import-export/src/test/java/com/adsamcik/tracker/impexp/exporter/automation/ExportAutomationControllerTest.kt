package com.adsamcik.tracker.impexp.exporter.automation

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.adsamcik.tracker.shared.base.concurrency.TestDispatchersProvider
import com.adsamcik.tracker.shared.base.time.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExportAutomationControllerTest {

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
}
