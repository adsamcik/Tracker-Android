package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity

@Dao
interface SourceEventWalDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIgnoringDuplicate(entity: SourceEventWalEntity): Long

	@Query("SELECT * FROM source_event_wal WHERE event_id = :eventId LIMIT 1")
	suspend fun getByEventId(eventId: String): SourceEventWalEntity?

	@Query(
		"SELECT event_id, admission_ordinal, provider_dedup_key, source_instance_id, " +
			"registration_generation, source_sequence, payload_version, payload_checksum " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND provider_dedup_key = :providerDedupKey LIMIT 1",
	)
	suspend fun identityByProviderDedupKey(
		sourceKind: Int,
		providerDedupKey: String,
	): SourceEventIdentityRow?

	@Query(
		"SELECT event_id, admission_ordinal, provider_dedup_key, source_instance_id, " +
			"registration_generation, source_sequence, payload_version, payload_checksum " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND source_instance_id = :sourceInstanceId AND source_sequence = :sourceSequence LIMIT 1",
	)
	suspend fun identityBySourceSequence(
		sourceKind: Int,
		sourceInstanceId: String,
		sourceSequence: Long,
	): SourceEventIdentityRow?

	@Query(
		"SELECT * FROM source_event_wal " +
			"WHERE source_kind = :sourceKind AND provider_dedup_key = :providerDedupKey LIMIT 1",
	)
	suspend fun getByProviderDedupKey(sourceKind: Int, providerDedupKey: String): SourceEventWalEntity?

	@Query(
		"SELECT * FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND source_instance_id = :sourceInstanceId AND source_sequence = :sourceSequence LIMIT 1",
	)
	suspend fun getBySourceSequence(
		sourceKind: Int,
		sourceInstanceId: String,
		sourceSequence: Long,
	): SourceEventWalEntity?

	@Query(
		"SELECT * FROM source_event_wal WHERE admission_ordinal > :afterOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun eventsAfter(afterOrdinal: Long, limit: Int): List<SourceEventWalEntity>

	@Query("SELECT MAX(admission_ordinal) FROM source_event_wal")
	suspend fun maximumAdmissionOrdinal(): Long?

	@Query("SELECT COUNT(*) FROM source_event_wal")
	suspend fun countAll(): Long

	@Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM source_event_wal")
	suspend fun payloadBytes(): Long

	@Query(
		"DELETE FROM source_event_wal WHERE admission_ordinal IN (" +
			"SELECT admission_ordinal FROM source_event_wal " +
			"WHERE created_at_ms < :createdBeforeMs AND admission_ordinal <= :safeOrdinal " +
			"ORDER BY created_at_ms, admission_ordinal LIMIT :limit)",
	)
	suspend fun deleteProjectedBatch(
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
	): Int

	@Query("DELETE FROM source_event_wal")
	fun deleteAll()
}

/** Covering projection used by duplicate admission checks so payload BLOBs are never read. */
data class SourceEventIdentityRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "provider_dedup_key") val providerDedupKey: String?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
)
