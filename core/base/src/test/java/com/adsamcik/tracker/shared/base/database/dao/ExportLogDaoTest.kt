package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.ExportLogEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportLogDaoTest {
	private lateinit var database: AppDatabase
	private lateinit var dao: ExportLogDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.exportLogDao()
	}

	@After
	fun tearDown() { database.close() }

	@Test
	fun `period counts successful completion boundaries without counting failures partial or no data`() = runBlocking {
		for (completedAt in listOf(999L, 1_000L, 1_500L, 2_000L, 2_001L)) {
			dao.insert(ExportLogEntity(format = "JSON", scope = "DATE_RANGE", fileName = "data.json",
				fileSizeBytes = 10L, recordCount = 1, startedAt = 1L, completedAt = completedAt,
				status = "SUCCESS", createdAt = completedAt))
		}
		for (status in listOf("FAILED", "PARTIAL", "NO_DATA")) {
			dao.insert(ExportLogEntity(format = "JSON", scope = "DATE_RANGE", fileName = "",
				fileSizeBytes = 0L, recordCount = 0, startedAt = 1L, completedAt = 1_500L,
				status = status, createdAt = 1_500L))
		}
		assertEquals(3L, dao.countCompletedBetween(1_000L, 2_000L))
		assertEquals(1L, dao.countCompletedBetween(1_000L, 1_000L))
		assertEquals(0L, dao.countCompletedBetween(3_000L, 4_000L))
		assertEquals(8L, dao.countTotal())
	}
}
