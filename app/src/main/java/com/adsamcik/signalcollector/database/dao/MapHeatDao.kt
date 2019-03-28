package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.signalcollector.database.data.DatabaseMapMaxHeat

@Dao
interface MapHeatDao {
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	fun insert(heat: DatabaseMapMaxHeat)

	@Query("SELECT * FROM map_max_heat WHERE layer_name = :layerName AND zoom <= :zoom ORDER BY zoom ASC LIMIT 1")
	fun getSingle(layerName: String, zoom: Int): DatabaseMapMaxHeat?

	@Query("DELETE FROM map_max_heat")
	fun clear()
}