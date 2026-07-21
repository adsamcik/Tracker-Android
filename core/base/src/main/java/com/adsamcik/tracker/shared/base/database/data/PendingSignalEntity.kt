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
			value = ["session_id", "id"],
			name = "idx_pending_signal_session_time",
		),
		Index(
			value = ["signal_id"],
			unique = true,
			name = "idx_pending_signal_signal_id",
		),
		Index(
			value = ["claim_token", "claim_expires_at", "id"],
			name = "idx_pending_signal_claimable",
		),
	],
)
data class PendingSignalEntity(
	@PrimaryKey(autoGenerate = true)
	val id: Long = 0,

	/**
	 * Stable producer-assigned identity for this logical signal.
	 *
	 * New records must carry a non-empty value.  The default only keeps callers
	 * that construct historical/fixture rows source-compatible; the unique index
	 * deliberately turns a forgotten identity into a visible insert conflict
	 * instead of silently admitting duplicate work.
	 */
	@ColumnInfo(name = "signal_id", defaultValue = "''")
	val signalId: String = "",

	@ColumnInfo(name = "session_id")
	val sessionId: Long,

	/** Version of the durable serialization envelope; zero denotes legacy JSON. */
	@ColumnInfo(name = "envelope_version", defaultValue = "0")
	val envelopeVersion: Int = 0,

	/** SHA-256 checksum of [signalJson] for versioned envelopes. */
	@ColumnInfo(name = "payload_checksum")
	val payloadChecksum: String? = null,

	@ColumnInfo(name = "signal_json")
	val signalJson: String,

	@ColumnInfo(name = "created_at")
	val createdAt: Long,

	/** Deletion epoch captured when this signal was admitted to the durable WAL. */
	@ColumnInfo(name = "captured_epoch", defaultValue = "0")
	val capturedEpoch: Long = 0L,

	/** Provider acquisition time used to enforce the retained-from boundary on delayed/replayed data. */
	@ColumnInfo(name = "acquired_at_ms", defaultValue = "0")
	val acquiredAtMs: Long = 0L,

	/** Opaque recovery-owner token. Null means this row is currently unclaimed. */
	@ColumnInfo(name = "claim_token")
	val claimToken: String? = null,

	/** Wall-clock lease deadline for [claimToken]; null means no active lease. */
	@ColumnInfo(name = "claim_expires_at")
	val claimExpiresAt: Long? = null,

	/** Number of recovery leases acquired for this row. */
	@ColumnInfo(name = "delivery_attempt_count", defaultValue = "0")
	val deliveryAttemptCount: Int = 0,
)
