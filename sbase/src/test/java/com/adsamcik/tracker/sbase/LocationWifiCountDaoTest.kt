package com.adsamcik.tracker.sbase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.data.BaseLocation
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.LocationWifiCountDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseLocationWifiCount
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.apter.junit.jupiter.robolectric.RobolectricExtension

@ExtendWith(RobolectricExtension::class)
class LocationWifiCountDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: LocationWifiCountDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.wifiLocationCountDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	private fun createWifiCount(
		time: Long = 1000L,
		latitude: Double = 50.07,
		longitude: Double = 14.42,
		altitude: Double? = null,
		count: Short = 5
	) = DatabaseLocationWifiCount(
		time = time,
		location = BaseLocation(latitude, longitude, altitude),
		count = count
	)

	// --- Insert and count ---

	@Test
	fun `insert single record and count`() {
		dao.insert(createWifiCount())
		dao.count() shouldBe 1L
	}

	@Test
	fun `insert batch of records`() {
		val records = listOf(
			createWifiCount(time = 1000L, latitude = 50.0, longitude = 14.0, count = 3),
			createWifiCount(time = 2000L, latitude = 50.1, longitude = 14.1, count = 5),
			createWifiCount(time = 3000L, latitude = 50.2, longitude = 14.2, count = 8)
		)
		dao.insert(records)

		dao.count() shouldBe 3L
	}

	@Test
	fun `count returns zero when empty`() {
		dao.count() shouldBe 0L
	}

	// --- Range ---

	@Test
	fun `range returns min and max times`() {
		dao.insert(listOf(
			createWifiCount(time = 1000L),
			createWifiCount(time = 5000L),
			createWifiCount(time = 3000L)
		))

		val range = dao.range()
		range.shouldNotBeNull()
		range.start shouldBe 1000L
		range.endInclusive shouldBe 5000L
	}

	// --- Spatial queries ---

	@Test
	fun `getAllInside returns records within bounds`() {
		dao.insert(listOf(
			createWifiCount(latitude = 50.0, longitude = 14.0, count = 3),
			createWifiCount(latitude = 50.5, longitude = 14.5, count = 7),
			createWifiCount(latitude = 60.0, longitude = 25.0, count = 2)
		))

		val inside = dao.getAllInside(
			topLatitude = 51.0,
			rightLongitude = 15.0,
			bottomLatitude = 49.0,
			leftLongitude = 13.0
		)
		inside shouldHaveSize 2
	}

	@Test
	fun `getAllInside returns empty when none in bounds`() {
		dao.insert(createWifiCount(latitude = 50.0, longitude = 14.0))

		val inside = dao.getAllInside(
			topLatitude = 10.0,
			rightLongitude = 5.0,
			bottomLatitude = 0.0,
			leftLongitude = 0.0
		)
		inside.shouldBeEmpty()
	}

	@Test
	fun `getAllInside returns weight matching count field`() {
		dao.insert(createWifiCount(latitude = 50.0, longitude = 14.0, count = 12))

		val inside = dao.getAllInside(
			topLatitude = 51.0,
			rightLongitude = 15.0,
			bottomLatitude = 49.0,
			leftLongitude = 13.0
		)
		inside shouldHaveSize 1
		inside[0].weight shouldBe 12.0
	}

	// --- Spatial and temporal queries ---

	@Test
	fun `countInsideAndBetween filters by bounds and time`() {
		dao.insert(listOf(
			createWifiCount(time = 1000L, latitude = 50.0, longitude = 14.0),
			createWifiCount(time = 3000L, latitude = 50.5, longitude = 14.5),
			createWifiCount(time = 8000L, latitude = 50.2, longitude = 14.2),
			createWifiCount(time = 2000L, latitude = 60.0, longitude = 25.0)
		))

		val count = dao.countInsideAndBetween(
			from = 500L,
			to = 4000L,
			topLatitude = 51.0,
			rightLongitude = 15.0,
			bottomLatitude = 49.0,
			leftLongitude = 13.0
		)
		count shouldBe 2
	}

	@Test
	fun `countInsideAndBetween returns zero for no matches`() {
		dao.insert(createWifiCount(time = 1000L, latitude = 50.0, longitude = 14.0))

		dao.countInsideAndBetween(
			from = 5000L,
			to = 9000L,
			topLatitude = 51.0,
			rightLongitude = 15.0,
			bottomLatitude = 49.0,
			leftLongitude = 13.0
		) shouldBe 0
	}

	@Test
	fun `getAllInsideAndBetween returns matching records`() {
		dao.insert(listOf(
			createWifiCount(time = 1000L, latitude = 50.0, longitude = 14.0, count = 3),
			createWifiCount(time = 3000L, latitude = 50.5, longitude = 14.5, count = 7),
			createWifiCount(time = 8000L, latitude = 50.2, longitude = 14.2, count = 1)
		))

		val results = dao.getAllInsideAndBetween(
			from = 500L,
			to = 4000L,
			topLatitude = 51.0,
			rightLongitude = 15.0,
			bottomLatitude = 49.0,
			leftLongitude = 13.0
		)
		results shouldHaveSize 2
	}

	@Test
	fun `getAllBetween filters by time only`() {
		dao.insert(listOf(
			createWifiCount(time = 1000L, count = 2),
			createWifiCount(time = 3000L, count = 4),
			createWifiCount(time = 8000L, count = 6)
		))

		dao.getAllBetween(from = 500L, to = 4000L) shouldHaveSize 2
	}

	@Test
	fun `getAllBetween returns empty for no matches`() {
		dao.insert(createWifiCount(time = 1000L))

		dao.getAllBetween(from = 5000L, to = 9000L).shouldBeEmpty()
	}

	@Test
	fun `getAllBetween is inclusive of boundary values`() {
		dao.insert(listOf(
			createWifiCount(time = 1000L),
			createWifiCount(time = 2000L),
			createWifiCount(time = 3000L)
		))

		dao.getAllBetween(from = 1000L, to = 3000L) shouldHaveSize 3
	}
}
