package com.adsamcik.signalcollector.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.signalcollector.database.dao.CellDataDao
import com.adsamcik.signalcollector.database.data.DatabaseCellData
import com.adsamcik.signalcollector.tracker.data.CellInfo
import com.adsamcik.signalcollector.tracker.data.CellType
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
		val data = DatabaseCellData(null, System.currentTimeMillis(), System.currentTimeMillis() - 1, CellInfo("Testafon", CellType.LTE, 5, "123", "321", 10, -50, 3))
		cellDao.insertWithUpdate(data)

		val dbget = cellDao.getAll()

		Assert.assertEquals(1, dbget.size)
		val first = dbget.first()
		Assert.assertEquals(data, first)
	}

}