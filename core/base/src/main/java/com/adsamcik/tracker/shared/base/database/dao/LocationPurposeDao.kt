package com.adsamcik.tracker.shared.base.database.dao

import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionJoinStateEntity
import androidx.room.Dao
import androidx.room.Embedded
import com.adsamcik.tracker.shared.base.database.data.LocationSample
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationAcceptedReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LocationSamplePurposeAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.LocationRegistrationPlanEntity

/** Read-only continuity metadata; it never conveys permission to acquire or restore native facts. */
data class LocationPathSampleRow(
	@Embedded val sample: LocationSample,
	@androidx.room.ColumnInfo(name = "continuity_id") val continuityId: String?,
	@androidx.room.ColumnInfo(name = "continuity_sequence") val continuitySequence: Long?,
	@androidx.room.ColumnInfo(name = "ambient_only") val ambientOnly: Boolean,
	@androidx.room.ColumnInfo(name = "has_session_purpose") val hasSessionPurpose: Boolean = false,
	@androidx.room.ColumnInfo(name = "has_visible_background_purpose")
	val hasVisibleBackgroundPurpose: Boolean = false,
)

/** Location authority only; this DAO never creates a canonical sample. */
@Dao
interface LocationPurposeDao {
	/** Exact saved capture membership. Coordinates are current user data, not reauthenticated provider payload. */
	@Query("""SELECT DISTINCT location_sample.*, lr.source_instance_id || ':' || lr.registration_generation AS continuity_id,
		lr.source_sequence AS continuity_sequence, 0 AS ambient_only,
		1 AS has_session_purpose,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
		AND lp.purpose = 'AMBIENT_PRODUCT') AS has_visible_background_purpose
		FROM location_sample_purpose_authority p JOIN location_sample ON location_sample.id = p.sample_id
		JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
		JOIN source_evidence_state e ON e.id = 1 AND e.collected_data_epoch = lr.collected_data_epoch
		JOIN session_segment segment ON segment.id = p.session_segment_id
		WHERE ${LocationReadScope.HISTORY_RETAINED}
		AND lr.source_admission_ordinal > e.deleted_source_event_high_water_ordinal
		AND p.purpose = 'SESSION_CAPTURE' AND p.authority_id IN (:serviceRunIds)
		AND p.logical_tracking_id = :logicalId AND p.service_run_id = p.authority_id
		AND segment.logical_tracking_id = p.logical_tracking_id AND segment.service_run_id = p.service_run_id
		AND (:afterTimeMs IS NULL OR time_ms > :afterTimeMs OR
		(time_ms = :afterTimeMs AND location_sample.id > COALESCE(:afterId, 0)))
		ORDER BY time_ms, location_sample.id LIMIT :limit""")
	suspend fun savedLogicalTripPointPage(logicalId: String, serviceRunIds: List<String>,
		afterTimeMs: Long?, afterId: Long?, limit: Int): List<LocationPathSampleRow>

	/** Legacy/imported interval compatibility; no ambient or another native session is borrowed. */
	@Query("""SELECT location_sample.*, NULL AS continuity_id, NULL AS continuity_sequence,
		0 AS ambient_only, 0 AS has_session_purpose, 0 AS has_visible_background_purpose
		FROM location_sample WHERE time_ms BETWEEN :fromMs AND :toMs
		AND NOT EXISTS (SELECT 1 FROM location_accepted_receipt r WHERE r.sample_id = location_sample.id)
		AND ${LocationReadScope.HISTORY_RETAINED}
		AND (NOT EXISTS (SELECT 1 FROM location_file_archive_point m WHERE m.sample_id = location_sample.id)
		OR ${LocationReadScope.HISTORY_CURRENT_IMPORT_COPY})
		AND (:afterTimeMs IS NULL OR time_ms > :afterTimeMs OR
		(time_ms = :afterTimeMs AND location_sample.id > COALESCE(:afterId, 0)))
		ORDER BY time_ms, id LIMIT :limit""")
	suspend fun savedLegacyTripPointPage(fromMs: Long, toMs: Long,
		afterTimeMs: Long?, afterId: Long?, limit: Int): List<LocationPathSampleRow>

