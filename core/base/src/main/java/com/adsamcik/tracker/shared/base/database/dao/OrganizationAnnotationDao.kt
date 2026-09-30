package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.adsamcik.tracker.shared.base.database.data.OrganizationAnnotationEntity

/** Overrides suppress virtual adapters before range selection and limiting in the composed query. */
@Dao
interface OrganizationAnnotationDao {
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insert(row: OrganizationAnnotationEntity)
	@Update
	suspend fun update(row: OrganizationAnnotationEntity): Int
	@Query("SELECT * FROM organization_annotation WHERE local_id = :id")
	suspend fun byId(id: String): OrganizationAnnotationEntity?
	@Query("SELECT * FROM organization_annotation WHERE recording_adapter_key = :key")
	suspend fun byAdapter(key: String): OrganizationAnnotationEntity?

	@Query("SELECT * FROM organization_annotation WHERE recording_adapter_key IN (:keys)")
	suspend fun byAdapters(keys: List<String>): List<OrganizationAnnotationEntity>

	@Query("SELECT * FROM organization_annotation WHERE local_id IN (:ids)")
	suspend fun byIds(ids: List<String>): List<OrganizationAnnotationEntity>
	@Query("DELETE FROM organization_annotation WHERE local_id = :id")
	suspend fun delete(id: String): Int

	@Query("""
		SELECT * FROM (
			SELECT a.local_id AS localId, a.recording_adapter_key AS adapterKey,
				a.revision, a.name, a.activity_label_id AS activityLabelId,
				COALESCE(a.display_zone_id, (SELECT m.zone_id FROM session_manifest_version m WHERE m.logical_tracking_id = l.logical_tracking_id ORDER BY m.manifest_revision LIMIT 1)) AS displayZoneId,
				COALESCE(a.start_time_ms, l.started_at_ms, s.start_time_ms) AS fromMs,
				COALESCE(a.end_time_ms, l.cutoff_at_ms, s.end_time_ms) AS toMs,
				CASE WHEN l.state NOT IN ('FINALIZED','CLOSED','FAILED') THEN 1 ELSE 0 END AS isActive,
				CASE WHEN l.logical_tracking_id IS NOT NULL THEN CASE WHEN l.state IN ('FINALIZED','CLOSED') THEN 1 ELSE 0 END
					WHEN s.id IS NOT NULL OR a.recording_adapter_key IS NULL THEN 1 ELSE 0 END AS isCompleted,
				COALESCE(s.id, (SELECT MIN(r.session_segment_id) FROM source_service_run r
					WHERE r.logical_tracking_id = l.logical_tracking_id)) AS originalSegmentId,
				CASE WHEN a.start_time_ms IS NOT NULL AND a.end_time_ms IS NOT NULL THEN 0
					WHEN l.logical_tracking_id IS NOT NULL THEN CASE WHEN l.started_at_ms < 0 OR l.started_elapsed_nanos < 0 OR l.clock_domain_id IN ('', 'LEGACY_UNKNOWN') OR (l.state IN ('FINALIZED','CLOSED','FAILED') AND (l.cutoff_at_ms IS NULL OR l.cutoff_at_ms <= l.started_at_ms OR l.cutoff_elapsed_nanos IS NULL OR l.cutoff_elapsed_nanos < l.started_elapsed_nanos)) THEN 1 ELSE 0 END
					ELSE 0 END AS timeUncertain,
				a.imported_source_selection AS importedSourceSelection,
				a.imported_activity_label AS importedActivityLabel
			FROM organization_annotation a
			LEFT JOIN logical_tracking_session l ON a.recording_adapter_key = 'logical:' || l.logical_tracking_id
			LEFT JOIN session_segment s ON a.recording_adapter_key = 'segment:' || s.id
			WHERE a.hidden = 0
			UNION ALL
			SELECT NULL, 'logical:' || l.logical_tracking_id, 0, NULL, NULL, (SELECT m.zone_id FROM session_manifest_version m WHERE m.logical_tracking_id = l.logical_tracking_id ORDER BY m.manifest_revision LIMIT 1),
				l.started_at_ms, l.cutoff_at_ms,
				CASE WHEN l.state NOT IN ('FINALIZED','CLOSED','FAILED') THEN 1 ELSE 0 END,
				CASE WHEN l.state IN ('FINALIZED','CLOSED') THEN 1 ELSE 0 END,
				(SELECT MIN(r.session_segment_id) FROM source_service_run r
					WHERE r.logical_tracking_id = l.logical_tracking_id),
				CASE WHEN l.started_at_ms < 0 OR l.started_elapsed_nanos < 0 OR l.clock_domain_id IN ('', 'LEGACY_UNKNOWN') OR (l.state IN ('FINALIZED','CLOSED','FAILED') AND (l.cutoff_at_ms IS NULL OR l.cutoff_at_ms <= l.started_at_ms OR l.cutoff_elapsed_nanos IS NULL OR l.cutoff_elapsed_nanos < l.started_elapsed_nanos)) THEN 1 ELSE 0 END,
				NULL, NULL
			FROM logical_tracking_session l
			WHERE NOT EXISTS (SELECT 1 FROM organization_annotation a
				WHERE a.recording_adapter_key = 'logical:' || l.logical_tracking_id)
			UNION ALL
			SELECT NULL, 'segment:' || s.id, 0, NULL, NULL, NULL,
				s.start_time_ms, s.end_time_ms, 0, 1, s.id,
				CASE WHEN s.start_time_ms < 0 OR s.end_time_ms <= s.start_time_ms THEN 1 ELSE 0 END,
				NULL, NULL
			FROM session_segment s
			WHERE s.logical_tracking_id IS NULL AND s.service_run_id IS NULL
				AND NOT EXISTS (SELECT 1 FROM organization_annotation a
					WHERE a.recording_adapter_key = 'segment:' || s.id)
		) WHERE (:fromMs IS NULL OR (fromMs < :toMs AND (toMs IS NULL OR toMs > :fromMs)))
		AND ((:selectedLocalId IS NULL AND :selectedAdapterKey IS NULL) OR (adapterKey IS NULL AND localId = :selectedLocalId) OR adapterKey = :selectedAdapterKey)
		ORDER BY fromMs DESC, adapterKey, localId LIMIT :limit OFFSET :offset
	""")
	suspend fun visible(fromMs: Long?, toMs: Long?, limit: Int, offset: Int = 0, selectedLocalId: String? = null, selectedAdapterKey: String? = null): List<OrganizationSessionRow>

