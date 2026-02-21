package com.adsamcik.tracker.sbase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.WifiDataDao
import com.adsamcik.tracker.shared.base.database.data.DatabaseWifiData
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
class WifiDataDaoTest {

	private lateinit var database: AppDatabase
	private lateinit var dao: WifiDataDao

	@BeforeEach
	fun setUp() {
		val context: Application = ApplicationProvider.getApplicationContext()
		database = AppDatabase.testDatabase(context)
		dao = database.wifiDao()
	}

	@AfterEach
	fun tearDown() {
		database.close()
	}

	private fun createWifi(
		bssid: String = "00:11:22:33:44:55",
		longitude: Double? = 14.42,
		latitude: Double? = 50.07,
		altitude: Double? = 200.0,
		firstSeen: Long = 1000L,
		lastSeen: Long = 2000L,
		ssid: String = "TestNetwork",
		capabilities: String = "[WPA2-PSK]",
		frequency: Int = 2437,
		level: Int = -60
	) = DatabaseWifiData(
		bssid = bssid,
		longitude = longitude,
		latitude = latitude,
		altitude = altitude,
		firstSeen = firstSeen,
		lastSeen = lastSeen,
		ssid = ssid,
		capabilities = capabilities,
		frequency = frequency,
		level = level
	)

	// --- Insert and retrieve ---

	@Test
	fun `insert single wifi record and retrieve all`() {
		val wifi = createWifi()
		dao.insert(wifi)

		val all = dao.getAll()
		all shouldHaveSize 1
		all[0].bssid shouldBe "00:11:22:33:44:55"
		all[0].ssid shouldBe "TestNetwork"
		all[0].latitude shouldBe 50.07
		all[0].longitude shouldBe 14.42
	}

	@Test
	fun `insert multiple wifi records and retrieve all`() {
		val wifiList = listOf(
			createWifi(bssid = "aa:bb:cc:dd:ee:01", ssid = "Net1"),
			createWifi(bssid = "aa:bb:cc:dd:ee:02", ssid = "Net2"),
			createWifi(bssid = "aa:bb:cc:dd:ee:03", ssid = "Net3")
		)
		dao.insert(wifiList)

		dao.getAll() shouldHaveSize 3
	}

	@Test
	fun `getAll with limit returns at most count records`() {
		dao.insert(listOf(
			createWifi(bssid = "01:01:01:01:01:01"),
			createWifi(bssid = "02:02:02:02:02:02"),
			createWifi(bssid = "03:03:03:03:03:03")
		))

		dao.getAll(2L) shouldHaveSize 2
	}

	@Test
	fun `getAll returns empty list when no data`() {
		dao.getAll().shouldBeEmpty()
	}

	// --- Count and range ---

	@Test
	fun `count returns total number of records`() {
		dao.insert(listOf(
			createWifi(bssid = "a1:a1:a1:a1:a1:a1"),
			createWifi(bssid = "b2:b2:b2:b2:b2:b2")
		))

		dao.count() shouldBe 2L
	}

	@Test
	fun `count returns zero when empty`() {
		dao.count() shouldBe 0L
	}

	@Test
	fun `count with time range filters correctly`() {
		dao.insert(listOf(
			createWifi(bssid = "a1:a1:a1:a1:a1:a1", lastSeen = 1000L),
			createWifi(bssid = "b2:b2:b2:b2:b2:b2", lastSeen = 2000L),
			createWifi(bssid = "c3:c3:c3:c3:c3:c3", lastSeen = 5000L)
		))

		dao.count(500L, 3000L) shouldBe 2L
		dao.count(4000L, 6000L) shouldBe 1L
		dao.count(8000L, 9000L) shouldBe 0L
	}

	@Test
	fun `range returns min and max last_seen times`() {
		dao.insert(listOf(
			createWifi(bssid = "a1:a1:a1:a1:a1:a1", lastSeen = 1000L),
			createWifi(bssid = "b2:b2:b2:b2:b2:b2", lastSeen = 5000L),
			createWifi(bssid = "c3:c3:c3:c3:c3:c3", lastSeen = 3000L)
		))

		val range = dao.range()
		range.shouldNotBeNull()
		range.start shouldBe 1000L
		range.endInclusive shouldBe 5000L
	}

	// --- Upsert and update ---

	@Test
	fun `upsert inserts new records`() {
		val wifiList = listOf(
			createWifi(bssid = "a1:a1:a1:a1:a1:a1", ssid = "Net1"),
			createWifi(bssid = "b2:b2:b2:b2:b2:b2", ssid = "Net2")
		)
		dao.upsert(wifiList)

		dao.count() shouldBe 2L
	}

	@Test
	fun `upsert updates existing records with stronger signal`() {
		dao.insert(createWifi(bssid = "aa:bb:cc:dd:ee:ff", level = -70, latitude = 50.0, longitude = 14.0))

		dao.upsert(listOf(
			createWifi(bssid = "aa:bb:cc:dd:ee:ff", level = -50, latitude = 51.0, longitude = 15.0, ssid = "Updated")
		))

		val all = dao.getAll()
		all shouldHaveSize 1
		all[0].level shouldBe -50
		all[0].ssid shouldBe "Updated"
		all[0].latitude shouldBe 51.0
		all[0].longitude shouldBe 15.0
	}

