package com.adsamcik.tracker.common.debug

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.common.database.dao.BaseDao

@Dao
interface GenericLogDao : BaseDao<LogData> {
	@Query("SELECT * from log_data")
	fun getAll(): List<LogData>
}
