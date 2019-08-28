package com.adsamcik.tracker.common.debug

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.common.database.dao.BaseDao

@Dao
interface GenericLogDao : BaseDao<LogData> {
	@Query("SELECT * from log_data")
	fun getAll(): List<LogData>

	@Query("SELECT * from log_data ORDER BY id DESC")
	fun getAllOrderedDesc(): List<LogData>

	@Query("SELECT * from log_data ORDER BY id DESC LIMIT :count")
	fun getLastOrderedDesc(count: Int): List<LogData>
}
