package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity backing [DomainEventRepository].
 * Stores polymorphic domain events with a type discriminator and JSON payload.
 */
@Entity(
	tableName = "domain_event",
	indices = [
		// Composite (timestamp_ms, id) — supports the cursor query's
		// `(timestamp_ms > T) OR (timestamp_ms = T AND id > lastId)` predicate
		// as an index seek instead of an ordered scan.
		Index(value = ["timestamp_ms", "id"]),
		Index(value = ["event_type", "processor_id"]),
	],
)
data class DomainEventEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	/** Discriminator: SessionStarted, SessionEnded, TierChanged, TripStarted, TripCompleted, CellDiscovered, AchievementUnlocked, AchievementProgress, DailySummaryUpdated */
	@ColumnInfo(name = "event_type") val eventType: String,
	@ColumnInfo(name = "processor_id") val processorId: String,
	@ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
	/** JSON-encoded type-specific payload */
	val payload: String,
)
