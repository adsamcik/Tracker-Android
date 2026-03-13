package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.dao.SessionSegmentDao
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.coVerify
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DatabaseMaintenanceWorkerTest {

	@AfterEach
	fun tearDown() {
		unmockkAll()
	}

	@Nested
	inner class DoWork {

		private val appContext = mockk<Context>(relaxed = true)
		private val context = mockk<Context>(relaxed = true) {
			every { applicationContext } returns appContext
		}
		private val workerParams = mockk<WorkerParameters>(relaxed = true)
		private val sessionSegmentDao = mockk<SessionSegmentDao>(relaxed = true)

		private fun executeDoWork(): ListenableWorker.Result {
			return runBlocking {
				DatabaseMaintenanceWorker(context, workerParams, sessionSegmentDao).doWork()
			}
		}

		@Test
		fun `returns success`() {
			executeDoWork() shouldBe ListenableWorker.Result.success()
		}

		@Test
		fun `deletes empty session segments through dao`() {
			executeDoWork()
			coVerify(exactly = 1) { sessionSegmentDao.deleteEmpty() }
		}
	}

	@Disabled("WorkManager.getInstance static mock causes AbstractMethodError with MockK")
	@Nested
	inner class Schedule {

		private val context = mockk<Context>(relaxed = true)
		private val mockWorkManager = mockk<WorkManager>(relaxed = true)

		@BeforeEach
		fun setUp() {
			mockkStatic("androidx.work.WorkManager")
			every { WorkManager.getInstance(any()) } returns mockWorkManager
		}

		@Test
		fun `enqueues unique periodic work with maintenance id`() {
			DatabaseMaintenanceWorker.schedule(context)

			verify {
				mockWorkManager.enqueueUniquePeriodicWork(
					eq("AppDatabaseMaintenance"),
					any(),
					any<PeriodicWorkRequest>()
				)
			}
		}

		@Test
		fun `uses UPDATE policy for existing periodic work`() {
			DatabaseMaintenanceWorker.schedule(context)

			verify {
				mockWorkManager.enqueueUniquePeriodicWork(
					any(),
					eq(ExistingPeriodicWorkPolicy.UPDATE),
					any<PeriodicWorkRequest>()
				)
			}
		}
	}
}