	/** Exact file membership with the same descriptive origin and retention as savedFileChunk. */
	@Query("""SELECT location_sample.*, lr.source_instance_id || ':' || lr.registration_generation AS continuity_id,
		lr.source_sequence AS continuity_sequence, 0 AS ambient_only,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
		AND lp.purpose = 'SESSION_CAPTURE') AS has_session_purpose,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
		AND lp.purpose = 'AMBIENT_PRODUCT') AS has_visible_background_purpose
		FROM location_sample LEFT JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
		WHERE location_sample.id IN (:sampleIds) AND ${LocationReadScope.HISTORY_RETAINED}
		ORDER BY location_sample.time_ms, location_sample.id""")
	suspend fun savedFileSamples(sampleIds: List<Long>): List<LocationPathSampleRow>
	/** Discovery only; source-owned reader qualifies the receipt and each purpose in the same snapshot. */
	@Query("SELECT * FROM location_accepted_receipt WHERE observed_wall_time_ms >= :fromMs " +
		"AND observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, sample_id LIMIT :limit")
	suspend fun contextReceiptsOverWindow(fromMs: Long, toMs: Long, limit: Int): List<LocationAcceptedReceiptEntity>
	/** Bounded elapsed-clock candidates for optional radio context; wall clock jumps do not join. */
	@Query("SELECT * FROM location_accepted_receipt WHERE boot_id = :bootId AND clock_domain_id = :clockDomainId " +
		"AND observed_elapsed_realtime_nanos >= :fromNanos AND observed_elapsed_realtime_nanos <= :toNanos " +
		"ORDER BY observed_elapsed_realtime_nanos, source_event_id LIMIT :limit")
	suspend fun contextReceipts(bootId: String, clockDomainId: String, fromNanos: Long,
		toNanos: Long, limit: Int): List<LocationAcceptedReceiptEntity>

	@Query("SELECT * FROM location_sample_purpose_authority WHERE sample_id IN (:sampleIds) " +
		"ORDER BY sample_id, purpose, authority_id LIMIT :limit")
	suspend fun contextPurposes(sampleIds: List<Long>, limit: Int): List<LocationSamplePurposeAuthorityEntity>

	@Query("SELECT * FROM ambient_location_retention_authority WHERE scope = 'LIVE_AMBIENT' " +
		"AND approval_revision IN (:revisions) ORDER BY approval_revision LIMIT :limit")
	suspend fun contextRetentionApprovals(revisions: List<Long>, limit: Int): List<AmbientLocationRetentionAuthorityEntity>

	@Query("""SELECT event_id, admission_ordinal, source_kind, source_instance_id,
		registration_generation, source_sequence, logical_tracking_id, service_run_id
		FROM source_event_wal WHERE source_kind = 1 AND source_instance_id = :sourceInstanceId
		AND admission_ordinal <= :throughOrdinal ORDER BY admission_ordinal DESC LIMIT 1""")
	suspend fun latestInstanceContinuityEvent(sourceInstanceId: String, throughOrdinal: Long): SourceEventContinuityRow?

	/** Bounded compatibility lookup for pre-instance-key compact anchors after WAL pruning. */
	@Query("""SELECT j.* FROM source_projection_join_state j
		JOIN provider_registration_generation r ON r.source_kind = 1 AND r.source_instance_id = :sourceInstanceId
		WHERE j.projection_id = '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}'
		AND j.projection_version = ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION}
		AND (j.state_key = 'physical-continuity:' || length(:sourceInstanceId) || ':' || :sourceInstanceId || ':' || r.registration_generation
		OR EXISTS (SELECT 1 FROM source_service_run run WHERE run.logical_tracking_id = j.logical_tracking_id AND
		j.state_key = 'continuity:' || length(run.logical_tracking_id) || ':' || run.logical_tracking_id || ':' ||
		length(run.service_run_id) || ':' || run.service_run_id || ':' || length(:sourceInstanceId) || ':' || :sourceInstanceId || ':' || r.registration_generation))
		ORDER BY j.minimum_required_ordinal DESC LIMIT 1""")
	suspend fun latestLegacyInstanceContinuityAnchor(sourceInstanceId: String): SourceProjectionJoinStateEntity?

