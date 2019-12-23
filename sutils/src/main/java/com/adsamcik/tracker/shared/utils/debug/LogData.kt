package com.adsamcik.tracker.shared.utils.debug

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.adsamcik.tracker.common.Time


@Entity(tableName = "log_data")
data class LogData(
		val timeStamp: Long = Time.nowMillis,
		val message: String,
		val data: String = ""
) {
	@PrimaryKey(autoGenerate = true)
	var id: Long = 0L

	constructor(
			timeStamp: Long = Time.nowMillis,
			message: String,
			data: Any
	) : this(timeStamp, message, data.toString())
}