	@Query("SELECT zone_id FROM session_manifest_version WHERE 'logical:' || logical_tracking_id = :key ORDER BY manifest_revision LIMIT 1")
	suspend fun recordingZone(key: String): String?

	/** Called only inside the existing approved trip-retention transaction. */
	@Query("DELETE FROM organization_annotation WHERE recording_adapter_key IS NULL AND end_time_ms <= :cutoff")
	suspend fun purgeFreeBefore(cutoff: Long)
	@Query("""
		UPDATE organization_annotation SET hidden = 1, name = NULL, activity_label_id = NULL,
			start_time_ms = NULL, end_time_ms = NULL, display_zone_id = NULL,
			imported_source_selection = NULL, imported_activity_label = NULL,
			revision = revision + 1, updated_at_ms = :now
		WHERE hidden = 0 AND recording_adapter_key IS NOT NULL AND COALESCE(end_time_ms,
			(SELECT cutoff_at_ms FROM logical_tracking_session WHERE 'logical:' || logical_tracking_id = recording_adapter_key),
			(SELECT end_time_ms FROM session_segment WHERE 'segment:' || id = recording_adapter_key)) <= :cutoff
	""")
	suspend fun suppressExpiredOverrides(cutoff: Long, now: Long)
	@Query("""
		DELETE FROM organization_annotation WHERE hidden = 1
		AND NOT EXISTS (SELECT 1 FROM logical_tracking_session WHERE 'logical:' || logical_tracking_id = recording_adapter_key)
		AND NOT EXISTS (SELECT 1 FROM session_segment WHERE 'segment:' || id = recording_adapter_key)
	""")
	suspend fun pruneUnusedSuppression()
}

data class OrganizationSessionRow(
	val localId: String?, val adapterKey: String?, val revision: Long,
	val name: String?, val activityLabelId: Long?, val displayZoneId: String?,
	val fromMs: Long?, val toMs: Long?, val isActive: Boolean, val isCompleted: Boolean,
	val originalSegmentId: Long?,
	val timeUncertain: Boolean,
	val importedSourceSelection: String? = null,
	val importedActivityLabel: String? = null,
)
