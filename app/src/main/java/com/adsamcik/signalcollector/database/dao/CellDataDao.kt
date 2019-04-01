package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.signalcollector.database.data.DatabaseCellData
import com.adsamcik.signalcollector.tracker.data.CellType

@Dao
interface CellDataDao : BaseDao<DatabaseCellData> {

	@Query("UPDATE cell_data SET location_id = CASE WHEN location_id IS NULL OR asu < :asu THEN :locationId ELSE location_id END, last_seen = :lastSeen, type = :type, asu = CASE WHEN asu < :asu THEN :asu ELSE asu END WHERE id = :id")
	abstract fun update(id: String, locationId: Long?, lastSeen: Long, type: CellType, asu: Int)

	@Transaction
	fun upsert(objList: Collection<DatabaseCellData>) {
		val insertResult = insert(objList)
		val updateList = objList.filterIndexed { index, _ -> insertResult[index] == -1L }

		updateList.forEach { update(it.id, it.locationId, it.lastSeen, it.cellInfo.type, it.cellInfo.asu) }
	}

	@Query("SELECT * from cell_data")
	abstract fun getAll(): List<DatabaseCellData>

	@Query("SELECT COUNT(*) from cell_data")
	abstract fun count(): Long
}