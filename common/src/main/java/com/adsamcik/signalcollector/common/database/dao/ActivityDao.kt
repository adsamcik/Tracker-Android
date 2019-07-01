package com.adsamcik.signalcollector.common.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.signalcollector.common.data.SessionActivity

@Dao
interface ActivityDao : BaseDao<SessionActivity> {
	@Query("SELECT * FROM activity")
	fun getAll(): List<SessionActivity>

	@Query("SELECT * FROM activity WHERE id = :id")
	fun get(id: Long): SessionActivity?

	@Query("DELETE FROM activity WHERE id = :id")
	fun delete(id: Long)
}