	/** Export cancellation stamp over existing authority, excluding unrelated quality edits. */
	@Query("""SELECT
		COALESCE((SELECT collected_data_epoch || ':' || COALESCE(retained_from_ms, -1) || ':' || deleted_source_event_high_water_ordinal FROM source_evidence_state WHERE id = 1), 'missing') || '|' ||
		COALESCE((SELECT epoch || ':' || eligible || ':' || persistence_eligible FROM source_consent_epoch WHERE source_kind = 1 AND purpose = 'AMBIENT_PRODUCT' ORDER BY epoch DESC LIMIT 1), 'missing') || '|' ||
		COALESCE((SELECT p.ambient_persistence_eligible || ':' || COALESCE(p.ambient_consent_epoch, -1) FROM source_policy p JOIN source_policy_authority a ON a.current_policy_revision = p.policy_revision WHERE a.id = 1 AND a.bootstrap_state = 'ACTIVE' AND p.source_kind = 1), 'missing') || '|' ||
		COALESCE((SELECT effect_checksum FROM ambient_location_retention_authority WHERE scope = 'LIVE_AMBIENT' ORDER BY approval_revision DESC LIMIT 1), 'missing') || '|' ||
		(EXISTS (SELECT 1 FROM collected_data_deletion_operation WHERE phase IN ('RETENTION_PREPARED', 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED', 'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED', 'RETENTION_SOURCE_MAINTENANCE_COMPLETED')))
	""")
	suspend fun ambientExportAuthorityStamp(): String

	@Query("""
		SELECT location_sample.*, lr.source_instance_id || ':' || lr.registration_generation AS continuity_id,
		lr.source_sequence AS continuity_sequence,
		NOT (${LocationReadScope.CAPTURE_OR_LEGACY}) AS ambient_only,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp
			WHERE lp.sample_id = location_sample.id AND lp.purpose = 'SESSION_CAPTURE'
			AND lr.sample_id IS NOT NULL) AS has_session_purpose,
		(${LocationReadScope.AMBIENT}) AS has_visible_background_purpose
		FROM location_sample LEFT JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
		WHERE ${LocationReadScope.ALL_VISIBLE} AND time_ms >= :fromMs AND time_ms <= :toMs
		AND (:ambientOnly = 0 OR NOT (${LocationReadScope.CAPTURE_OR_LEGACY}))
		AND (:northE7 IS NULL OR (lat_e7 <= :northE7 AND lat_e7 >= :southE7 AND
		((:eastE7 >= :westE7 AND lon_e7 BETWEEN :westE7 AND :eastE7) OR (:eastE7 < :westE7 AND (lon_e7 >= :westE7 OR lon_e7 <= :eastE7)))))
		AND (:afterTimeMs IS NULL OR time_ms > :afterTimeMs OR (time_ms = :afterTimeMs AND location_sample.id > COALESCE(:afterId, 0)))
		ORDER BY time_ms, location_sample.id LIMIT :limit
	""")
	suspend fun visiblePathChunk(fromMs: Long, toMs: Long, afterTimeMs: Long?, afterId: Long?, limit: Int,
		ambientOnly: Boolean = false, northE7: Int? = null, southE7: Int? = null, eastE7: Int? = null, westE7: Int? = null): List<LocationPathSampleRow>

