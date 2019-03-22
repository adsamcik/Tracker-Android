package com.adsamcik.signalcollector.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.signalcollector.database.data.DatabaseDebugActivity

@Dao
interface ActivityDebugDao {

	@Insert(onConflict = OnConflictStrategy.ABORT)
	fun insert(activityData: DatabaseDebugActivity)

	@Query("SELECT * FROM debug_activity")
	fun getAll(): List<DatabaseDebugActivity>

	@Query("DELETE FROM debug_activity")
	fun deleteAll()
}