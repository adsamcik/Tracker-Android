package com.adsamcik.signalcollector.common.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.signalcollector.common.data.CellType
import com.adsamcik.signalcollector.common.database.data.Database2DLocationWeightedMinimal
import com.adsamcik.signalcollector.common.database.data.DatabaseCellData

@Dao
interface CellDataDao : BaseDao<DatabaseCellData> {

	@Query("DELETE FROM cell_data")
	fun deleteAll()

	@Query("UPDATE cell_data SET location_id = CASE WHEN location_id IS NULL OR asu < :asu THEN :locationId ELSE location_id END, last_seen = :lastSeen, type = :type, asu = CASE WHEN asu < :asu THEN :asu ELSE asu END WHERE id = :id")
	fun update(id: String, locationId: Long?, lastSeen: Long, type: CellType, asu: Int)

	@Transaction
	fun upsert(objList: Collection<DatabaseCellData>) {
		val insertResult = insert(objList)
		val updateList = objList.filterIndexed { index, _ -> insertResult[index] == -1L }

		updateList.forEach { update(it.id, it.locationId, it.lastSeen, it.cellInfo.type, it.cellInfo.asu) }
	}

	@Query("SELECT * from cell_data")
	fun getAll(): List<DatabaseCellData>

	@Query("""
		SELECT lat, lon, COUNT(*) as weight FROM cell_data 
		INNER JOIN location_data ld ON ld.id = cell_id
		WHERE ld.lat >= :bottomLatitude and ld.lon >= :leftLongitude and ld.lat <= :topLatitude and ld.lon <= :rightLongitude""")
	fun getAllInside(topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>

	@Query("""SELECT lat, lon, COUNT(*) as weight FROM cell_data
			INNER JOIN location_data ld ON ld.id = cell_id
			WHERE last_seen >= :from and last_seen <= :to and ld.lat >= :bottomLatitude and ld.lon >= :leftLongitude and ld.lat <= :topLatitude and ld.lon <= :rightLongitude""")
	fun getAllInsideAndBetween(from: Long, to: Long, topLatitude: Double, rightLongitude: Double, bottomLatitude: Double, leftLongitude: Double): List<Database2DLocationWeightedMinimal>


	@Query("SELECT COUNT(*) from cell_data")
	fun count(): Long
}