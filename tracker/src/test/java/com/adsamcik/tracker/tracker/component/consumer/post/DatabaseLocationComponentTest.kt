package com.adsamcik.tracker.tracker.component.consumer.post

import android.content.Context
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.TrackerSession
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import com.adsamcik.tracker.tracker.data.PersistenceError
import com.adsamcik.tracker.tracker.data.PersistenceErrorCollector
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension
import org.robolectric.annotation.Config

/**
 * Test suite for [DatabaseLocationComponent].
 *
 * Verifies:
 * - Location persistence to Room via [LocationDataDao]
 * - Batch buffering (flush at 10 items)
 * - Missing location data is silently skipped
 * - Error collector receives persistence failures
 * - FlushPending drains buffer on disable
 * - Enable/disable lifecycle resets state
 */
@ExtendWith(RobolectricExtension::class)
@Config(sdk = [28])
class DatabaseLocationComponentTest {

	private lateinit var component: DatabaseLocationComponent
	private lateinit var context: Context
	private lateinit var mockDatabase: AppDatabase
	private lateinit var mockLocationDao: LocationDataDao
	private lateinit var mockErrorCollector: PersistenceErrorCollector

	@BeforeEach
	fun setup() {
		context = mockk(relaxed = true)
		mockDatabase = mockk(relaxed = true)
		mockLocationDao = mockk(relaxed = true)
		mockErrorCollector = mockk(relaxed = true)

		every { mockDatabase.locationDao() } returns mockLocationDao
		coEvery { mockLocationDao.insert(any<Collection<DatabaseLocation>>()) } returns emptyList()

		mockkObject(AppDatabase.Companion)
		every { AppDatabase.database(any()) } returns mockDatabase

		component = DatabaseLocationComponent()
		component.setErrorCollector(mockErrorCollector)
	}

	@AfterEach
	fun teardown() {
		unmockkObject(AppDatabase.Companion)
	}

	private fun createCollectionData(
		latitude: Double? = null,
		longitude: Double? = null,
	): CollectionData {
		val data = mockk<CollectionData>(relaxed = true)
		if (latitude != null && longitude != null) {
			val location = mockk<Location>()
			every { location.latitude } returns latitude
			every { location.longitude } returns longitude
			every { location.speed } returns null
			every { location.horizontalAccuracy } returns 10f
			every { location.altitude } returns null
			every { location.time } returns BASE_TIME
			every { data.location } returns location
			val activity = mockk<ActivityInfo>()
			every { activity.activityType } returns 4
			every { activity.confidence } returns 0
			every { data.activity } returns activity
		} else {
			every { data.location } returns null
		}
		return data
	}

	private fun createTempData(timeMs: Long): MutableCollectionTempData {
		return MutableCollectionTempData(timeMs, timeMs * 1_000_000)
	}

	private fun createSession(): TrackerSession = mockk(relaxed = true)

	// --- Basic Persistence ---

	@Test
	fun `single location is flushed on disable`()  { runTest {
		component.onEnable(context)

		component.onNewData(
			context, createSession(),
			createCollectionData(latitude = 50.0, longitude = 14.0),
			createTempData(BASE_TIME)
		)

		component.onDisable(context)

		coVerify(atLeast = 1) {
			mockLocationDao.insert(match<Collection<DatabaseLocation>> { it.size == 1 })
		}
	} }

	@Test
	fun `null location skips persistence`()  { runTest {
		component.onEnable(context)

		component.onNewData(
			context, createSession(),
			createCollectionData(), // no location
			createTempData(BASE_TIME)
		)

		component.onDisable(context)

		coVerify(exactly = 0) { mockLocationDao.insert(any<Collection<DatabaseLocation>>()) }
	} }

	// --- Batch Flushing ---

	@Test
	fun `batch flushes at size 10`()  { runTest {
		component.onEnable(context)

		// Feed exactly BATCH_SIZE (10) items
		repeat(10) { i ->
			component.onNewData(
				context, createSession(),
				createCollectionData(latitude = 50.0 + i * 0.001, longitude = 14.0),
				createTempData(BASE_TIME + i * 1000L)
			)
		}

		// Allow async flush
		Thread.sleep(200)

		// Should have flushed the batch of 10
		coVerify(atLeast = 1) {
			mockLocationDao.insert(match<Collection<DatabaseLocation>> { it.size == 10 })
		}

		component.onDisable(context)
	} }

