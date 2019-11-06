package com.adsamcik.tracker.common.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.database.dao.WifiDataDao
import com.adsamcik.tracker.common.database.data.DatabaseWifiData
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WifiTest {
	private lateinit var wifiDao: WifiDataDao

	@Before
	fun setup() {
		wifiDao = AppDatabase.testDatabase(ApplicationProvider.getApplicationContext()).wifiDao()
	}

	@Test
	fun testInsert() {
		val data1 = DatabaseWifiData(
				"00:19:3b:99:e2:80", 50.123, 10.321, null, Time.nowMillis, Time.nowMillis,
				"Example", "[WPA2]", 2400, -80
		)
		val data2 = DatabaseWifiData(
				"00:19:3b:99:e2:81", 51.234, 15.432, 300.0, Time.nowMillis, Time.nowMillis,
				"Example2", "[WPA2, WPA]", 5000, -90
		)

		val array = listOf(data1, data2)
		wifiDao.upsert(array)

		val dbget = wifiDao.getAll()

		Assert.assertTrue(dbget.isNotEmpty())
		Assert.assertEquals(array.distinctBy { it.bssid }.size, dbget.size)
		array.forEach { dwd ->
			val item = dbget.find { it.bssid == dwd.bssid }!!
			Assert.assertEquals(dwd.bssid, item.bssid)
			Assert.assertEquals(dwd.firstSeen, item.firstSeen)
			Assert.assertEquals(dwd.lastSeen, item.lastSeen)
			Assert.assertEquals(dwd.longitude, item.longitude)
			Assert.assertEquals(dwd.latitude, item.latitude)
			Assert.assertEquals(dwd.altitude, item.altitude)
			Assert.assertEquals(dwd.bssid, item.bssid)
			Assert.assertEquals(dwd.ssid, item.ssid)
			Assert.assertEquals(dwd.capabilities, item.capabilities)
			Assert.assertEquals(dwd.frequency, item.frequency)
			Assert.assertEquals(dwd.level, item.level)
		}
	}

}

