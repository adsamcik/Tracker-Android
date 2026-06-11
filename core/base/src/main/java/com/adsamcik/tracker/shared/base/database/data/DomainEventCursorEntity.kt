package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks per-consumer consumption progress for domain events.
 *
 * A composite `(last_processed_ms, last_processed_id)` cursor — timestamp alone is
 * not enough because two events can share a millisecond (especially batch-produced
 * ones). The DAO query advances both fields together; the consumer ack passes the
 * last event's id alongside its timestamp.
 *
 * Backward compatibility: rows migrated from v25's timestamp-only cursor carry
 * `last_processed_id = 0`, so the first post-migration fetch query
 * `timestamp_ms > lastMs OR (timestamp_ms = lastMs AND id > lastId)` correctly
 * skips everything already past the timestamp boundary and includes nothing extra.
 */
@Entity(tableName = "domain_event_cursor")
data class DomainEventCursorEntity(
	@PrimaryKey @ColumnInfo(name = "consumer_id") val consumerId: String,
	@ColumnInfo(name = "last_processed_ms") val lastProcessedMs: Long,
	@ColumnInfo(name = "last_processed_id", defaultValue = "0") val lastProcessedId: Long = 0L,
)
