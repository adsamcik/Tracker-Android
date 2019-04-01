package com.adsamcik.signalcollector.database.dao

import androidx.room.*
import com.adsamcik.signalcollector.database.data.DatabaseCellData
import com.adsamcik.signalcollector.tracker.data.CellType

@Dao
interface CellDataDao {
	@Insert
	fun insert(cell: DatabaseCellData): Long

	//Rewrite as https://www.sqlite.org/lang_UPSERT.html
	@Query("INSERT INTO cell_data(id, cell_id, location_id, last_seen, first_seen, operator_name, type, mcc, mnc, asu) VALUES(:cellId, :cellId, :locationId, :lastSeen, :lastSeen, :operatorName, :type, :mcc, :mnc, :asu) ON CONFLICT(id) DO UPDATE SET location_id = CASE WHEN location_id IS NULL OR asu < :asu THEN :locationId ELSE location_id END, last_seen = :lastSeen, type = :type, asu = CASE WHEN asu < :asu THEN :asu ELSE asu END WHERE cell_id = :cellId")
	fun insertWithUpdate(cellId: Int,
	                     locationId: Long?,
	                     lastSeen: Long,
	                     operatorName: String,
	                     type: CellType,
	                     mcc: String,
	                     mnc: String,
	                     asu: Int)

	@Query("SELECT * from cell_data")
	fun getAll(): List<DatabaseCellData>

	@Query("SELECT COUNT(*) from cell_data")
	fun count(): Long
}