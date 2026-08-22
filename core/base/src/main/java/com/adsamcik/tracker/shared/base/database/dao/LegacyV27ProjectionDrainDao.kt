package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionDrainEntity
import com.adsamcik.tracker.shared.base.database.data.LegacyV27ProjectionTargetEntity

@Dao
interface LegacyV27ProjectionDrainDao {
	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun saveDrain(entity: LegacyV27ProjectionDrainEntity)

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun saveTarget(entity: LegacyV27ProjectionTargetEntity)

	@Query("SELECT * FROM legacy_v27_projection_drain WHERE id = 1")
	suspend fun get(): LegacyV27ProjectionDrainEntity?

	@Query(
		"SELECT * FROM legacy_v27_projection_target " +
			"ORDER BY projection_id, projection_version",
	)
	suspend fun targets(): List<LegacyV27ProjectionTargetEntity>

	@Query(
		"SELECT MIN(last_completed_ordinal + 1) FROM legacy_v27_projection_target " +
			"WHERE disposition = 'PENDING' AND last_completed_ordinal < required_through_ordinal",
	)
	suspend fun minimumPendingOrdinal(): Long?

	@Query(
		"SELECT MIN(outbox.admission_ordinal) FROM source_projection_outbox AS outbox " +
			"JOIN legacy_v27_projection_drain AS drain ON drain.id = 1 " +
			"WHERE outbox.admission_ordinal <= drain.cutoff_admission_ordinal " +
			"AND outbox.delivered_at_ms IS NULL AND outbox.terminal_disposition IS NULL",
	)
	suspend fun minimumPendingOutboxOrdinal(): Long?

	@Query(
		"SELECT MIN(wal.admission_ordinal) FROM source_event_wal AS wal " +
			"JOIN legacy_v27_projection_drain AS drain ON drain.id = 1 " +
			"WHERE wal.admission_ordinal <= drain.cutoff_admission_ordinal " +
			"AND drain.status IN ('BLOCKED_UNSUPPORTED_TARGET', 'FAILED_RETRYABLE')",
	)
	suspend fun minimumBlockedWalOrdinal(): Long?

	@Query(
		"SELECT CASE WHEN cutoff_admission_ordinal > 0 THEN cutoff_admission_ordinal + 1 ELSE 1 END " +
			"FROM legacy_v27_projection_drain WHERE id = 1",
	)
	suspend fun liveActivationOrdinal(): Long?

	@Query("DELETE FROM legacy_v27_projection_target")
	fun deleteAllTargets()

	@Query("DELETE FROM legacy_v27_projection_drain")
	fun deleteDrain()
}
