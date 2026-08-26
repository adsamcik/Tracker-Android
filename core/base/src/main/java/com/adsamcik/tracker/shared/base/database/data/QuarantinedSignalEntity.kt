package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable terminal record for a pending signal that cannot safely be replayed.
 *
 * Quarantine is deliberately a copy rather than an error string attached to
 * `pending_signal`: the recovery transaction inserts this record and removes
 * the source pending row together, which makes permanent failures observable
 * without allowing them to block all newer work forever.
 */
@Entity(
	tableName = "quarantined_signal",
	indices = [
		Index(value = ["source_pending_id"], unique = true, name = "idx_quarantined_signal_source_pending"),
		Index(value = ["signal_id"], unique = true, name = "idx_quarantined_signal_signal_id"),
		Index(value = ["quarantined_at", "id"], name = "idx_quarantined_signal_time"),
		Index(value = ["acquired_at_ms", "id"], name = "idx_quarantined_signal_acquired_time"),
		Index(value = ["failure_reason"], name = "idx_quarantined_signal_reason"),
	],
)
data class QuarantinedSignalEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/** Primary key of the pending row moved into this durable error ledger. */
	@ColumnInfo(name = "source_pending_id")
	val sourcePendingId: Long,

	/** Stable identity retained so an operator can correlate retries and exports. */
	@ColumnInfo(name = "signal_id")
	val signalId: String,

	@ColumnInfo(name = "session_id")
	val sessionId: Long,

	@ColumnInfo(name = "envelope_version")
	val envelopeVersion: Int,

	@ColumnInfo(name = "payload_checksum")
	val payloadChecksum: String?,

	/** Exact serialized payload retained for forensic export or a future repair tool. */
	@ColumnInfo(name = "signal_json")
	val signalJson: String,

	@ColumnInfo(name = "created_at")
	val createdAt: Long,

	/** Provider acquisition time retained so raw-data expiry uses evidence age, not WAL age. */
	@ColumnInfo(name = "acquired_at_ms", defaultValue = "0")
	val acquiredAtMs: Long,

	@ColumnInfo(name = "delivery_attempt_count")
	val deliveryAttemptCount: Int,

	/** Stable machine-readable failure code, not a localized string. */
	@ColumnInfo(name = "failure_reason")
	val failureReason: String,

	/** Optional bounded diagnostic detail suitable for support export. */
	@ColumnInfo(name = "failure_detail")
	val failureDetail: String? = null,

	@ColumnInfo(name = "quarantined_at")
	val quarantinedAt: Long,
)
