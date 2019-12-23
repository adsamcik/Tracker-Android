package com.adsamcik.tracker.shared.utils.debug

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.dao.BaseDao

@Dao
interface GenericLogDao : BaseDao<LogData> {
	@Query("SELECT * from log_data")
	fun getAll(): List<LogData>

	@Query("SELECT * from log_data ORDER BY id DESC")
	fun getAllOrderedDesc(): List<LogData>

	@Query("SELECT * from log_data ORDER BY id DESC LIMIT :count")
	fun getLastOrderedDesc(count: Int): List<LogData>
}
