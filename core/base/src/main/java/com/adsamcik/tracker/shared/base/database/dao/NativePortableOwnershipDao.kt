package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Query

/** Metadata-only reverse lookup. A matching row is never sufficient to return a measurement. */
@Dao
interface NativePortableOwnershipDao {
	/**
	 * Reads distinct `owner.logical_tracking_id` values from `native_portable_ownership`. Query
	 * conditions include `owner.source_kind = :sourceKind`, `owner.opaque_identity IN (:identities)`,
	 * `source.source_kind = :sourceKind`, `source.purpose = 'SESSION_CAPTURE'`. Orders by
	 * `owner.logical_tracking_id`. Returns at most `limit` rows.
	 */
	@Query("""
		SELECT DISTINCT owner.logical_tracking_id
		FROM native_portable_ownership owner
		WHERE owner.source_kind = :sourceKind AND owner.opaque_identity IN (:identities)
		AND (
		  EXISTS (SELECT 1 FROM session_manifest_version manifest
		    INNER JOIN session_manifest_source source
		      ON source.logical_tracking_id = manifest.logical_tracking_id
		     AND source.manifest_revision = manifest.manifest_revision
		    WHERE manifest.logical_tracking_id = owner.logical_tracking_id
		      AND manifest.service_run_id = owner.service_run_id
		      AND source.source_kind = :sourceKind AND source.purpose = 'SESSION_CAPTURE'
		      AND source.persistence_eligible = 1)
		  OR (:sourceKind = 2 AND EXISTS (SELECT 1 FROM activity_captured_window_revision fact
		    WHERE fact.logical_tracking_id = owner.logical_tracking_id AND fact.service_run_id = owner.service_run_id))
		  OR (:sourceKind = 4 AND EXISTS (SELECT 1 FROM pressure_fact_revision fact
		    WHERE fact.logical_tracking_id = owner.logical_tracking_id AND fact.service_run_id = owner.service_run_id))
		)
		ORDER BY owner.logical_tracking_id LIMIT :limit
	""")
	suspend fun ownerLogicalIds(sourceKind: Int, identities: List<String>, limit: Int): List<String>

	/** One seed per exact owner; the source selector expands and checks all original members. */
	@Query("""
		SELECT segment.* FROM session_segment segment
		WHERE segment.logical_tracking_id IN (:logicalIds)
		AND segment.id = (SELECT MIN(seed.id) FROM session_segment seed
		  WHERE seed.logical_tracking_id = segment.logical_tracking_id)
		ORDER BY segment.logical_tracking_id LIMIT :limit
	""")
	suspend fun ownerSeeds(logicalIds: List<String>,
        limit: Int): List<com.adsamcik.tracker.shared.base.database.data.SessionSegment>

	/**
	 * Deletes `native_portable_ownership` rows.
	 */
	@Query("DELETE FROM native_portable_ownership")
	fun deleteAll()
}
