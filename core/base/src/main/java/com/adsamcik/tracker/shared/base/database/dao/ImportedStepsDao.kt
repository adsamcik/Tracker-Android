package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsEntryEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsManifestEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedStepsRunEntity

/**
 * Dormant source-local storage, not an import command. A future authoritative writer must own the
 * whole entry transaction and recheck integrity, destination ownership, retention and deletion.
 * ABORT preserves existing identity/content; conflicts must never silently overwrite a receipt.
 */
@Dao
interface ImportedStepsDao {
	/** Inserts original entry identity without replacing conflicting content. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertEntry(entry: ImportedStepsEntryEntity)

	/** Inserts exact physical membership under an existing imported entry. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRun(run: ImportedStepsRunEntity)

	/** Inserts immutable foreign capture attribution under an existing imported run. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertManifest(manifest: ImportedStepsManifestEntity)

	/** Inserts one bounded immutable-manifest batch; any conflict aborts the owning entry transaction. */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertManifests(manifests: List<ImportedStepsManifestEntity>)

	/** Reads the exact original portable entry identity, not a timestamp overlap. */
	@Query("SELECT * FROM imported_steps_entry WHERE identity = :identity")
	suspend fun entry(identity: String): ImportedStepsEntryEntity?

	/** Reads the exact original portable run identity and deletion-scope mapping. */
	@Query("SELECT * FROM imported_steps_run WHERE identity = :identity")
	suspend fun run(identity: String): ImportedStepsRunEntity?

	/** Reads immutable manifests in canonical revision order for one exact portable run. */
	@Query("SELECT * FROM imported_steps_manifest WHERE run_identity = :identity ORDER BY revision")
	suspend fun manifests(identity: String): List<ImportedStepsManifestEntity>

	/** Bounded original entry lookup for one product/admission batch. */
	@Query("SELECT * FROM imported_steps_entry WHERE identity IN (:identities) LIMIT :limit")
	suspend fun entries(identities: List<String>, limit: Int): List<ImportedStepsEntryEntity>

	/** Checks raw SQLite values before Room can narrow Boolean, Int, or Float presentation fields. */
	@Query("""
		SELECT DISTINCT run.entry_identity FROM imported_steps_run AS run
		LEFT JOIN session_segment AS segment ON segment.id = run.session_segment_id
		WHERE run.entry_identity IN (:entryIdentities) AND (
			(run.app_drain_complete IS NOT 0 AND run.app_drain_complete IS NOT 1) OR
			(run.stop_complete IS NOT 0 AND run.stop_complete IS NOT 1) OR
			(run.has_unresolved_provider_range IS NOT 0 AND run.has_unresolved_provider_range IS NOT 1) OR
			typeof(run.app_drain_complete) != 'integer' OR typeof(run.stop_complete) != 'integer' OR
			typeof(run.has_unresolved_provider_range) != 'integer' OR
			segment.id IS NULL OR segment.sample_count IS NOT 0 OR typeof(segment.sample_count) != 'integer' OR
			segment.distance_m IS NOT 0 OR segment.steps IS NOT NULL OR segment.primary_activity IS NOT NULL OR
			segment.activity_confidence IS NOT NULL OR segment.has_distance_anomaly IS NOT 0 OR
			typeof(segment.has_distance_anomaly) != 'integer' OR segment.inference_version IS NOT NULL
		)
	""")
	suspend fun malformedReadEntryIds(entryIdentities: List<String>): List<String>

	/** Logical recency is the newest surviving physical member, never the original envelope. */
	@Query("""
		SELECT entry.* FROM imported_steps_entry AS entry
		JOIN imported_steps_run AS run ON run.entry_identity = entry.identity
		WHERE NOT EXISTS (
			SELECT 1 FROM imported_steps_run AS newer
			WHERE newer.entry_identity = entry.identity AND (
				newer.start_time_ms > run.start_time_ms OR
				(newer.start_time_ms = run.start_time_ms AND
				 COALESCE(newer.session_segment_id, 0) > COALESCE(run.session_segment_id, 0)) OR
				(newer.start_time_ms = run.start_time_ms AND
				 COALESCE(newer.session_segment_id, 0) = COALESCE(run.session_segment_id, 0) AND
				 newer.identity > run.identity)
			)
		)
		ORDER BY run.start_time_ms DESC, run.session_segment_id DESC, entry.identity DESC LIMIT :limit
	""")
	suspend fun recentEntries(limit: Int): List<ImportedStepsEntryEntity>

