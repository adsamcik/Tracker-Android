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
data class CacheStatData(
		/**
		 * Session id
		 */
		@ColumnInfo(name = "session_id")
		val sessionId: Long,
		/**
		 * Provider id
		 */
		@ColumnInfo(name = "provider_id")
		val providerId: String,
		/**
		 * Cache value
		 */
		val value: String
)
