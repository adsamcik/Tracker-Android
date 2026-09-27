package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceEventRetentionProofEntity

@Dao
interface SourceEventRetentionProofDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertAll(receipts: List<SourceEventRetentionProofEntity>): List<Long>

	@Query(
		"SELECT * FROM source_event_retention_proof " +
			"WHERE source_kind = :sourceKind AND event_id = :eventId LIMIT 1",
	)
	suspend fun get(sourceKind: Int, eventId: String): SourceEventRetentionProofEntity?

	@Query(
		"DELETE FROM source_event_retention_proof " +
			"WHERE source_kind = :sourceKind AND event_id IN (" +
			"SELECT event_id FROM source_event_wal WHERE source_kind = :sourceKind)",
	)
	suspend fun deleteProofsWithLiveWal(sourceKind: Int): Int

	@Query(
		"DELETE FROM source_event_retention_proof " +
			"WHERE source_kind = :sourceKind AND source_admission_ordinal NOT IN (" +
			"SELECT source_admission_ordinal FROM source_event_retention_proof " +
			"WHERE source_kind = :sourceKind ORDER BY source_admission_ordinal DESC LIMIT :maximumCount)",
	)
	suspend fun trimSourceToNewest(sourceKind: Int, maximumCount: Int): Int

	@Query(
		"SELECT * FROM source_event_retention_proof WHERE source_kind = :sourceKind " +
			"ORDER BY source_admission_ordinal",
	)
	suspend fun allForSource(sourceKind: Int): List<SourceEventRetentionProofEntity>

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

	@Query(
		"DELETE FROM source_event_retention_proof WHERE source_kind = :sourceKind " +
			"AND event_id IN (:eventIds)",
	)
	suspend fun deleteExactSourceEvents(sourceKind: Int, eventIds: List<String>): Int

	@Query(
		"SELECT COUNT(*) FROM source_event_retention_proof WHERE source_kind = :sourceKind " +
			"AND (purpose_eligibility_mask & :capturePurposeMask) != 0",
	)
	suspend fun captureProofCount(
		sourceKind: Int,
		capturePurposeMask: Long,
	): Long

	@Query("SELECT COUNT(*) FROM source_event_retention_proof")
	suspend fun countAll(): Long

	@Query("DELETE FROM source_event_retention_proof")
	fun deleteAll()
}
