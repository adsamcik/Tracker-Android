package com.adsamcik.signalcollector.common.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.signalcollector.common.Time
import com.adsamcik.signalcollector.common.data.CellInfo
import com.adsamcik.signalcollector.common.data.CellType
import com.adsamcik.signalcollector.common.database.dao.CellDataDao
import com.adsamcik.signalcollector.common.database.data.DatabaseCellData
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CellTest {
	private lateinit var cellDao: CellDataDao

	@Before
	fun setup() {
		cellDao = AppDatabase.getTestDatabase(ApplicationProvider.getApplicationContext()).cellDao()
	}

	@Test
	fun testInsert() {
		val data1 = DatabaseCellData(null, Time.nowMillis, Time.nowMillis - 1, CellInfo("Testafon", CellType.LTE, 5, "123", "321", 10, -50, 3))
		val data2 = DatabaseCellData(null, Time.nowMillis, Time.nowMillis - 1, CellInfo("Testafon", CellType.LTE, 6, "123", "321", 10, -50, 3))

		val array = listOf(data1, data2)
		cellDao.upsert(array)

		val dbget = cellDao.getAll()

		Assert.assertTrue(dbget.isNotEmpty())
		Assert.assertEquals(array.distinctBy { it.id }.size, dbget.size)
		array.forEach { dcd ->
			val item = dbget.find { it.id == dcd.id }!!
			Assert.assertEquals(dcd.id, item.id)
			Assert.assertEquals(dcd.firstSeen, item.firstSeen)
			Assert.assertEquals(dcd.lastSeen, item.lastSeen)
			Assert.assertEquals(dcd.locationId, item.locationId)
			Assert.assertEquals(dcd.cellInfo.operatorName, item.cellInfo.operatorName)
			Assert.assertEquals(dcd.cellInfo.type, item.cellInfo.type)
			Assert.assertEquals(dcd.cellInfo.mcc, item.cellInfo.mcc)
			Assert.assertEquals(dcd.cellInfo.mnc, item.cellInfo.mnc)
			Assert.assertEquals(dcd.cellInfo.asu, item.cellInfo.asu)
		}
	}

}