	/** Bounded exact physical membership, including only retained imported rows. */
	@Query("SELECT * FROM imported_steps_run WHERE entry_identity IN (:entryIdentities) " +
		"ORDER BY start_time_ms, identity LIMIT :limit")
	suspend fun runsForEntries(entryIdentities: List<String>, limit: Int): List<ImportedStepsRunEntity>

	/** Bounded exact reverse presentation binding lookup. */
	@Query("SELECT * FROM imported_steps_run WHERE session_segment_id IN (:segmentIds) LIMIT :limit")
	suspend fun runsForSegmentIds(segmentIds: List<Long>, limit: Int): List<ImportedStepsRunEntity>

	/** Bounded immutable foreign manifest batch. */
	@Query("SELECT * FROM imported_steps_manifest WHERE run_identity IN (:runIdentities) " +
		"ORDER BY run_identity, revision LIMIT :limit")
	suspend fun manifestsForRuns(runIdentities: List<String>, limit: Int): List<ImportedStepsManifestEntity>

	/** Newest-first stable keyset page; callers request limit plus one to detect truncation. */
	@Query("SELECT * FROM imported_steps_entry WHERE :beforeStartTimeMs IS NULL OR " +
		"start_time_ms < :beforeStartTimeMs OR (start_time_ms = :beforeStartTimeMs AND identity < :beforeIdentity) " +
		"ORDER BY start_time_ms DESC, identity DESC LIMIT :limit")
	suspend fun entryPage(beforeStartTimeMs: Long?, beforeIdentity: String?, limit: Int): List<ImportedStepsEntryEntity>

	/** Complete bounded export selection, in canonical oldest-first order. */
	@Query("SELECT * FROM imported_steps_entry WHERE start_time_ms < :toExclusiveMs AND end_time_ms > :fromInclusiveMs " +
		"ORDER BY start_time_ms, identity LIMIT :limit")
	suspend fun entriesOverlapping(fromInclusiveMs: Long, toExclusiveMs: Long, limit: Int): List<ImportedStepsEntryEntity>

	/** Detects cross-entry reuse of a run's original deletion scope before inserting any data. */
	@Query("SELECT * FROM imported_steps_run WHERE deletion_scope_digest IN (:digests) LIMIT :limit")
	suspend fun runsForDeletionScopes(digests: List<String>, limit: Int): List<ImportedStepsRunEntity>

	/** Bounded exact imported identity partition for source-local retention. */
	@Query("SELECT * FROM imported_steps_run WHERE identity IN (:identities) LIMIT :limit")
	suspend fun runsForIdentities(identities: List<String>, limit: Int): List<ImportedStepsRunEntity>

	/** Removes only the authenticated physical member; manifest rows cascade, siblings remain. */
	@Query("DELETE FROM imported_steps_run WHERE identity = :identity AND entry_identity = :entryIdentity " +
		"AND session_segment_id = :sessionSegmentId AND retained_checksum = :expectedChecksum")
	suspend fun deleteRunExact(
		identity: String, entryIdentity: String, sessionSegmentId: Long, expectedChecksum: String,
	): Int

	/** Removes the original envelope only when no retained physical member remains. */
	@Query("DELETE FROM imported_steps_entry WHERE identity = :identity AND NOT EXISTS " +
		"(SELECT 1 FROM imported_steps_run WHERE entry_identity = :identity)")
	suspend fun deleteEntryIfEmpty(identity: String): Int

	/** Retention-only compare-and-set after exact surviving facts and original receipt authenticate. */
	@Query("UPDATE imported_steps_run SET retained_checksum = :newChecksum WHERE identity = :identity " +
		"AND retained_checksum = :expectedChecksum AND session_segment_id = :sessionSegmentId")
	suspend fun updateRetainedChecksum(
		identity: String, expectedChecksum: String, sessionSegmentId: Long, newChecksum: String,
	): Int

	/** Full collected-data clear only; cascades the foreign metadata, never replaces source fences. */
	@Query("DELETE FROM imported_steps_entry")
	fun deleteAll()
}
