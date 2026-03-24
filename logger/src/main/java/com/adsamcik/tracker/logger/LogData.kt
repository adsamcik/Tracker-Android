package com.adsamcik.tracker.logger

import androidx.room.Entity
import androidx.room.PrimaryKey


/**
 * Generic log data object
 */
@Entity(tableName = "log_data")
data class LogData(
		val timeStamp: Long = System.currentTimeMillis(),
		val message: String,
		val data: String = "",
		val source: String
) {
	@PrimaryKey(autoGenerate = true)
	var id: Long = 0L

	constructor(
			timeStamp: Long = System.currentTimeMillis(),
			message: String,
			data: Any,
			source: String
	) : this(timeStamp, message, data.toString(), source)
}
