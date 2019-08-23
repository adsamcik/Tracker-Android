package com.adsamcik.tracker.common.debug

import androidx.room.Entity
import androidx.room.PrimaryKey


@Entity(tableName = "log_data")
data class LogData(val message: String, val data: String) {
	@PrimaryKey
	var id: Long = 0L
}
