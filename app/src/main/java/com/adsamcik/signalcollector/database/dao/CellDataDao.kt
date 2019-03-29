package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.signalcollector.tracker.data.CellType
import com.adsamcik.signalcollector.database.data.DatabaseCellData

@Dao
interface CellDataDao {
	@Update(onConflict = OnConflictStrategy.IGNORE)
	fun insertWithUpdate(cell: DatabaseCellData): Int

	@Query("UPDATE cell_data SET location_id = CASE WHEN location_id IS NULL OR asu < :asu THEN :locationId ELSE location_id END, last_seen = :lastSeen, type = :type, asu = CASE WHEN asu < :asu THEN :asu ELSE asu END WHERE cell_id = :cellId")
	fun update(cellId: Int, locationId: Long?, lastSeen: Long, type: CellType, asu: Int)

	@Query("SELECT * from cell_data")
	fun getAll(): List<DatabaseCellData>

	@Query("SELECT COUNT(*) from cell_data")
	fun count(): Long
}