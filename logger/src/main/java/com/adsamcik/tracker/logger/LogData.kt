package com.adsamcik.tracker.logger

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.tracker.shared.base.Time


/**
 * Generic log data object
 */
@Entity(tableName = "log_data")
data class LogData(
		val timeStamp: Long = Time.nowMillis,
		val message: String,
		val data: String = "",
		val source: String
) {
	@PrimaryKey(autoGenerate = true)
	var id: Long = 0L

	constructor(
			timeStamp: Long = Time.nowMillis,
			message: String,
			data: Any,
			source: String
	) : this(timeStamp, message, data.toString(), source)
}
