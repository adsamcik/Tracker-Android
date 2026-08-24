package com.adsamcik.tracker.shared.base.database.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton consistency token for the raw evidence source.
 *
 * Readers capture [revision] before a bounded source query and verify it afterwards. Writers bump
 * it in the same Room transaction as an evidence insert or deletion, so a future raster can never
 * publish a mixed snapshot as if it were coherent.
 */
@Entity(tableName = "source_evidence_state")
data class SourceEvidenceState(
	@PrimaryKey
	val id: Int = SINGLETON_ID,
	val revision: Long = 0L,
	/** Monotonic deletion epoch mirrored from durable preferences for Room-transaction checks. */
	@ColumnInfo(name = "collected_data_epoch")
	val collectedDataEpoch: Long = 0L,
	/** Lower raw-history boundary mirrored from durable preferences; null means no raw cutoff. */
	@ColumnInfo(name = "retained_from_ms")
	val retainedFromMs: Long? = null,
	/** Highest source-event ordinal made unreachable by a committed full-data deletion. */
	@ColumnInfo(name = "deleted_source_event_high_water_ordinal", defaultValue = "0")
	val deletedSourceEventHighWaterOrdinal: Long = 0L,
	@ColumnInfo(name = "updated_at_ms")
	val updatedAtMs: Long = 0L,
) {
	companion object {
		const val SINGLETON_ID = 1
	}
}
