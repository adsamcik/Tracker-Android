package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Write-ahead log entry for tracking signals.
 *
 * Signals are serialized to JSON and staged here before being flushed
 * to their final destination tables (location_sample, cell_sample, etc.).
 * If the process dies mid-flush, these entries survive and are recovered
 * on the next startup.
 */
@Entity(
	tableName = "pending_signal",
	indices = [
		Index(
			value = ["session_id", "created_at"],
			name = "idx_pending_signal_session_time",
		),
		Index(
			value = ["created_at", "id"],
			name = "idx_pending_signal_recovery_order",
		),
	],
)
data class PendingSignalEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	@ColumnInfo(name = "session_id")
	val sessionId: Long,

	@ColumnInfo(name = "signal_json")
	val signalJson: String,

	@ColumnInfo(name = "created_at")
	val createdAt: Long,
)
