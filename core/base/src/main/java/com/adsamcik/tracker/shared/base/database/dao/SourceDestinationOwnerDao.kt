package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity

@Dao
interface SourceDestinationOwnerDao {
	@Query(
		"SELECT * FROM source_destination_owner " +
			"WHERE source_kind = :sourceKind AND destination = :destination",
	)
	suspend fun get(sourceKind: Int, destination: String): SourceDestinationOwnerEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(entity: SourceDestinationOwnerEntity): Long

	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_destination_owner " +
			"WHERE source_kind = :sourceKind AND destination = :destination " +
			"AND owner = :owner AND owner_generation = :ownerGeneration)",
	)
	suspend fun isExactOwner(
		sourceKind: Int,
		destination: String,
		owner: String,
		ownerGeneration: Long,
	): Boolean

	/**
	 * Low-level monotonic owner transition seam.
	 *
	 * This CAS is not a cutover coordinator: queued-command fencing, segment-effective provenance,
	 * rollback writer binding, and post-deletion lane reconstruction must be established around it.
	 * There is intentionally no production caller until those gates are implemented and verified.
	 */
	@Query(
		"UPDATE source_destination_owner SET owner = :newOwner, " +
			"owner_generation = :newOwnerGeneration, updated_at_ms = :updatedAtMs " +
			"WHERE source_kind = :sourceKind AND destination = :destination " +
			"AND owner = :expectedOwner AND owner_generation = :expectedOwnerGeneration " +
			"AND :newOwnerGeneration > owner_generation",
	)
	suspend fun compareAndSetOwner(
		sourceKind: Int,
		destination: String,
		expectedOwner: String,
		expectedOwnerGeneration: Long,
		newOwner: String,
		newOwnerGeneration: Long,
		updatedAtMs: Long,
	): Int
}