	/** User data backup; missing historical receipts or grants do not hide saved points. */
	@Query("""SELECT location_sample.*, lr.source_instance_id || ':' || lr.registration_generation AS continuity_id,
		lr.source_sequence AS continuity_sequence,
		0 AS ambient_only,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
			AND lp.purpose = 'SESSION_CAPTURE') AS has_session_purpose,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
			AND lp.purpose = 'AMBIENT_PRODUCT') AS has_visible_background_purpose
		FROM location_sample LEFT JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
		WHERE location_sample.time_ms >= :fromMs AND location_sample.time_ms <= :toMs
		AND (NOT EXISTS (SELECT 1 FROM source_evidence_state e WHERE e.id = 1 AND e.retained_from_ms IS NOT NULL
			AND location_sample.time_ms < e.retained_from_ms))
		AND NOT EXISTS (SELECT 1 FROM collected_data_deletion_operation WHERE phase IN
			('RETENTION_PREPARED', 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED',
			'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED',
			'RETENTION_SOURCE_MAINTENANCE_COMPLETED'))
		AND (:afterTimeMs IS NULL OR location_sample.time_ms > :afterTimeMs OR
			(location_sample.time_ms = :afterTimeMs AND location_sample.id > COALESCE(:afterId, 0)))
		ORDER BY location_sample.time_ms, location_sample.id LIMIT :limit""")
	suspend fun savedFileChunk(fromMs: Long, toMs: Long, afterTimeMs: Long?, afterId: Long?,
		limit: Int): List<LocationPathSampleRow>

	/** Historical file reads are fenced by erase/retention, not current recording settings. */
	@Query("""SELECT COALESCE((SELECT collected_data_epoch || ':' || COALESCE(retained_from_ms, -1) || ':' ||
		deleted_source_event_high_water_ordinal FROM source_evidence_state WHERE id = 1), 'missing') || '|' ||
		EXISTS (SELECT 1 FROM collected_data_deletion_operation WHERE phase IN
			('RETENTION_PREPARED', 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED',
			'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED',
			'RETENTION_SOURCE_MAINTENANCE_COMPLETED'))""")
	suspend fun savedFileExportAuthorityStamp(): String

	@Query("""DELETE FROM location_sample_purpose_authority WHERE purpose = 'AMBIENT_PRODUCT' AND sample_id IN
		(SELECT sample_id FROM location_accepted_receipt WHERE observed_wall_time_ms - wall_time_uncertainty_ms < :retainedFromMs)""")
	suspend fun removeAmbientAssociationsBefore(retainedFromMs: Long): Int

	@Query("DELETE FROM source_projection_join_state WHERE projection_id = '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}' AND projection_version = ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION} AND state_key LIKE 'ambient-curation:%'")
	suspend fun clearAmbientCurationCheckpoints(): Int

	@Query("DELETE FROM location_sample_purpose_authority WHERE purpose = 'AMBIENT_PRODUCT'")
	suspend fun removeAmbientAssociations(): Int

	@Query("""DELETE FROM location_observation WHERE EXISTS (
		SELECT 1 FROM source_projection_join_state j WHERE j.projection_id = '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}' AND j.projection_version = ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION} AND j.state_key = 'ambient-receipt:' || location_observation.source_event_id)
		AND (fix_time_ms < :beforeMs OR EXISTS (SELECT 1 FROM location_accepted_receipt r WHERE r.source_event_id = location_observation.source_event_id))
		AND NOT EXISTS (SELECT 1 FROM location_sample s JOIN location_sample_purpose_authority p ON p.sample_id = s.id
		WHERE s.source_event_id = location_observation.source_event_id)""")
	suspend fun deleteUnassociatedAmbientObservations(beforeMs: Long = Long.MAX_VALUE): Int

