package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks per-consumer consumption progress for domain events.
 * Each consumer maintains a cursor pointing to the last-processed event timestamp.
 */
@Entity(tableName = "domain_event_cursor")
data class DomainEventCursorEntity(
	@PrimaryKey @ColumnInfo(name = "consumer_id") val consumerId: String,
	@ColumnInfo(name = "last_processed_ms") val lastProcessedMs: Long,
)
