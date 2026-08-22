package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity

@Dao
interface SourceRegistrationStateDao {
	@Query("SELECT * FROM source_registration_state WHERE source_kind = :sourceKind AND owner_scope = :ownerScope")
	suspend fun get(sourceKind: Int, ownerScope: String): SourceRegistrationStateEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(entity: SourceRegistrationStateEntity): Long

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun replace(entity: SourceRegistrationStateEntity)

	@Query(
		"UPDATE source_registration_state SET next_sequence = next_sequence + 1, updated_at_ms = :updatedAtMs " +
			"WHERE source_kind = :sourceKind AND owner_scope = :ownerScope",
	)
	suspend fun incrementSequence(sourceKind: Int, ownerScope: String, updatedAtMs: Long): Int

	@Query(
		"UPDATE source_registration_state SET next_sequence = next_sequence + :count, " +
			"updated_at_ms = :updatedAtMs WHERE source_kind = :sourceKind AND owner_scope = :ownerScope",
	)
	suspend fun incrementSequenceBy(
		sourceKind: Int,
		ownerScope: String,
		count: Int,
		updatedAtMs: Long,
	): Int

	@Transaction
	suspend fun allocateSequence(sourceKind: Int, ownerScope: String, updatedAtMs: Long): SourceRegistrationStateEntity {
		check(incrementSequence(sourceKind, ownerScope, updatedAtMs) == 1) {
			"Source registration is not reserved"
		}
		val updated = requireNotNull(get(sourceKind, ownerScope))
		return updated.copy(nextSequence = updated.nextSequence - 1L)
	}

	@Transaction
	suspend fun allocateSequenceRange(
		sourceKind: Int,
		ownerScope: String,
		count: Int,
		updatedAtMs: Long,
	): LongRange {
		require(count > 0)
		val current = requireNotNull(get(sourceKind, ownerScope)) {
			"Source registration is not reserved"
		}
		check(current.nextSequence <= Long.MAX_VALUE - count.toLong()) {
			"Source sequence exhausted"
		}
		check(incrementSequenceBy(sourceKind, ownerScope, count, updatedAtMs) == 1) {
			"Source registration is not reserved"
		}
		return current.nextSequence..(current.nextSequence + count - 1L)
	}

	@Query("DELETE FROM source_registration_state")
	fun deleteAll()

	@Query("DELETE FROM source_registration_state WHERE source_kind = :sourceKind AND owner_scope = :ownerScope")
	suspend fun delete(sourceKind: Int, ownerScope: String): Int
}