	@Test
	fun `upsert preserves location when new signal is weaker`() {
		dao.insert(createWifi(bssid = "aa:bb:cc:dd:ee:ff", level = -40, latitude = 50.0, longitude = 14.0))

		dao.upsert(listOf(
			createWifi(bssid = "aa:bb:cc:dd:ee:ff", level = -80, latitude = 99.0, longitude = 99.0, ssid = "Weaker")
		))

		val all = dao.getAll()
		all shouldHaveSize 1
		all[0].level shouldBe -40
		all[0].latitude shouldBe 50.0
		all[0].longitude shouldBe 14.0
		all[0].ssid shouldBe "Weaker"
	}

	@Test
	fun `upsert with empty collection does nothing`() {
		dao.insert(createWifi())
		dao.upsert(emptyList())
		dao.count() shouldBe 1L
	}

	@Test
	fun `updateSignalDataIfCloser updates only when signal is stronger`() {
		dao.insert(createWifi(bssid = "aa:bb:cc:dd:ee:ff", level = -60, latitude = 50.0, longitude = 14.0))

		dao.updateSignalDataIfCloser("aa:bb:cc:dd:ee:ff", 15.0, 51.0, 300.0, -40)
		var result = dao.getAll()[0]
		result.level shouldBe -40
		result.latitude shouldBe 51.0

		dao.updateSignalDataIfCloser("aa:bb:cc:dd:ee:ff", 99.0, 99.0, 999.0, -90)
		result = dao.getAll()[0]
		result.level shouldBe -40
		result.latitude shouldBe 51.0
	}

	@Test
	fun `updateData updates metadata fields`() {
		dao.insert(createWifi(bssid = "aa:bb:cc:dd:ee:ff", ssid = "Old", frequency = 2412, lastSeen = 1000L))

		dao.updateData("aa:bb:cc:dd:ee:ff", "NewSSID", "[WPA3]", 5180, 9000L)

		val result = dao.getAll()[0]
		result.ssid shouldBe "NewSSID"
		result.capabilities shouldBe "[WPA3]"
		result.frequency shouldBe 5180
		result.lastSeen shouldBe 9000L
	}

	// --- Delete ---

	@Test
	fun `deleteAll removes all records`() {
		dao.insert(listOf(
			createWifi(bssid = "a1:a1:a1:a1:a1:a1"),
			createWifi(bssid = "b2:b2:b2:b2:b2:b2")
		))

		dao.deleteAll()
		dao.count() shouldBe 0L
	}

	@Test
	fun `deleteAll on empty table does not throw`() {
		dao.deleteAll()
		dao.count() shouldBe 0L
	}

	// --- Duplicate handling ---

	@Test
	fun `insert ignores duplicate bssid`() {
		val first = createWifi(bssid = "aa:bb:cc:dd:ee:ff", ssid = "First")
		val second = createWifi(bssid = "aa:bb:cc:dd:ee:ff", ssid = "Second")

		val id1 = dao.insert(first)
		val id2 = dao.insert(second)

		id2 shouldBe -1L
		dao.count() shouldBe 1L
		dao.getAll()[0].ssid shouldBe "First"
	}

	// --- Spatial queries ---

	@Test
	fun `getAllInside returns records within bounds`() {
		dao.insert(listOf(
			createWifi(bssid = "a1:a1:a1:a1:a1:a1", latitude = 50.0, longitude = 14.0),
			createWifi(bssid = "b2:b2:b2:b2:b2:b2", latitude = 51.0, longitude = 15.0),
			createWifi(bssid = "c3:c3:c3:c3:c3:c3", latitude = 60.0, longitude = 25.0)
		))

		val inside = dao.getAllInside(
			topLatitude = 52.0,
			rightLongitude = 16.0,
			bottomLatitude = 49.0,
			leftLongitude = 13.0
		)
		inside shouldHaveSize 2
	}

	@Test
	fun `getAllInside returns empty when none match`() {
		dao.insert(createWifi(latitude = 50.0, longitude = 14.0))

		val inside = dao.getAllInside(
			topLatitude = 10.0,
			rightLongitude = 5.0,
			bottomLatitude = 0.0,
			leftLongitude = 0.0
		)
		inside.shouldBeEmpty()
	}

	@Test
	fun `getAllInsideAndBetween filters by bounds and time`() {
		dao.insert(listOf(
			createWifi(bssid = "a1:a1:a1:a1:a1:a1", latitude = 50.0, longitude = 14.0, lastSeen = 1000L),
			createWifi(bssid = "b2:b2:b2:b2:b2:b2", latitude = 50.5, longitude = 14.5, lastSeen = 3000L),
			createWifi(bssid = "c3:c3:c3:c3:c3:c3", latitude = 50.2, longitude = 14.2, lastSeen = 8000L)
		))

		val result = dao.getAllInsideAndBetween(
			from = 500L,
			to = 4000L,
			topLatitude = 52.0,
			rightLongitude = 16.0,
			bottomLatitude = 49.0,
			leftLongitude = 13.0
		)
		result shouldHaveSize 2
	}
}
