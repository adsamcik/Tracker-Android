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

/** Descriptive saved origin; it does not authorize collection or map enrichment. */
data class LocationHistoryDayRow(
	@Embedded val point: LocationPathSampleRow,
	@androidx.room.ColumnInfo(name = "history_origin") val historyOrigin: String,
	@androidx.room.ColumnInfo(name = "has_current_import_copy") val hasCurrentImportCopy: Boolean,
)

/** Distinct immutable dependencies of a saved prefix, rather than one copy per coordinate. */
data class LocationSessionAuthorityKey(
    @androidx.room.ColumnInfo(name = "source_policy_revision") val policyRevision: Long,
    @androidx.room.ColumnInfo(name = "consent_epoch") val consentEpoch: Long,
    @androidx.room.ColumnInfo(name = "session_manifest_revision") val manifestRevision: Long,
    @androidx.room.ColumnInfo(name = "lifecycle_lease_generation") val leaseGeneration: Long,
)

/** Detects insertion-only changes without reading old coordinate payloads into memory. */
data class LocationSavedAppendState(
    @androidx.room.ColumnInfo(name = "through_id") val throughId: Long,
    @androidx.room.ColumnInfo(name = "sample_prefix_count") val samplePrefixCount: Long,
    @androidx.room.ColumnInfo(name = "purpose_prefix_count") val purposePrefixCount: Long,
)

/** Location authority only; this DAO never creates a canonical sample. */
@Dao
interface LocationPurposeDao {
    /** Counts an existing prefix and reads the newest PK to distinguish append-only updates. */
    @Query("""SELECT COALESCE((SELECT MAX(id) FROM location_sample), 0) AS through_id,
        (SELECT COUNT(*) FROM location_sample WHERE id <= :throughId) AS sample_prefix_count,
        (SELECT COUNT(*) FROM location_sample_purpose_authority
            WHERE sample_id <= :throughId) AS purpose_prefix_count""")
    suspend fun savedAppendState(throughId: Long): LocationSavedAppendState

    /** Index-only native membership probe. A late point still has a new saved PK. */
    @Query("""SELECT EXISTS(SELECT 1 FROM location_sample_purpose_authority
        WHERE purpose = 'SESSION_CAPTURE' AND authority_id = :runId AND sample_id > :afterId
        AND logical_tracking_id = :logicalId AND service_run_id = :runId AND session_segment_id = :segmentId)""")
    suspend fun hasSessionPointsAfter(logicalId: String, runId: String, segmentId: Long, afterId: Long): Boolean

    /** Probes a legacy trip's measured window without loading any coordinates. */
    @Query("""SELECT EXISTS(SELECT 1 FROM location_sample
        WHERE time_ms BETWEEN :fromMs AND :toMs AND id > :afterId)""")
    suspend fun hasWindowPointsAfter(fromMs: Long, toMs: Long, afterId: Long): Boolean

    /** Reads only the distinct historical authority keys referenced by this saved prefix. */
    @Query("""SELECT DISTINCT source_policy_revision, consent_epoch, session_manifest_revision,
        lifecycle_lease_generation FROM location_sample_purpose_authority
        WHERE purpose = 'SESSION_CAPTURE' AND authority_id = :runId
        AND logical_tracking_id = :logicalId AND service_run_id = :runId
        AND session_segment_id = :segmentId AND sample_id > :afterId AND sample_id <= :throughId
        ORDER BY source_policy_revision, consent_epoch, session_manifest_revision, lifecycle_lease_generation""")
    suspend fun savedSessionAuthorityKeys(logicalId: String, runId: String, segmentId: Long,
        afterId: Long, throughId: Long): List<LocationSessionAuthorityKey>

    /** Native receipt PK cursor: a wakeup is a hint, not a list of everything committed. */
    @Query("SELECT COALESCE(MAX(sample_id), 0) FROM location_accepted_receipt")
    suspend fun latestSavedReceiptSampleId(): Long

