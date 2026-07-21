package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only tracker lifecycle evidence.
 *
 * Wall time only places an event on the calendar. Duration reconstruction must stay inside one
 * [clockDomainId] and use [elapsedRealtimeNanos]; an active event is bounded by its durable lease
 * rather than by a later process's wall clock.
 */
@Entity(
	tableName = "tracker_state_event",
	indices = [
		Index(
			value = ["clock_domain_id", "elapsed_realtime_nanos", "id"],
			name = "idx_tracker_state_event_clock",
		),
		Index(value = ["wall_time_ms", "id"], name = "idx_tracker_state_event_wall"),
	],
)
data class TrackerStateEvent(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,
	@ColumnInfo(name = "clock_domain_id")
	val clockDomainId: String,
	@ColumnInfo(name = "elapsed_realtime_nanos")
	val elapsedRealtimeNanos: Long,
	@ColumnInfo(name = "wall_time_ms")
	val wallTimeMs: Long,
	val state: String,
	val policy: String,
	val reason: String? = null,
	@ColumnInfo(name = "active_lease_expires_elapsed_nanos")
	val activeLeaseExpiresElapsedNanos: Long? = null,
	@ColumnInfo(name = "created_at_ms")
	val createdAtMs: Long,
	@ColumnInfo(name = "source_revision", defaultValue = "0")
	val sourceRevision: Long = 0L,
) {
	companion object {
		const val START = "START"
		const val POLICY_TRANSITION = "POLICY_TRANSITION"
		const val STOP = "STOP"
		const val HEARTBEAT = "HEARTBEAT"
	}
}