	@Test
	fun `items below batch threshold flush on disable`()  { runTest {
		component.onEnable(context)

		// Feed fewer than BATCH_SIZE items
		repeat(5) { i ->
			component.onNewData(
				context, createSession(),
				createCollectionData(latitude = 50.0 + i * 0.001, longitude = 14.0),
				createTempData(BASE_TIME + i * 1000L)
			)
		}

		component.onDisable(context)

		coVerify(atLeast = 1) {
			mockLocationDao.insert(match<Collection<DatabaseLocation>> { it.size == 5 })
		}
	} }

	@Test
	fun `multiple batches accumulate correctly`()  { runTest {
		component.onEnable(context)

		// Feed 25 items: should trigger 2 batch flushes (10+10) + 5 remaining
		repeat(25) { i ->
			component.onNewData(
				context, createSession(),
				createCollectionData(latitude = 50.0 + i * 0.0001, longitude = 14.0),
				createTempData(BASE_TIME + i * 100L)
			)
		}

		Thread.sleep(200)
		component.onDisable(context)

		// At least 2 batch inserts of 10 + final flush of 5
		coVerify(atLeast = 3) {
			mockLocationDao.insert(any<Collection<DatabaseLocation>>())
		}
	} }

	// --- Error Handling ---

	@Test
	fun `dao exception triggers error collector`()  { runTest {
		val testException = RuntimeException("DB write failed")
		coEvery { mockLocationDao.insert(any<Collection<DatabaseLocation>>()) } throws testException

		component.onEnable(context)

		// Feed exactly BATCH_SIZE to trigger immediate flush
		repeat(10) { i ->
			component.onNewData(
				context, createSession(),
				createCollectionData(latitude = 50.0 + i * 0.001, longitude = 14.0),
				createTempData(BASE_TIME + i * 1000L)
			)
		}

		// Allow async flush to fire and catch exception
		Thread.sleep(300)

		verify(atLeast = 1) {
			mockErrorCollector.reportErrorAsync(match<PersistenceError> {
				it.source == "DatabaseLocationComponent" &&
					it.operation == "batch insert locations" &&
					it.recordCount == 10 &&
					it.cause === testException
			})
		}

		// Reset mock to avoid exception on disable flush
		coEvery { mockLocationDao.insert(any<Collection<DatabaseLocation>>()) } returns emptyList()
		component.onDisable(context)
	} }

	// --- FlushPending ---

	@Test
	fun `flushPending drains buffered locations`()  { runTest {
		component.onEnable(context)

		repeat(3) { i ->
			component.onNewData(
				context, createSession(),
				createCollectionData(latitude = 50.0 + i * 0.001, longitude = 14.0),
				createTempData(BASE_TIME + i * 1000L)
			)
		}

		component.flushPending()

		coVerify(atLeast = 1) {
			mockLocationDao.insert(match<Collection<DatabaseLocation>> { it.size == 3 })
		}

		component.onDisable(context)
	} }

	@Test
	fun `flushPending with empty buffer does not insert`()  { runTest {
		component.onEnable(context)

		component.flushPending()

		coVerify(exactly = 0) { mockLocationDao.insert(any<Collection<DatabaseLocation>>()) }

		component.onDisable(context)
	} }

	// --- Lifecycle ---

	@Test
	fun `enable then immediate disable with no data`()  { runTest {
		component.onEnable(context)
		component.onDisable(context)

		coVerify(exactly = 0) { mockLocationDao.insert(any<Collection<DatabaseLocation>>()) }
	} }

	@Test
	fun `multiple enable-disable cycles reset buffer`()  { runTest {
		// First cycle
		component.onEnable(context)
		repeat(3) { i ->
			component.onNewData(
				context, createSession(),
				createCollectionData(latitude = 50.0 + i * 0.001, longitude = 14.0),
				createTempData(BASE_TIME + i * 1000L)
			)
		}
		component.onDisable(context)

		// Second cycle should start with empty buffer
		component.onEnable(context)
		component.onNewData(
			context, createSession(),
			createCollectionData(latitude = 51.0, longitude = 15.0),
			createTempData(BASE_TIME + 100_000)
		)
		component.onDisable(context)

		// Second disable should only flush the single item from cycle 2
		coVerify {
			mockLocationDao.insert(match<Collection<DatabaseLocation>> { it.size == 1 })
		}
	} }

	// --- Required Data ---

	@Test
	fun `requiredData is empty`() {
		component.requiredData shouldBe emptyList()
	}

	companion object {
		private const val BASE_TIME = 1_700_000_000_000L
	}
}