    /** Discovery only. The source-owned reader requalifies every returned receipt and purpose. */
    @Query("""SELECT r.* FROM location_accepted_receipt r
        JOIN location_sample_purpose_authority p ON p.sample_id = r.sample_id
        WHERE p.purpose = 'SESSION_CAPTURE' AND p.authority_id = :runId
        AND p.logical_tracking_id = :logicalId AND p.service_run_id = :runId
        AND p.session_segment_id = :segmentId
        AND r.sample_id > :afterId AND r.sample_id <= :throughId
        ORDER BY r.sample_id LIMIT :limit""")
    suspend fun savedSessionReceiptPageAfterId(logicalId: String, runId: String, segmentId: Long,
        afterId: Long, throughId: Long, limit: Int): List<LocationAcceptedReceiptEntity>

    /** Membership sentinel in the optional current measured window; never a qualified user total. */
    @Query("""SELECT COUNT(*) FROM location_sample_purpose_authority p
        WHERE p.purpose = 'SESSION_CAPTURE' AND p.authority_id = :runId
        AND p.logical_tracking_id = :logicalId AND p.service_run_id = :runId
        AND p.session_segment_id = :segmentId
        AND ((:fromMs IS NULL AND :toMs IS NULL) OR EXISTS (
            SELECT 1 FROM effective_location_sample location_sample WHERE location_sample.id = p.sample_id
            AND (:fromMs IS NULL OR location_sample.time_ms >= :fromMs)
            AND (:toMs IS NULL OR location_sample.time_ms <= :toMs)))""")
    suspend fun savedSessionCandidateCount(logicalId: String, runId: String, segmentId: Long,
        fromMs: Long? = null, toMs: Long? = null): Long

	/** Exact saved capture membership. Coordinates are current user data, not reauthenticated provider payload. */
	@Query("""SELECT DISTINCT location_sample.*, CASE WHEN location_sample.clock_domain_id IS NULL THEN NULL ELSE lr.source_instance_id || ':' || lr.registration_generation END AS continuity_id,
		lr.source_sequence AS continuity_sequence, 0 AS ambient_only,
		1 AS has_session_purpose,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
		AND lp.purpose = 'AMBIENT_PRODUCT') AS has_visible_background_purpose
		FROM location_sample_purpose_authority p JOIN effective_location_sample AS location_sample ON location_sample.id = p.sample_id
		JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
		JOIN source_evidence_state e ON e.id = 1 AND (e.collected_data_epoch = lr.collected_data_epoch OR instr(',' || e.restored_data_epochs || ',', ',' || lr.collected_data_epoch || ',') > 0)
		JOIN session_segment segment ON segment.id = p.session_segment_id
		WHERE ${LocationReadScope.HISTORY_RETAINED}
		AND lr.source_admission_ordinal > e.deleted_source_event_high_water_ordinal
		AND p.purpose = 'SESSION_CAPTURE' AND p.authority_id IN (:serviceRunIds)
		AND p.logical_tracking_id = :logicalId AND p.service_run_id = p.authority_id
		AND segment.logical_tracking_id = p.logical_tracking_id AND segment.service_run_id = p.service_run_id
		AND time_ms BETWEEN :fromMs AND :toMs
		AND (:afterTimeMs IS NULL OR time_ms > :afterTimeMs OR
		(time_ms = :afterTimeMs AND location_sample.id > COALESCE(:afterId, 0)))
		ORDER BY time_ms, location_sample.id LIMIT :limit""")
	suspend fun savedLogicalTripPointPage(logicalId: String, serviceRunIds: List<String>,
		afterTimeMs: Long?, afterId: Long?, limit: Int,
		fromMs: Long = 0L, toMs: Long = Long.MAX_VALUE): List<LocationPathSampleRow>

