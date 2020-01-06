package com.adsamcik.tracker.statistics.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Database entity for statistics.
 */
@Entity(
		tableName = "statCache",
		primaryKeys = ["session_id", "cache_id"]
)
data class StatData(
		/**
		 * Session id
		 */
		@ColumnInfo(name = "session_id")
		val sessionId: Long,
		/**
		 * Cache id
		 */
		@ColumnInfo(name = "cache_id")
		val cacheId: String,
		/**
		 * Cache value
		 */
		val value: String
)
