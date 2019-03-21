package com.adsamcik.signalcollector.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.signalcollector.data.DatabaseActivity

@Dao
interface ActivityDataDao {
	@Query("SELECT * from activity_data")
	fun getAll(): List<DatabaseActivity>

	@Query("SELECT COUNT(id) FROM activity_data")
	fun count(): LiveData<Int>

	@Insert(onConflict = OnConflictStrategy.ABORT)
	fun insert(locationData: DatabaseActivity)

	@Query("DELETE from activity_data")
	fun deleteAll()

}