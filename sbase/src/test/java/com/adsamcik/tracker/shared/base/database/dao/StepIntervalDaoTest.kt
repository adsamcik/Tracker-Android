package com.adsamcik.tracker.shared.base.database.dao

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.StepInterval
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.robolectric.annotation.Config
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
@Config(sdk = [34])
class StepIntervalDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: StepIntervalDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.stepIntervalDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	private fun createInterval(
		startTimeMs: Long,
		endTimeMs: Long,
		stepCount: Int = 100,
		sensorValueStart: Int = 1000,
		sensorValueEnd: Int = 1100,
		sensorReset: Boolean = false,
		createdAt: Long = System.currentTimeMillis()
	) = StepInterval(
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		stepCount = stepCount,
		sensorValueStart = sensorValueStart,
		sensorValueEnd = sensorValueEnd,
		sensorReset = sensorReset,
		createdAt = createdAt
	)

	@Test
	fun getAllBetweenReturnsEmptyForNoData()  { runTest {
		val result = dao.getAllBetweenFlow(0L, 100_000L).first()
		result.shouldBeEmpty()
	} }

	@Test
	fun insertAndGetAllBetween()  { runTest {
		val interval = createInterval(startTimeMs = 1000L, endTimeMs = 2000L, stepCount = 50)
		dao.insert(interval)

		val results = dao.getAllBetweenFlow(0L, 3000L).first()
		results shouldHaveSize 1
		results[0].stepCount shouldBe 50
		results[0].startTimeMs shouldBe 1000L
	} }

	@Test
	fun getAllBetweenFiltersOutOfRange()  { runTest {
		dao.insert(createInterval(startTimeMs = 1000L, endTimeMs = 2000L))
		dao.insert(createInterval(startTimeMs = 3000L, endTimeMs = 4000L))
		dao.insert(createInterval(startTimeMs = 5000L, endTimeMs = 6000L))

		val results = dao.getAllBetweenFlow(2500L, 4500L).first()
		results shouldHaveSize 1
		results[0].startTimeMs shouldBe 3000L
	} }

	@Test
	fun getAllBetweenOrdersByStartTime()  { runTest {
		dao.insert(createInterval(startTimeMs = 5000L, endTimeMs = 6000L))
		dao.insert(createInterval(startTimeMs = 1000L, endTimeMs = 2000L))
		dao.insert(createInterval(startTimeMs = 3000L, endTimeMs = 4000L))

		val results = dao.getAllBetweenFlow(0L, 10000L).first()
		results shouldHaveSize 3
		results[0].startTimeMs shouldBe 1000L
		results[1].startTimeMs shouldBe 3000L
		results[2].startTimeMs shouldBe 5000L
	} }

	@Test
	fun getTotalStepsReturnsZeroForNoData()  { runTest {
		val total = dao.getTotalSteps(0L, 100_000L)
		total shouldBe 0
	} }

	@Test
	fun getTotalStepsSumsCorrectly()  { runTest {
		dao.insert(createInterval(startTimeMs = 1000L, endTimeMs = 2000L, stepCount = 50))
		dao.insert(createInterval(startTimeMs = 3000L, endTimeMs = 4000L, stepCount = 75))
		dao.insert(createInterval(startTimeMs = 8000L, endTimeMs = 9000L, stepCount = 200))

		val total = dao.getTotalSteps(0L, 5000L)
		total shouldBe 125
	} }

	@Test
	fun getLatestReturnsNullWhenEmpty()  { runTest {
		val result = dao.getLatest()
		result.shouldBeNull()
	} }

	@Test
	fun getLatestReturnsMostRecent()  { runTest {
		dao.insert(createInterval(startTimeMs = 1000L, endTimeMs = 2000L, stepCount = 10))
		dao.insert(createInterval(startTimeMs = 5000L, endTimeMs = 6000L, stepCount = 30))
		dao.insert(createInterval(startTimeMs = 3000L, endTimeMs = 4000L, stepCount = 20))

		val latest = dao.getLatest()
		latest.shouldNotBeNull()
		latest!!.endTimeMs shouldBe 6000L
		latest.stepCount shouldBe 30
	} }

	@Test
	fun deleteAllRemovesAllIntervals()  { runTest {
		dao.insert(createInterval(startTimeMs = 1000L, endTimeMs = 2000L))
		dao.insert(createInterval(startTimeMs = 3000L, endTimeMs = 4000L))

		dao.deleteAll()

		val results = dao.getAllBetweenFlow(0L, 100_000L).first()
		results.shouldBeEmpty()
	} }

	@Test
	fun deleteOlderThanRemovesOldRecords()  { runTest {
		dao.insert(createInterval(startTimeMs = 1000L, endTimeMs = 2000L))
		dao.insert(createInterval(startTimeMs = 3000L, endTimeMs = 4000L))
		dao.insert(createInterval(startTimeMs = 5000L, endTimeMs = 6000L))

		val deleted = dao.deleteOlderThan(4500L)
		deleted shouldBe 2

		val remaining = dao.getAllBetweenFlow(0L, 100_000L).first()
		remaining shouldHaveSize 1
		remaining[0].startTimeMs shouldBe 5000L
	} }

	@Test
	fun getAllBetweenFlowEmitsUpdates()  { runTest {
		val initial = dao.getAllBetweenFlow(0L, 10_000L).first()
		initial.shouldBeEmpty()

		dao.insert(createInterval(startTimeMs = 1000L, endTimeMs = 2000L, stepCount = 42))
		val afterInsert = dao.getAllBetweenFlow(0L, 10_000L).first()
		afterInsert shouldHaveSize 1
		afterInsert[0].stepCount shouldBe 42
	} }
}
