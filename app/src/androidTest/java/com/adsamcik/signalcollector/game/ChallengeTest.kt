package com.adsamcik.signalcollector.game

import androidx.test.core.app.ApplicationProvider
import com.adsamcik.signalcollector.database.data.DatabaseCellData
import com.adsamcik.signalcollector.game.challenge.database.ChallengeDatabase
import com.adsamcik.signalcollector.game.challenge.database.dao.ExplorerChallengeDao
import com.adsamcik.signalcollector.tracker.data.CellInfo
import com.adsamcik.signalcollector.tracker.data.CellType
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class ChallengeTest {
	private lateinit var cellDao: ExplorerChallengeDao

	@Before
	fun setup() {
		cellDao = ChallengeDatabase.getTestDatabase(ApplicationProvider.getApplicationContext()).explorerDao()
	}

	@Test
	fun testInsert() {
		val data1 = DatabaseCellData(null, System.currentTimeMillis(), System.currentTimeMillis() - 1, CellInfo("Testafon", CellType.LTE, 5, "123", "321", 10, -50, 3))
		val data2 = DatabaseCellData(null, System.currentTimeMillis(), System.currentTimeMillis() - 1, CellInfo("Testafon", CellType.LTE, 6, "123", "321", 10, -50, 3))

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