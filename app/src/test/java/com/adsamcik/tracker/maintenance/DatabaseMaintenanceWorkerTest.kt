package com.adsamcik.tracker.maintenance

import android.content.Context
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.adsamcik.tracker.shared.base.database.AppDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
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
		private val mockDb = mockk<AppDatabase>(relaxed = true)
		private val mockStatement = mockk<SupportSQLiteStatement>(relaxed = true)
		private val sqlSlot = slot<String>()

		@BeforeEach
		fun setUp() {
			mockkObject(AppDatabase)
			every { AppDatabase.database(any()) } returns mockDb
			every { mockDb.compileStatement(capture(sqlSlot)) } returns mockStatement
		}

		private fun executeDoWork(): ListenableWorker.Result {
			return DatabaseMaintenanceWorker(context, workerParams).doWork()
		}

		@Test
		fun `returns success`() {
			executeDoWork() shouldBe ListenableWorker.Result.success()
		}

		@Test
		fun `executes delete on compiled statement`() {
			executeDoWork()
			verify(exactly = 1) { mockStatement.executeUpdateDelete() }
		}

		@Test
		fun `SQL targets tracker_session table`() {
			executeDoWork()
			sqlSlot.captured shouldContain "DELETE FROM tracker_session"
		}

		@Test
		fun `SQL removes sessions where start is at or after end`() {
			executeDoWork()
			sqlSlot.captured shouldContain "start >= `end`"
		}

		@Test
		fun `SQL removes sessions with minimal collections and steps`() {
			executeDoWork()
			sqlSlot.captured shouldContain "collections <= 1"
			sqlSlot.captured shouldContain "steps <= 10"
		}

		@Test
		fun `SQL uses OR to combine invalid session conditions`() {
			executeDoWork()
			sqlSlot.captured shouldContain "OR"
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
