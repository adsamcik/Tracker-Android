package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.LocationFileIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchiveEntity
import com.adsamcik.tracker.shared.base.database.data.LocationFileArchivePointEntity
import java.util.UUID

/**
 * Persists Location point/file identities and import receipts so overlapping files can merge
 * without duplicating measurements.
 */
@Dao
interface LocationFileIdentityDao {
	/**
	 * Reads `location_file_identity` rows. Query conditions include `portable_id = :portableId`.
	 * Returns null when no row matches.
	 */
	@Query("SELECT * FROM location_file_identity WHERE portable_id = :portableId")
	suspend fun byPortableId(portableId: String): LocationFileIdentityEntity?

	/** Exact bounded lookup for a Location file preview or import batch. */
	@Query("SELECT * FROM location_file_identity WHERE portable_id IN (:portableIds)")
	suspend fun byPortableIds(portableIds: List<String>): List<LocationFileIdentityEntity>

	/**
	 * Reads `location_file_identity` rows. Query conditions include `sample_id = :sampleId`. Returns
	 * null when no row matches.
	 */
	@Query("SELECT * FROM location_file_identity WHERE sample_id = :sampleId")
	suspend fun bySampleId(sampleId: Long): LocationFileIdentityEntity?

	/**
	 * Inserts [LocationFileIdentityEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertIfAbsent(value: LocationFileIdentityEntity): Long

	/**
	 * Reads `location_file_archive` rows. Query conditions include `identity = :identity`. Returns
	 * null when no row matches.
	 */
	@Query("SELECT * FROM location_file_archive WHERE identity = :identity")
	suspend fun archive(identity: String): LocationFileArchiveEntity?

	/**
	 * Inserts [LocationFileArchiveEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertArchiveIfAbsent(value: LocationFileArchiveEntity): Long

	/**
	 * Reads `location_file_archive_point` rows. Query conditions include `archive_identity =
	 * :identity`, `ordinal IN (:ordinals)`.
	 */
	@Query("SELECT * FROM location_file_archive_point WHERE archive_identity = :identity " +
		"AND ordinal IN (:ordinals)")
	suspend fun archivePointsByOrdinal(identity: String, ordinals: List<Int>): List<LocationFileArchivePointEntity>

	/** Exact membership for already-qualified displayed points; never matches a timestamp. */
	@Query("SELECT DISTINCT p.sample_id FROM location_file_archive_point p " +
		"JOIN location_file_identity i ON i.portable_id = p.portable_id AND i.sample_id = p.sample_id " +
		"WHERE p.archive_identity = :identity AND p.sample_id IN (:sampleIds)")
	suspend fun sampleIdsInArchive(identity: String, sampleIds: List<Long>): List<Long>

	/**
	 * Inserts [LocationFileArchivePointEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertArchivePointsIfAbsent(values: List<LocationFileArchivePointEntity>): List<Long>

	/**
	 * Reads `location_file_archive_point` rows. Query conditions include `archive_identity =
	 * :identity`, `ordinal > :afterOrdinal`. Orders by `ordinal`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM location_file_archive_point WHERE archive_identity = :identity " +
		"AND ordinal > :afterOrdinal ORDER BY ordinal LIMIT :limit")
	suspend fun archivePointPage(identity: String, afterOrdinal: Int, limit: Int): List<LocationFileArchivePointEntity>

	/**
	 * Counts rows in `location_file_archive_point`. Query conditions include `p.archive_identity =
	 * :identity`. Timestamp bounds use UTC milliseconds.
	 */
	@Query("SELECT COUNT(*) AS membership_count, COALESCE(SUM(CASE WHEN s.id IS NOT NULL " +
		"AND i.sample_id = p.sample_id AND (:retainedFromMs IS NULL OR s.time_ms >= :retainedFromMs) " +
		"THEN 1 ELSE 0 END), 0) AS available_count FROM location_file_archive_point p " +
		"LEFT JOIN effective_location_sample s ON s.id = p.sample_id " +
		"LEFT JOIN location_file_identity i ON i.portable_id = p.portable_id " +
		"WHERE p.archive_identity = :identity")
	suspend fun archivePointCounts(identity: String, retainedFromMs: Long?): LocationFileArchivePointCounts

	/**
	 * Deletes `location_file_archive` rows.
	 */
	@Query("DELETE FROM location_file_archive")
	suspend fun deleteAllArchives()

	/** Same validated bytes reuse their archive. Source identity does not depend on an outer ZIP. */
	@Transaction
	suspend fun recordArchive(value: LocationFileArchiveEntity) {
		insertArchiveIfAbsent(value)
		check(archive(value.identity) == value) { "Location file identity conflicts with saved membership" }
	}

	/** Replay preserves a missing point after deletion rather than reconnecting an old selector. */
	@Transaction
	suspend fun recordArchivePoints(values: List<LocationFileArchivePointEntity>) {
		if (values.isEmpty()) {
			return
		}
		require(values.size <= MAX_LOCATION_ARCHIVE_POINT_BATCH && values.map { it.archiveIdentity }.distinct().size == 1)
		val identity = values.first().archiveIdentity
		val archive = checkNotNull(archive(identity))
		require(values.all { it.ordinal < archive.pointCount })
		insertArchivePointsIfAbsent(values)
		val recorded = archivePointsByOrdinal(identity, values.map { it.ordinal }).associateBy { it.ordinal }
		check(values.all { value ->
			val existing = recorded[value.ordinal]
			existing != null && existing.portableId == value.portableId &&
				(existing.sampleId == null || existing.sampleId == value.sampleId)
		}) { "Location file points conflict with saved membership" }
	}

	/** Assign once; another export or an overlapping import always reads the saved ID. */
	@Transaction
	suspend fun localIdentityFor(sampleId: Long): LocationFileIdentityEntity {
		require(sampleId > 0L)
		bySampleId(sampleId)?.let { return it }
		repeat(LOCATION_ID_ASSIGNMENT_ATTEMPTS) {
			val candidate = LocationFileIdentityEntity(
				portableId = UUID.randomUUID().toString(),
				sampleId = sampleId,
				imported = false,
				reportedOrigin = "unknown",
			)
			if (insertIfAbsent(candidate) != -1L) {
				return candidate
			}
			bySampleId(sampleId)?.let { return it }
		}
		error("Could not assign a Location file ID")
	}
}

/**
 * Saved point-membership cardinalities for a Location archive; reports counts without loading
 * coordinate payloads.
 */
data class LocationFileArchivePointCounts(
	@ColumnInfo(name = "membership_count") val membershipCount: Int,
	@ColumnInfo(name = "available_count") val availableCount: Int,
)

private const val MAX_LOCATION_ARCHIVE_POINT_BATCH = 256
private const val LOCATION_ID_ASSIGNMENT_ATTEMPTS = 3