	/** Legacy/imported interval compatibility; no ambient or another native session is borrowed. */
	@Query("""SELECT location_sample.*, NULL AS continuity_id, NULL AS continuity_sequence,
		0 AS ambient_only, 0 AS has_session_purpose, 0 AS has_visible_background_purpose
		FROM effective_location_sample AS location_sample WHERE time_ms BETWEEN :fromMs AND :toMs
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
	@Query("""SELECT location_sample.*, CASE WHEN location_sample.clock_domain_id IS NULL THEN NULL ELSE lr.source_instance_id || ':' || lr.registration_generation END AS continuity_id,
		lr.source_sequence AS continuity_sequence, 0 AS ambient_only,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
		AND lp.purpose = 'SESSION_CAPTURE') AS has_session_purpose,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
		AND lp.purpose = 'AMBIENT_PRODUCT') AS has_visible_background_purpose
		FROM effective_location_sample AS location_sample LEFT JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
		WHERE location_sample.id IN (:sampleIds) AND ${LocationReadScope.HISTORY_RETAINED}
		ORDER BY location_sample.time_ms, location_sample.id""")
	suspend fun savedFileSamples(sampleIds: List<Long>): List<LocationPathSampleRow>
	/** File provenance is descriptive; a coincident old session interval is not file membership. */
	@Query("""SELECT location_sample.id FROM effective_location_sample AS location_sample WHERE location_sample.id IN (:sampleIds)
		AND (EXISTS (SELECT 1 FROM location_file_identity i WHERE i.sample_id = location_sample.id AND i.imported = 1)
		OR ${LocationReadScope.HISTORY_CURRENT_IMPORT_COPY})""")
	suspend fun importedHistorySampleIds(sampleIds: List<Long>): List<Long>
	/**
	 * Reads `location_accepted_receipt` rows. Query conditions include `sample_id IN (:sampleIds)`.
	 * Orders by `observed_wall_time_ms, sample_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM location_accepted_receipt WHERE sample_id IN (:sampleIds) " +
		"ORDER BY observed_wall_time_ms, sample_id LIMIT :limit")
	suspend fun contextReceiptsForSamples(sampleIds: List<Long>, limit: Int): List<LocationAcceptedReceiptEntity>

	/** Bounded ordinary sessionless history. Native qualification labels points but never hides legacy data. */
	@Query("""SELECT location_sample.*, CASE WHEN location_sample.clock_domain_id IS NULL THEN NULL ELSE lr.source_instance_id || ':' || lr.registration_generation END AS continuity_id,
		lr.source_sequence AS continuity_sequence, 0 AS ambient_only,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
		AND lp.purpose = 'SESSION_CAPTURE') AS has_session_purpose,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
		AND lp.purpose = 'AMBIENT_PRODUCT') AS has_visible_background_purpose,
		${LocationReadScope.HISTORY_ORIGIN} AS history_origin,
		${LocationReadScope.HISTORY_CURRENT_IMPORT_COPY} AS has_current_import_copy
		FROM effective_location_sample AS location_sample LEFT JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
		WHERE location_sample.time_ms >= :fromMs AND location_sample.time_ms < :toMs
		AND location_sample.lat_e7 BETWEEN -900000000 AND 900000000
		AND location_sample.lon_e7 BETWEEN -1800000000 AND 1800000000
		AND ${LocationReadScope.HISTORY_RETAINED} AND ${LocationReadScope.SESSIONLESS_HISTORY}
		AND ((${LocationReadScope.HISTORY_ORIGIN} = 'LOCAL' AND :includeLocal)
		OR (${LocationReadScope.HISTORY_ORIGIN} = 'IMPORTED' AND :includeImported)
		OR (${LocationReadScope.HISTORY_ORIGIN} = 'LEGACY' AND :includeLegacy))
		ORDER BY location_sample.time_ms, location_sample.id LIMIT :limit""")
	suspend fun sessionlessHistoryOverWindow(fromMs: Long, toMs: Long, limit: Int,
		includeLocal: Boolean, includeImported: Boolean, includeLegacy: Boolean): List<LocationHistoryDayRow>
	/** Discovery only; source-owned reader qualifies the receipt and each purpose in the same snapshot. */
	@Query("SELECT * FROM location_accepted_receipt WHERE observed_wall_time_ms >= :fromMs " +
		"AND observed_wall_time_ms < :toMs ORDER BY observed_wall_time_ms, sample_id LIMIT :limit")
	suspend fun contextReceiptsOverWindow(fromMs: Long, toMs: Long, limit: Int): List<LocationAcceptedReceiptEntity>
	/** Keyset paging for a saved measured-time summary; no total point cap or acquisition effect. */
	@Query("""SELECT * FROM location_accepted_receipt
		WHERE observed_wall_time_ms >= :fromMs AND observed_wall_time_ms < :toMs
		AND (:afterTimeMs IS NULL OR observed_wall_time_ms > :afterTimeMs OR
		(observed_wall_time_ms = :afterTimeMs AND sample_id > COALESCE(:afterSampleId, 0)))
		ORDER BY observed_wall_time_ms, sample_id LIMIT :limit""")
	suspend fun savedReceiptPageOverWindow(fromMs: Long, toMs: Long, afterTimeMs: Long?,
		afterSampleId: Long?, limit: Int): List<LocationAcceptedReceiptEntity>

	/** Bounded elapsed-clock candidates for optional radio context; wall clock jumps do not join. */
	@Query("SELECT * FROM location_accepted_receipt WHERE boot_id = :bootId AND clock_domain_id = :clockDomainId " +
		"AND observed_elapsed_realtime_nanos >= :fromNanos AND observed_elapsed_realtime_nanos <= :toNanos " +
		"ORDER BY observed_elapsed_realtime_nanos, source_event_id LIMIT :limit")
	suspend fun contextReceipts(bootId: String, clockDomainId: String, fromNanos: Long,
		toNanos: Long, limit: Int): List<LocationAcceptedReceiptEntity>

	/**
	 * Reads `location_sample_purpose_authority` rows. Query conditions include `sample_id IN
	 * (:sampleIds)`. Orders by `sample_id, purpose, authority_id`. Returns at most `limit` rows.
	 */
	@Query("SELECT * FROM location_sample_purpose_authority WHERE sample_id IN (:sampleIds) " +
		"ORDER BY sample_id, purpose, authority_id LIMIT :limit")
	suspend fun contextPurposes(sampleIds: List<Long>, limit: Int): List<LocationSamplePurposeAuthorityEntity>

	/**
	 * Reads `ambient_location_retention_authority` rows. Query conditions include `scope =
	 * 'LIVE_AMBIENT'`, `approval_revision IN (:revisions)`. Orders by `approval_revision`. Returns at
	 * most `limit` rows.
	 */
	@Query("SELECT * FROM ambient_location_retention_authority WHERE scope = 'LIVE_AMBIENT' " +
		"AND approval_revision IN (:revisions) ORDER BY approval_revision LIMIT :limit")
	suspend fun contextRetentionApprovals(revisions: List<Long>,
        limit: Int): List<AmbientLocationRetentionAuthorityEntity>

	/**
	 * Reads `source_event_wal` rows. Query conditions include `source_kind = 1`, `source_instance_id
	 * = :sourceInstanceId`, `admission_ordinal <= :throughOrdinal`. Orders by `admission_ordinal
	 * DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
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

	/**
	 * Reads `location_sample_purpose_authority` rows. Query conditions include `lp.purpose =
	 * 'SESSION_CAPTURE'`, `time_ms >= :fromMs`, `time_ms <= :toMs`, `ambientOnly = 0`. Orders by
	 * `time_ms, location_sample.id`. Returns at most `limit` rows. Timestamp bounds use UTC
	 * milliseconds.
	 */
	@Query("""
		SELECT location_sample.*, CASE WHEN location_sample.clock_domain_id IS NULL THEN NULL ELSE lr.source_instance_id || ':' || lr.registration_generation END AS continuity_id,
		lr.source_sequence AS continuity_sequence,
		NOT (${LocationReadScope.CAPTURE_OR_LEGACY}) AS ambient_only,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp
			WHERE lp.sample_id = location_sample.id AND lp.purpose = 'SESSION_CAPTURE'
			AND lr.sample_id IS NOT NULL) AS has_session_purpose,
		(${LocationReadScope.AMBIENT}) AS has_visible_background_purpose
		FROM effective_location_sample AS location_sample LEFT JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
		WHERE ${LocationReadScope.ALL_VISIBLE} AND time_ms >= :fromMs AND time_ms <= :toMs
		AND (:ambientOnly = 0 OR NOT (${LocationReadScope.CAPTURE_OR_LEGACY}))
		AND (:northE7 IS NULL OR (lat_e7 <= :northE7 AND lat_e7 >= :southE7 AND
		((:eastE7 >= :westE7 AND lon_e7 BETWEEN :westE7 AND :eastE7) OR (:eastE7 < :westE7 AND (lon_e7 >= :westE7 OR lon_e7 <= :eastE7)))))
		AND (:afterTimeMs IS NULL OR time_ms > :afterTimeMs OR (time_ms = :afterTimeMs AND location_sample.id > COALESCE(:afterId, 0)))
		ORDER BY time_ms, location_sample.id LIMIT :limit
	""")
	suspend fun visiblePathChunk(fromMs: Long, toMs: Long, afterTimeMs: Long?, afterId: Long?, limit: Int,
		ambientOnly: Boolean = false, northE7: Int? = null, southE7: Int? = null, eastE7: Int? = null, westE7: Int? =
            null): List<LocationPathSampleRow>

	/** User data backup; missing historical receipts or grants do not hide saved points. */
	@Query("""SELECT location_sample.*, CASE WHEN location_sample.clock_domain_id IS NULL THEN NULL ELSE lr.source_instance_id || ':' || lr.registration_generation END AS continuity_id,
		lr.source_sequence AS continuity_sequence,
		0 AS ambient_only,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
			AND lp.purpose = 'SESSION_CAPTURE') AS has_session_purpose,
		EXISTS (SELECT 1 FROM location_sample_purpose_authority lp WHERE lp.sample_id = location_sample.id
			AND lp.purpose = 'AMBIENT_PRODUCT') AS has_visible_background_purpose
		FROM effective_location_sample AS location_sample LEFT JOIN location_accepted_receipt lr ON lr.sample_id = location_sample.id
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
		deleted_source_event_high_water_ordinal FROM source_evidence_state WHERE id = 1), 'missing') || ':' ||
		COALESCE((SELECT MAX(revision) FROM location_point_correction), 0) || '|' ||
		EXISTS (SELECT 1 FROM collected_data_deletion_operation WHERE phase IN
			('RETENTION_PREPARED', 'RETENTION_DATASTORE_ACKNOWLEDGED', 'RETENTION_ROOM_GUARD_COMMITTED',
			'RETENTION_AUTHORITY_REISSUED', 'RETENTION_PROVIDER_RECONCILED',
			'RETENTION_SOURCE_MAINTENANCE_COMPLETED'))""")
	suspend fun savedFileExportAuthorityStamp(): String

	/**
	 * Deletes `location_sample_purpose_authority` rows matching the supplied scope. Query conditions
	 * include `purpose = 'AMBIENT_PRODUCT'`. Returns the affected row count. Timestamp bounds use UTC
	 * milliseconds.
	 */
	@Query(
		"""DELETE FROM location_sample_purpose_authority WHERE purpose = 'AMBIENT_PRODUCT' AND sample_id IN
		(SELECT sample_id FROM location_accepted_receipt WHERE """ +
			"""observed_wall_time_ms - wall_time_uncertainty_ms < :retainedFromMs)""",
	)
	suspend fun removeAmbientAssociationsBefore(retainedFromMs: Long): Int

	/**
	 * Deletes `source_projection_join_state` rows matching the supplied scope. Query conditions
	 * include `projection_id =
	 * '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}'`, `state_key LIKE
	 * 'ambient-curation:%'`. Returns the affected row count.
	 */
	@Query(("DELETE FROM source_projection_join_state WHERE projection_id = " +
        "'${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}' AND projection_version " +
        "= ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION} AND state_key " +
        "LIKE 'ambient-curation:%'"))
	suspend fun clearAmbientCurationCheckpoints(): Int

	/**
	 * Deletes `location_sample_purpose_authority` rows matching the supplied scope. Query conditions
	 * include `purpose = 'AMBIENT_PRODUCT'`. Returns the affected row count.
	 */
	@Query("DELETE FROM location_sample_purpose_authority WHERE purpose = 'AMBIENT_PRODUCT'")
	suspend fun removeAmbientAssociations(): Int

	/**
	 * Deletes `location_observation` rows matching the supplied scope. Query conditions include
	 * `j.projection_id = '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}'`,
	 * `j.state_key = 'ambient-receipt:'`, `fix_time_ms < :beforeMs`. Returns the affected row count.
	 * Timestamp bounds use UTC milliseconds.
	 */
	@Query("""DELETE FROM location_observation WHERE EXISTS (
		SELECT 1 FROM source_projection_join_state j WHERE j.projection_id = '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}' AND j.projection_version = ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION} AND j.state_key = 'ambient-receipt:' || location_observation.source_event_id)
		AND (fix_time_ms < :beforeMs OR EXISTS (SELECT 1 FROM location_accepted_receipt r WHERE r.source_event_id = location_observation.source_event_id))
		AND NOT EXISTS (SELECT 1 FROM location_sample s JOIN location_sample_purpose_authority p ON p.sample_id = s.id
		WHERE s.source_event_id = location_observation.source_event_id)""")
	suspend fun deleteUnassociatedAmbientObservations(beforeMs: Long = Long.MAX_VALUE): Int

	/**
	 * Deletes `location_observation_decision` rows matching the supplied scope. Query conditions
	 * include `j.projection_id =
	 * '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}'`, `j.state_key =
	 * 'ambient-receipt:'`, `decided_at_ms < :beforeMs`. Returns the affected row count. Timestamp
	 * bounds use UTC milliseconds.
	 */
	@Query("""DELETE FROM location_observation_decision WHERE EXISTS (
		SELECT 1 FROM source_projection_join_state j WHERE j.projection_id = '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}' AND j.projection_version = ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION} AND j.state_key = 'ambient-receipt:' || location_observation_decision.observation_source_event_id)
		AND (decided_at_ms < :beforeMs OR EXISTS (SELECT 1 FROM location_accepted_receipt r WHERE r.source_event_id = location_observation_decision.observation_source_event_id))
		AND NOT EXISTS (SELECT 1 FROM location_sample s JOIN location_sample_purpose_authority p ON p.sample_id = s.id
		WHERE s.source_event_id = location_observation_decision.observation_source_event_id)""")
	suspend fun deleteUnassociatedAmbientDecisions(beforeMs: Long = Long.MAX_VALUE): Int

	/**
	 * Deletes `location_sample` rows matching the supplied scope. Returns the affected row count.
	 */
	@Query("""DELETE FROM location_sample WHERE EXISTS (SELECT 1 FROM location_accepted_receipt r WHERE r.sample_id = location_sample.id)
		AND NOT EXISTS (SELECT 1 FROM location_sample_purpose_authority p WHERE p.sample_id = location_sample.id)""")
	suspend fun deleteUnassociatedSamples(): Int

	/**
	 * Deletes `source_projection_join_state` rows matching the supplied scope. Query conditions
	 * include `projection_id =
	 * '${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}'`, `state_key LIKE
	 * 'ambient-curation:%'`, `state_key LIKE 'ambient-receipt:%'`. Returns the affected row count.
	 */
	@Query(("DELETE FROM source_projection_join_state WHERE projection_id = " +
        "'${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_ID}' AND projection_version " +
        "= ${SourceDestinationOwnerEntity.LOCATION_CANONICAL_HANDOFF_PROJECTION_VERSION} AND (state_key " +
        "LIKE 'ambient-curation:%' OR state_key LIKE 'ambient-receipt:%')"))
	suspend fun deleteAmbientCurationState(): Int

	/**
	 * Inserts [LocationRegistrationPlanEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertRegistrationPlanIfAbsent(value: LocationRegistrationPlanEntity): Long

	/**
	 * Reads `location_registration_plan` rows. Query conditions include `registration_generation =
	 * :generation`, `configuration_revision = :configurationRevision`, `authorization_revision =
	 * :authorizationRevision`. Returns null when no row matches.
	 */
	@Query(("SELECT * FROM location_registration_plan WHERE registration_generation = :generation AND " +
        "configuration_revision = :configurationRevision AND authorization_revision = " +
        ":authorizationRevision"))
	suspend fun registrationPlan(generation: Long, configurationRevision: Long,
        authorizationRevision: Long): LocationRegistrationPlanEntity?

	/**
	 * Reads `location_registration_plan` rows. Query conditions include `registration_generation =
	 * :generation`. Orders by `authorization_revision DESC`. Returns at most 1 row. Returns null when
	 * no row matches.
	 */
	@Query("SELECT * FROM location_registration_plan WHERE registration_generation = :generation " +
		"ORDER BY authorization_revision DESC LIMIT 1")
	suspend fun latestRegistrationPlan(generation: Long): LocationRegistrationPlanEntity?

	/**
	 * Inserts [LocationAcceptedReceiptEntity] rows. Existing conflicting rows remain unchanged. An
	 * ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertReceiptIfAbsent(value: LocationAcceptedReceiptEntity): Long

	/**
	 * Reads `location_accepted_receipt` rows. Query conditions include `sample_id = :sampleId`.
	 * Returns null when no row matches.
	 */
	@Query("SELECT * FROM location_accepted_receipt WHERE sample_id = :sampleId")
	suspend fun receipt(sampleId: Long): LocationAcceptedReceiptEntity?

	/**
	 * Reads `location_accepted_receipt` rows. Query conditions include `source_event_id = :eventId`.
	 * Returns null when no row matches.
	 */
	@Query("SELECT * FROM location_accepted_receipt WHERE source_event_id = :eventId")
	suspend fun receiptByEvent(eventId: String): LocationAcceptedReceiptEntity?

	/**
	 * Inserts [LocationSamplePurposeAuthorityEntity] rows. Existing conflicting rows remain
	 * unchanged. An ignored insert returns -1 for that row.
	 */
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertPurposeIfAbsent(value: LocationSamplePurposeAuthorityEntity): Long

	/**
	 * Reads `location_sample_purpose_authority` rows. Query conditions include `sample_id =
	 * :sampleId`, `purpose = :purpose`, `authority_id = :authorityId`. Returns null when no row
	 * matches.
	 */
	@Query(("SELECT * FROM location_sample_purpose_authority WHERE sample_id = :sampleId AND purpose = " +
        ":purpose AND authority_id = :authorityId"))
	suspend fun purpose(sampleId: Long, purpose: String, authorityId: String): LocationSamplePurposeAuthorityEntity?

	/**
	 * Reads `location_sample_purpose_authority` rows. Query conditions include `sample_id =
	 * :sampleId`. Orders by `purpose, authority_id`. Returns at most `limit` rows.
	 */
	@Query(("SELECT * FROM location_sample_purpose_authority WHERE sample_id = :sampleId ORDER BY purpose, " +
        "authority_id LIMIT :limit"))
	suspend fun purposes(sampleId: Long, limit: Int): List<LocationSamplePurposeAuthorityEntity>


	/**
	 * Inserts [AmbientLocationRetentionAuthorityEntity] rows. A conflicting key aborts the insert.
	 */
	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRetentionAuthority(value: AmbientLocationRetentionAuthorityEntity)

	/**
	 * Reads `ambient_location_retention_authority` rows. Query conditions include `scope = :scope`.
	 * Orders by `approval_revision DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM ambient_location_retention_authority WHERE scope = :scope " +
			"ORDER BY approval_revision DESC LIMIT 1",
	)
	suspend fun latestRetentionAuthority(scope: String): AmbientLocationRetentionAuthorityEntity?

	/**
	 * Reads `ambient_location_retention_authority` rows. Query conditions include `scope = :scope`.
	 * Orders by `approval_revision ASC`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM ambient_location_retention_authority WHERE scope = :scope " +
			"ORDER BY approval_revision ASC LIMIT :limit",
	)
	suspend fun retentionAuthorities(
		scope: String,
		limit: Int,
	): List<AmbientLocationRetentionAuthorityEntity>

	/**
	 * Reads `ambient_location_retention_authority` rows. Query conditions include `scope = :scope`,
	 * `effective_boot_id = :bootId`, `effective_elapsed_realtime_nanos <=
	 * :observedElapsedRealtimeNanos`. Orders by `effective_elapsed_realtime_nanos DESC,
	 * approval_revision DESC`. Returns at most 1 row. Returns null when no row matches.
	 */
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

	/**
	 * Returns the greatest `approval_revision` in `ambient_location_retention_authority`. Query
	 * conditions include `scope = :scope`. Returns 0 when no saved value exists.
	 */
	@Query(
		"SELECT COALESCE(MAX(approval_revision), 0) FROM ambient_location_retention_authority " +
			"WHERE scope = :scope",
	)
	suspend fun maximumRetentionApprovalRevision(scope: String): Long

}
