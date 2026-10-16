package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity

/**
 * Stores payload-free event retention witnesses and removes witnesses whose original event is
 * still available or no longer referenced.
 */
@Dao
interface SourceEventRetentionProofDao {
	/**
	 * Inserts [SourceEventRetentionProofEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertAll(receipts: List<SourceEventRetentionProofEntity>): List<Long>

	/**
	 * Reads `source_event_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `event_id = :eventId`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM source_event_retention_proof " +
			"WHERE source_kind = :sourceKind AND event_id = :eventId LIMIT 1",
	)
	suspend fun get(sourceKind: Int, eventId: String): SourceEventRetentionProofEntity?

	/**
	 * Deletes `source_event_retention_proof` rows matching the supplied scope. Query conditions
	 * include `source_kind = :sourceKind`. Returns the affected row count.
	 */
	@Query(
		"DELETE FROM source_event_retention_proof " +
			"WHERE source_kind = :sourceKind AND event_id IN (" +
			"SELECT event_id FROM source_event_wal WHERE source_kind = :sourceKind)",
	)
	suspend fun deleteProofsWithLiveWal(sourceKind: Int): Int

	/**
	 * Deletes `source_event_retention_proof` rows matching the supplied scope. Query conditions
	 * include `source_kind = :sourceKind`. Returns the affected row count.
	 */
	@Query(
		"DELETE FROM source_event_retention_proof " +
			"WHERE source_kind = :sourceKind AND source_admission_ordinal NOT IN (" +
			"SELECT source_admission_ordinal FROM source_event_retention_proof " +
			"WHERE source_kind = :sourceKind ORDER BY source_admission_ordinal DESC LIMIT :maximumCount)",
	)
	suspend fun trimSourceToNewest(sourceKind: Int, maximumCount: Int): Int

	/**
	 * Reads `source_event_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`. Orders by `source_admission_ordinal`.
	 */
	@Query(
		"SELECT * FROM source_event_retention_proof WHERE source_kind = :sourceKind " +
			"ORDER BY source_admission_ordinal",
	)
	suspend fun allForSource(sourceKind: Int): List<SourceEventRetentionProofEntity>

	/**
	 * Reads `source_event_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `source_admission_ordinal > :afterAdmissionOrdinal`. Orders by
	 * `source_admission_ordinal`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM source_event_retention_proof WHERE source_kind = :sourceKind " +
			"AND (:afterAdmissionOrdinal IS NULL OR " +
			"source_admission_ordinal > :afterAdmissionOrdinal) " +
			"ORDER BY source_admission_ordinal LIMIT :limit",
	)
	suspend fun sourceErasePage(
		sourceKind: Int,
		afterAdmissionOrdinal: Long?,
		limit: Int,
	): List<SourceEventRetentionProofEntity>

	/**
	 * Reads `source_event_retention_proof` rows. Query conditions include `source_kind =
	 * :sourceKind`, `source_admission_ordinal > :afterAdmissionOrdinal`. Orders by
	 * `source_admission_ordinal`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM source_event_retention_proof WHERE source_kind = :sourceKind " +
			"AND (purpose_eligibility_mask & :capturePurposeMask) != 0 " +
			"AND (:afterAdmissionOrdinal IS NULL OR " +
			"source_admission_ordinal > :afterAdmissionOrdinal) " +
			"ORDER BY source_admission_ordinal LIMIT :limit",
	)
	suspend fun sourceCaptureErasePage(
		sourceKind: Int,
		capturePurposeMask: Long,
		afterAdmissionOrdinal: Long?,
		limit: Int,
	): List<SourceEventRetentionProofEntity>

	/**
	 * Deletes `source_event_retention_proof` rows matching the supplied scope. Query conditions
	 * include `source_kind = :sourceKind`, `event_id IN (:eventIds)`. Returns the affected row count.
	 */
	@Query(
		"DELETE FROM source_event_retention_proof WHERE source_kind = :sourceKind " +
			"AND event_id IN (:eventIds)",
	)
	suspend fun deleteExactSourceEvents(sourceKind: Int, eventIds: List<String>): Int

	/**
	 * Counts rows in `source_event_retention_proof`. Query conditions include `source_kind =
	 * :sourceKind`.
	 */
	@Query(
		"SELECT COUNT(*) FROM source_event_retention_proof WHERE source_kind = :sourceKind " +
			"AND (purpose_eligibility_mask & :capturePurposeMask) != 0",
	)
	suspend fun captureProofCount(
		sourceKind: Int,
		capturePurposeMask: Long,
	): Long

	/**
	 * Counts rows in `source_event_retention_proof`.
	 */
	@Query("SELECT COUNT(*) FROM source_event_retention_proof")
	suspend fun countAll(): Long

	/**
	 * Deletes `source_event_retention_proof` rows.
	 */
	@Query("DELETE FROM source_event_retention_proof")
	fun deleteAll()
}
