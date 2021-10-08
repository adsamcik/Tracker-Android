package com.adsamcik.tracker.logger

import androidx.room.Dao
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.dao.BaseDao

/**
 * DAO for generic logs
 */
@Dao
interface GenericLogDao : BaseDao<LogData> {
	/**
	 * Get all logs from database
	 */
	@Query("SELECT * from log_data")
	fun getAll(): List<LogData>

	/**
	 * Get all logs ordered by id from database
	 */
	@Query("SELECT * from log_data ORDER BY id DESC")
	fun getAllOrderedDesc(): List<LogData>

	/**
	 * Get limited number of logs ordered by descending order with id.
	 */
	@Query("SELECT * from log_data ORDER BY id DESC LIMIT :count")
	fun getLastOrderedDesc(count: Int): List<LogData>
}