	@Query("""DELETE FROM location_observation_decision WHERE EXISTS (
		SELECT 1 FROM source_projection_join_state j WHERE j.projection_id = '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}' AND j.projection_version = ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION} AND j.state_key = 'ambient-receipt:' || location_observation_decision.observation_source_event_id)
		AND (decided_at_ms < :beforeMs OR EXISTS (SELECT 1 FROM location_accepted_receipt r WHERE r.source_event_id = location_observation_decision.observation_source_event_id))
		AND NOT EXISTS (SELECT 1 FROM location_sample s JOIN location_sample_purpose_authority p ON p.sample_id = s.id
		WHERE s.source_event_id = location_observation_decision.observation_source_event_id)""")
	suspend fun deleteUnassociatedAmbientDecisions(beforeMs: Long = Long.MAX_VALUE): Int

	@Query("""DELETE FROM location_sample WHERE EXISTS (SELECT 1 FROM location_accepted_receipt r WHERE r.sample_id = location_sample.id)
		AND NOT EXISTS (SELECT 1 FROM location_sample_purpose_authority p WHERE p.sample_id = location_sample.id)""")
	suspend fun deleteUnassociatedSamples(): Int

	@Query("DELETE FROM source_projection_join_state WHERE projection_id = '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}' AND projection_version = ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION} AND (state_key LIKE 'ambient-curation:%' OR state_key LIKE 'ambient-receipt:%')")
	suspend fun deleteAmbientCurationState(): Int

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRegistrationPlanIfAbsent(value: LocationRegistrationPlanEntity): Long

	@Query("SELECT * FROM location_registration_plan WHERE registration_generation = :generation AND configuration_revision = :configurationRevision AND authorization_revision = :authorizationRevision")
	suspend fun registrationPlan(generation: Long, configurationRevision: Long, authorizationRevision: Long): LocationRegistrationPlanEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertReceiptIfAbsent(value: LocationAcceptedReceiptEntity): Long

	@Query("SELECT * FROM location_accepted_receipt WHERE sample_id = :sampleId")
	suspend fun receipt(sampleId: Long): LocationAcceptedReceiptEntity?

	@Query("SELECT * FROM location_accepted_receipt WHERE source_event_id = :eventId")
	suspend fun receiptByEvent(eventId: String): LocationAcceptedReceiptEntity?

	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertPurposeIfAbsent(value: LocationSamplePurposeAuthorityEntity): Long

	@Query("SELECT * FROM location_sample_purpose_authority WHERE sample_id = :sampleId AND purpose = :purpose AND authority_id = :authorityId")
	suspend fun purpose(sampleId: Long, purpose: String, authorityId: String): LocationSamplePurposeAuthorityEntity?

	@Query("SELECT * FROM location_sample_purpose_authority WHERE sample_id = :sampleId ORDER BY purpose, authority_id LIMIT :limit")
	suspend fun purposes(sampleId: Long, limit: Int): List<LocationSamplePurposeAuthorityEntity>


	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRetentionAuthority(value: AmbientLocationRetentionAuthorityEntity)

	@Query(
		"SELECT * FROM ambient_location_retention_authority WHERE scope = :scope " +
			"ORDER BY approval_revision DESC LIMIT 1",
	)
	suspend fun latestRetentionAuthority(scope: String): AmbientLocationRetentionAuthorityEntity?

	@Query(
		"SELECT * FROM ambient_location_retention_authority WHERE scope = :scope " +
			"ORDER BY approval_revision ASC LIMIT :limit",
	)
	suspend fun retentionAuthorities(
		scope: String,
		limit: Int,
	): List<AmbientLocationRetentionAuthorityEntity>

	@Query(
		"SELECT * FROM ambient_location_retention_authority WHERE scope = :scope " +
			"AND effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, approval_revision DESC LIMIT 1",
	)
	suspend fun retentionAuthorityAt(
		scope: String,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
	): AmbientLocationRetentionAuthorityEntity?

	@Query(
		"SELECT COALESCE(MAX(approval_revision), 0) FROM ambient_location_retention_authority " +
			"WHERE scope = :scope",
	)
	suspend fun maximumRetentionApprovalRevision(scope: String): Long

}
