package com.adsamcik.tracker.shared.base.database

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.tracker.shared.base.database.data.AchievementProgressEntity
import com.adsamcik.tracker.sqlite.runtime.SQLiteXSupportSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Generated Room upserts must update existing rows through the actual vendored SQLite runtime. */
@RunWith(AndroidJUnit4::class)
class SQLiteXRoomUpsertInstrumentedTest {
	private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
	@Before fun prepare() { context.deleteDatabase(NAME) }
	@After fun cleanup() { context.deleteDatabase(NAME) }

	@Test
	fun repeatedGeneratedUpsertUpdatesExistingRowsAndRetainsIndependentRowsAcrossReopen() = runBlocking<Unit> {
		val first = AchievementProgressEntity("upsert-probe", lastTierIndex = 1, lastValue = 10.0, updatedAt = 1L)
		val sibling = AchievementProgressEntity("upsert-sibling", lastValue = 3.0, updatedAt = 1L)
		val changed = first.copy(lastTierIndex = 2, lastValue = 20.0, updatedAt = 2L, lastUnlockedAt = 2L)
		val database = open()
		try {
			val dao = database.achievementProgressDao()
			dao.replaceEvaluatedAll(listOf(first, sibling))
			repeat(2) { dao.replaceEvaluatedAll(listOf(changed)) }
			assertEquals(changed, dao.getByMetric(first.metricKey))
			assertEquals(sibling, dao.getByMetric(sibling.metricKey))
			assertEquals(2, dao.getAll().size)
			database.openHelper.writableDatabase.query("PRAGMA quick_check").use { row ->
				assertEquals(true, row.moveToFirst())
				assertEquals("ok", row.getString(0))
			}
		} finally {
			database.close()
		}
		val reopened = open()
		try {
			assertEquals(changed, reopened.achievementProgressDao().getByMetric(first.metricKey))
			assertNotNull(reopened.achievementProgressDao().getByMetric(sibling.metricKey))
			assertEquals(2, reopened.achievementProgressDao().getAll().size)
		} finally {
			reopened.close()
		}
	}

	private fun open(): AppDatabase = AppDatabase.fileBuilder(context, NAME)
		.openHelperFactory(SQLiteXSupportSQLiteOpenHelperFactory(preserveDatabaseFilesOnCorruption = true)).build()

	private companion object { const val NAME = "sqlite_x_room_upsert_test.db" }
}
