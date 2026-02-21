package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PersonalRecordEntity
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class PersonalRecordDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: PersonalRecordDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.personalRecordDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	private fun createRecord(
		metric: String,
		value: Double = 100.0,
		achievedAt: Long = 1_000_000L,
		updatedAt: Long = 1_000_000L
	) = PersonalRecordEntity(
		metric = metric,
		value = value,
		achievedAt = achievedAt,
		updatedAt = updatedAt
	)

	@Test
	fun getByMetricReturnsNullForMissing()  { runTest {
		val result = dao.getByMetric("nonexistent")
		result.shouldBeNull()
	} }

	@Test
	fun upsertAndGetByMetric()  { runTest {
		val record = createRecord(metric = "max_speed", value = 25.5)
		dao.upsert(record)

		val result = dao.getByMetric("max_speed")
		result.shouldNotBeNull()
		result!!.metric shouldBe "max_speed"
		result.value shouldBe 25.5
	} }

	@Test
	fun upsertReplacesExistingMetric()  { runTest {
		dao.upsert(createRecord(metric = "max_speed", value = 20.0, updatedAt = 100L))
		dao.upsert(createRecord(metric = "max_speed", value = 30.0, updatedAt = 200L))

		val result = dao.getByMetric("max_speed")
		result.shouldNotBeNull()
		result!!.value shouldBe 30.0
		result.updatedAt shouldBe 200L
	} }

	@Test
	fun getAllReturnsAllRecords()  { runTest {
		dao.upsert(createRecord(metric = "max_speed", updatedAt = 300L))
		dao.upsert(createRecord(metric = "longest_trip", updatedAt = 200L))
		dao.upsert(createRecord(metric = "most_steps", updatedAt = 100L))

		val results = dao.getAll()
		results shouldHaveSize 3
	} }

	@Test
	fun getAllOrdersByUpdatedAtDesc()  { runTest {
		dao.upsert(createRecord(metric = "a", updatedAt = 100L))
		dao.upsert(createRecord(metric = "b", updatedAt = 300L))
		dao.upsert(createRecord(metric = "c", updatedAt = 200L))

		val results = dao.getAll()
		results[0].metric shouldBe "b"
		results[1].metric shouldBe "c"
		results[2].metric shouldBe "a"
	} }

	@Test
	fun deleteAllRemovesAllRecords()  { runTest {
		dao.upsert(createRecord(metric = "max_speed"))
		dao.upsert(createRecord(metric = "longest_trip"))

		dao.deleteAll()

		val results = dao.getAll()
		results.shouldBeEmpty()
	} }

	@Test
	fun deleteOlderThanRemovesOldRecords()  { runTest {
		dao.upsert(createRecord(metric = "old", updatedAt = 100L))
		dao.upsert(createRecord(metric = "medium", updatedAt = 500L))
		dao.upsert(createRecord(metric = "new", updatedAt = 1000L))

		val deleted = dao.deleteOlderThan(600L)
		deleted shouldBe 2

		dao.getByMetric("old").shouldBeNull()
		dao.getByMetric("medium").shouldBeNull()
		dao.getByMetric("new").shouldNotBeNull()
	} }

	@Test
	fun getByMetricReturnsNullAfterDeleteAll()  { runTest {
		dao.upsert(createRecord(metric = "max_speed"))

		dao.deleteAll()

		dao.getByMetric("max_speed").shouldBeNull()
	} }
}
