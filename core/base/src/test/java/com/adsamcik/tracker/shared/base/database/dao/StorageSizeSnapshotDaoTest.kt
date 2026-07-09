package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.StorageSizeSnapshotEntity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageSizeSnapshotDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: StorageSizeSnapshotDao

	@Before
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.storageSizeSnapshotDao()
	}

	@After
	fun tearDown() {
		database.close()
	}

	private fun createSnapshot(
		epochDay: Long,
		databaseSizeBytes: Long = 1_000_000L,
		locationCount: Int = 100,
		sessionCount: Int = 5,
		wifiCount: Int = 50,
		cellCount: Int = 30,
		explorationCellCount: Int = 20,
		routeCacheCount: Int = 10,
		createdAt: Long = System.currentTimeMillis()
	) = StorageSizeSnapshotEntity(
		epochDay = epochDay,
		databaseSizeBytes = databaseSizeBytes,
		locationCount = locationCount,
		sessionCount = sessionCount,
		wifiCount = wifiCount,
		cellCount = cellCount,
		explorationCellCount = explorationCellCount,
		routeCacheCount = routeCacheCount,
		createdAt = createdAt
	)

	@Test
	fun getByDayReturnsNullForMissingDay()  { runTest {
		val result = dao.getByDay(19000L)
		result.shouldBeNull()
	} }

	@Test
	fun upsertAndGetByDay()  { runTest {
		val snapshot = createSnapshot(epochDay = 19000L, databaseSizeBytes = 5_000_000L)
		dao.upsert(snapshot)

		val result = dao.getByDay(19000L)
		result.shouldNotBeNull()
		result!!.epochDay shouldBe 19000L
		result.databaseSizeBytes shouldBe 5_000_000L
	} }

	@Test
	fun upsertReplacesExistingDay()  { runTest {
		dao.upsert(createSnapshot(epochDay = 19000L, databaseSizeBytes = 1_000L))
		dao.upsert(createSnapshot(epochDay = 19000L, databaseSizeBytes = 2_000L))

		val result = dao.getByDay(19000L)
		result.shouldNotBeNull()
		result!!.databaseSizeBytes shouldBe 2_000L
	} }

	@Test
	fun getRecentReturnsNewestFirst()  { runTest {
		dao.upsert(createSnapshot(epochDay = 19000L))
		dao.upsert(createSnapshot(epochDay = 19002L))
		dao.upsert(createSnapshot(epochDay = 19001L))

		val results = dao.getRecent(10)
		results shouldHaveSize 3
		results[0].epochDay shouldBe 19002L
		results[1].epochDay shouldBe 19001L
		results[2].epochDay shouldBe 19000L
	} }

	@Test
	fun getRecentRespectsLimit()  { runTest {
		dao.upsert(createSnapshot(epochDay = 19000L))
		dao.upsert(createSnapshot(epochDay = 19001L))
		dao.upsert(createSnapshot(epochDay = 19002L))

		val results = dao.getRecent(2)
		results shouldHaveSize 2
		results[0].epochDay shouldBe 19002L
		results[1].epochDay shouldBe 19001L
	} }

	@Test
	fun getRecentReturnsEmptyWhenNoData()  { runTest {
		val results = dao.getRecent(10)
		results.shouldBeEmpty()
	} }

	@Test
	fun deleteOlderThanRemovesOldSnapshots()  { runTest {
		dao.upsert(createSnapshot(epochDay = 19000L))
		dao.upsert(createSnapshot(epochDay = 19001L))
		dao.upsert(createSnapshot(epochDay = 19005L))

		dao.deleteOlderThan(19002L)

		dao.getByDay(19000L).shouldBeNull()
		dao.getByDay(19001L).shouldBeNull()
		dao.getByDay(19005L).shouldNotBeNull()
	} }

	@Test
	fun deleteAllRemovesAllSnapshots()  { runTest {
		dao.upsert(createSnapshot(epochDay = 19000L))
		dao.upsert(createSnapshot(epochDay = 19001L))

		dao.deleteAll()

		val results = dao.getRecent(10)
		results.shouldBeEmpty()
	} }
}
