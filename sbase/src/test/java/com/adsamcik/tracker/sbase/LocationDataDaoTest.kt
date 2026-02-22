package com.adsamcik.tracker.sbase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class LocationDataDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: LocationDataDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.locationDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	private fun createLocation(timeMs: Long): DatabaseLocation {
		val location = Location(
			time = timeMs,
			latitude = 50.0,
			longitude = 14.0,
			altitude = null,
			horizontalAccuracy = 5f,
			verticalAccuracy = null,
			speed = null,
			speedAccuracy = null
		)
		return DatabaseLocation(location, ActivityInfo.UNKNOWN)
	}

	@Test
	fun `getAllSince returns matching rows`() {
		runTest {
			dao.insert(createLocation(1000L))
			dao.insert(createLocation(2000L))
			dao.insert(createLocation(3000L))

			val results = dao.getAllSince(2000L)
			results shouldHaveSize 2
		}
	}

	@Test
	fun `getAllSince returns empty for no matches`() {
		runTest {
			dao.insert(createLocation(1000L))

			dao.getAllSince(5000L).shouldBeEmpty()
		}
	}

	@Test
	fun `getAllSince respects limit parameter`() {
		runTest {
			// Insert more rows than the limit
			val rows = (1..20).map { i -> createLocation(i * 1000L) }
			dao.insert(rows)

			val results = dao.getAllSince(0L, limit = 5)
			results shouldHaveSize 5
			// Should be ordered by time, so first result is earliest
			results.first().time shouldBe 1000L
		}
	}

	@Test
	fun `getAllSince default limit does not truncate small result sets`() {
		runTest {
			val rows = (1..10).map { i -> createLocation(i * 1000L) }
			dao.insert(rows)

			// Default limit (50000) should not truncate 10 rows
			val results = dao.getAllSince(0L)
			results shouldHaveSize 10
		}
	}

	@Test
	fun `getAllSince results are ordered by time`() {
		runTest {
			dao.insert(createLocation(3000L))
			dao.insert(createLocation(1000L))
			dao.insert(createLocation(2000L))

			val results = dao.getAllSince(0L)
			results shouldHaveSize 3
			results[0].time shouldBe 1000L
			results[1].time shouldBe 2000L
			results[2].time shouldBe 3000L
		}
	}
}
