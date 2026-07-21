package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks per-consumer consumption progress for domain events.
 *
 * [lastProcessedId] is the delivery cursor because ids preserve insertion order even
 * when a delayed event has an older timestamp. [lastProcessedMs] is retained for
 * retention safety and advances monotonically alongside acknowledgements.
 */
@Entity(tableName = "domain_event_cursor")
data class DomainEventCursorEntity(
	@PrimaryKey @ColumnInfo(name = "consumer_id") val consumerId: String,
	@ColumnInfo(name = "last_processed_ms") val lastProcessedMs: Long,
	@ColumnInfo(name = "last_processed_id", defaultValue = "0") val lastProcessedId: Long = 0L,
)
