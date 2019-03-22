package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.signalcollector.database.data.DatabaseCellData

@Dao
interface CellDataDao {
	@Insert
	fun insert(cells: DatabaseCellData)

	@Query("SELECT * from cell_data")
	fun getAll(): List<DatabaseCellData>
}