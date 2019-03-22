package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.adsamcik.signalcollector.database.data.DatabaseWifiData

@Dao
interface WifiDataDao {

	@Insert
	fun insert(wifi: DatabaseWifiData)

	@Query("SELECT * from wifi_data")
	fun getAll(): List<DatabaseWifiData>
}