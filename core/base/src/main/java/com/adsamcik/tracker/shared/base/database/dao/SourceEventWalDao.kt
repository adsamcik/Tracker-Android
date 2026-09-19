package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity

@Dao
interface SourceEventWalDao {
	/**
	 * Preserves the historical `-1` duplicate result without executing an `INSERT OR IGNORE`.
	 *
	 * SQLite may advance an AUTOINCREMENT sequence before an ignored unique-index conflict. The
	 * scalar preflight and ABORT insert therefore share this Room transaction. Existing allocator
	 * holes from older builds remain unclassified evidence; v28 recovery must prove deletion or
	 * migration authority rather than rewinding the sequence or fabricating a gap receipt.
	 */
	@Transaction
	suspend fun insertIgnoringDuplicate(entity: SourceEventWalEntity): Long {
		if (hasAdmissionConflict(
				admissionOrdinal = entity.admissionOrdinal,
				eventId = entity.eventId,
				sourceKind = entity.sourceKind,
				providerDedupKey = entity.providerDedupKey,
				sourceInstanceId = entity.sourceInstanceId,
				sourceSequence = entity.sourceSequence,
				capturedCollectedDataEpoch = entity.capturedCollectedDataEpoch,
				clockDomainId = entity.clockDomainId,
				deliveryIdentity = entity.deliveryIdentity,
				deliveryUnitIndex = entity.deliveryUnitIndex,
			)
		) {
			return -1L
		}
		return insertAbortingOnUnexpectedConflict(entity)
	}

	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_event_wal WHERE " +
			"(:admissionOrdinal > 0 AND admission_ordinal = :admissionOrdinal) OR " +
			"event_id = :eventId OR " +
			"(:providerDedupKey IS NOT NULL AND source_kind = :sourceKind " +
			"AND provider_dedup_key = :providerDedupKey) OR " +
			"(source_kind = :sourceKind AND source_instance_id = :sourceInstanceId " +
			"AND source_sequence = :sourceSequence) OR " +
			"(:deliveryIdentity IS NOT NULL AND :deliveryUnitIndex IS NOT NULL " +
			"AND source_kind = :sourceKind " +
			"AND captured_collected_data_epoch = :capturedCollectedDataEpoch " +
			"AND clock_domain_id = :clockDomainId " +
			"AND delivery_identity = :deliveryIdentity " +
			"AND delivery_unit_index = :deliveryUnitIndex) LIMIT 1)",
	)
	suspend fun hasAdmissionConflict(
		admissionOrdinal: Long,
		eventId: String,
		sourceKind: Int,
		providerDedupKey: String?,
		sourceInstanceId: String,
		sourceSequence: Long,
		capturedCollectedDataEpoch: Long,
		clockDomainId: String,
		deliveryIdentity: String?,
		deliveryUnitIndex: Int?,
	): Boolean

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertAbortingOnUnexpectedConflict(entity: SourceEventWalEntity): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertDeliveryUnits(entities: List<SourceEventWalEntity>): List<Long>

	@Query("SELECT * FROM source_event_wal WHERE event_id = :eventId LIMIT 1")
	suspend fun getByEventId(eventId: String): SourceEventWalEntity?

	@Query("SELECT * FROM source_event_wal WHERE admission_ordinal = :admissionOrdinal LIMIT 1")
	suspend fun getByAdmissionOrdinal(admissionOrdinal: Long): SourceEventWalEntity?

	/** Payload-free size and delivery identity checked before a source adapter may read one BLOB. */
	@Query(
		"SELECT event_id, source_kind, captured_collected_data_epoch, clock_domain_id, " +
			"delivery_identity, delivery_unit_index, delivery_unit_count, LENGTH(payload) AS payload_bytes " +
			"FROM source_event_wal WHERE event_id = :eventId LIMIT 1",
	)
	suspend fun payloadPreflightByEventId(eventId: String): SourceEventWalPayloadPreflightRow?

	/** Loads one event only when SQLite has enforced the adapter's payload bound. */
	@Query(
		"SELECT * FROM source_event_wal WHERE event_id = :eventId " +
			"AND LENGTH(payload) <= :maximumPayloadBytes LIMIT 1",
	)
	suspend fun boundedPayloadByEventId(
		eventId: String,
		maximumPayloadBytes: Int,
	): SourceEventWalEntity?

	/** Loads the payload-free authority needed to reconcile one projection failure. */
	@Query(
		"SELECT admission_ordinal, source_kind, captured_collected_data_epoch, " +
			"acquired_at_ms, wall_time_ms, wall_time_uncertainty_ms, " +
			"authorization_purpose_eligibility_mask, " +
			"logical_tracking_id, service_run_id, source_instance_id, registration_generation " +
			"FROM source_event_wal " +
			"WHERE admission_ordinal = :admissionOrdinal LIMIT 1",
	)
	suspend fun projectionEligibilityByAdmissionOrdinal(
		admissionOrdinal: Long,
	): SourceEventProjectionEligibilityRow?

	/**
	 * Bounded payload-free source-local projection preflight. The materializer must reload and
	 * authenticate each exact event before deriving a product fact; this covering read only bounds
	 * scheduling and prevents a batch payload decode before lane authority is established.
	 */
	@Query(
		"SELECT event_id, admission_ordinal, authorization_purpose_eligibility_mask " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND admission_ordinal > :afterOrdinal AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun sourceProjectionCandidatesAfterThrough(
		sourceKind: Int,
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<SourceEventProjectionCandidateRow>

	@Query(
		"SELECT event_id, admission_ordinal, provider_dedup_key, source_instance_id, " +
			"registration_generation, physical_configuration_fingerprint, authorization_revision, " +
			"authorization_purpose_eligibility_mask, authorization_fingerprint, " +
			"source_sequence, activity_automation_epoch, source_policy_revision, " +
			"capture_consent_epoch, session_manifest_revision, lifecycle_lease_generation, " +
			"payload_version, payload_checksum, integrity_identity " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND provider_dedup_key = :providerDedupKey LIMIT 1",
	)
	suspend fun identityByProviderDedupKey(
		sourceKind: Int,
		providerDedupKey: String,
	): SourceEventIdentityRow?

	@Query(
		"SELECT event_id, admission_ordinal, provider_dedup_key, source_instance_id, " +
			"registration_generation, physical_configuration_fingerprint, authorization_revision, " +
			"authorization_purpose_eligibility_mask, authorization_fingerprint, " +
			"source_sequence, activity_automation_epoch, source_policy_revision, " +
			"capture_consent_epoch, session_manifest_revision, lifecycle_lease_generation, " +
			"payload_version, payload_checksum, integrity_identity " +
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

	/** Immutable WAL fields required to distinguish replay from checkpoint authority. */
	@Query(
		"SELECT event_id, admission_ordinal, delivery_unit_index, delivery_unit_count, " +
			"source_instance_id, registration_generation, physical_configuration_fingerprint, " +
			"authorization_revision, " +
			"observed_elapsed_nanos, observed_interval_start_nanos, " +
			"payload_version, payload_checksum " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND captured_collected_data_epoch = :collectedDataEpoch " +
			"AND clock_domain_id = :clockDomainId AND delivery_identity = :deliveryIdentity " +
			"ORDER BY delivery_unit_index ASC",
	)
	suspend fun deliveryUnits(
		sourceKind: Int,
		collectedDataEpoch: Long,
		clockDomainId: String,
		deliveryIdentity: String,
	): List<SourceDeliveryUnitIdentityRow>

	/** Payload-free bounded delivery scan used before any Activity payload BLOB is materialized. */
	@Query(
		"SELECT event_id, delivery_unit_index, delivery_unit_count, " +
			"LENGTH(payload) AS payload_bytes FROM source_event_wal " +
			"WHERE source_kind = :sourceKind " +
			"AND captured_collected_data_epoch = :collectedDataEpoch " +
			"AND clock_domain_id = :clockDomainId AND delivery_identity = :deliveryIdentity " +
			"ORDER BY delivery_unit_index ASC LIMIT :limit",
	)
	suspend fun deliveryPayloadPreflight(
		sourceKind: Int,
		collectedDataEpoch: Long,
		clockDomainId: String,
		deliveryIdentity: String,
		limit: Int,
	): List<SourceEventWalDeliveryPayloadPreflightRow>

	/** Bounded exact-delivery probe for source adapters with one physical delivery unit. */
	@Query(
		"SELECT event_id, admission_ordinal, delivery_unit_index, delivery_unit_count, " +
			"source_instance_id, registration_generation, physical_configuration_fingerprint, " +
			"authorization_revision, observed_elapsed_nanos, observed_interval_start_nanos, " +
			"payload_version, payload_checksum " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND captured_collected_data_epoch = :collectedDataEpoch " +
			"AND clock_domain_id = :clockDomainId AND delivery_identity = :deliveryIdentity " +
			"ORDER BY delivery_unit_index ASC LIMIT :limit",
	)
	suspend fun deliveryUnitsBounded(
		sourceKind: Int,
		collectedDataEpoch: Long,
		clockDomainId: String,
		deliveryIdentity: String,
		limit: Int,
	): List<SourceDeliveryUnitIdentityRow>

	/** Full delivery read whose per-row payload bound remains enforced by SQLite. */
	@Query(
		"SELECT * FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND captured_collected_data_epoch = :collectedDataEpoch " +
			"AND clock_domain_id = :clockDomainId AND delivery_identity = :deliveryIdentity " +
			"AND LENGTH(payload) <= :maximumPayloadBytes " +
			"ORDER BY delivery_unit_index ASC LIMIT :limit",
	)
	suspend fun deliveryEventsWithBoundedPayload(
		sourceKind: Int,
		collectedDataEpoch: Long,
		clockDomainId: String,
		deliveryIdentity: String,
		maximumPayloadBytes: Int,
		limit: Int,
	): List<SourceEventWalEntity>

	/**
	 * Bounded full-unit read used only when a source adapter must recompute a provider delivery.
	 *
	 * Payloads are intentionally included here: a persisted delivery identity cannot be authenticated
	 * from the covering identity projection alone. Callers request one overflow row and reject rather
	 * than accepting a truncated delivery.
	 */
	@Query(
		"SELECT * FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND captured_collected_data_epoch = :collectedDataEpoch " +
			"AND clock_domain_id = :clockDomainId AND delivery_identity = :deliveryIdentity " +
			"ORDER BY delivery_unit_index ASC LIMIT :limit",
	)
	suspend fun deliveryEvents(
		sourceKind: Int,
		collectedDataEpoch: Long,
		clockDomainId: String,
		deliveryIdentity: String,
		limit: Int,
	): List<SourceEventWalEntity>

	@Query(
		"SELECT * FROM source_event_wal WHERE admission_ordinal > :afterOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun eventsAfter(afterOrdinal: Long, limit: Int): List<SourceEventWalEntity>

	/**
	 * Authoritative location evidence that does not yet have its canonical raw-observation row.
	 *
	 * The WAL remains the recovery authority. Paging by its durable admission ordinal prevents a
	 * missing destination row from being hidden by projection state, while the destination's unique
	 * `source_event_id` keeps repair exactly once in effect.
	 */
	@Query(
		"SELECT wal.* FROM source_event_wal AS wal " +
			"LEFT JOIN location_observation AS observation " +
			"ON observation.source_event_id = wal.event_id " +
			"WHERE wal.source_kind = :sourceKind AND wal.admission_ordinal > :afterOrdinal " +
			"AND observation.id IS NULL " +
			"ORDER BY wal.admission_ordinal ASC LIMIT :limit",
	)
	suspend fun locationEventsMissingCanonicalObservation(
		sourceKind: Int,
		afterOrdinal: Long,
		limit: Int,
	): List<SourceEventWalEntity>

	/**
	 * Reads only the immutable released-v27 recovery interval.
	 *
	 * Admission ordinals are high-water marks, not a promise of a dense sequence. Callers may move
	 * their durable cursor directly to [throughOrdinal] after this returns no rows.
	 */
	@Query(
		"SELECT * FROM source_event_wal WHERE admission_ordinal > :afterOrdinal " +
			"AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun eventsAfterThrough(
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<SourceEventWalEntity>

	@Query(
		"SELECT * FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND admission_ordinal > :afterOrdinal AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun sourceEventsAfterThrough(
		sourceKind: Int,
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<SourceEventWalEntity>

	/**
	 * Raw bounded envelope for one run/source capture high-water.
	 *
	 * Valid rows remain anchored on the exact text service-run identity and source. A malformed,
	 * blank, or null service-run identity is visible only when an authenticated manifest revision
	 * for this run, plus the exact source, logical owner, and capture purpose, associates the row
	 * with this requested domain. Other invalid aggregate inputs sort before the newest eligible row
	 * without sweeping unrelated runs.
	 */
	@Query(
		"SELECT " +
			"typeof(event_id) || '|' || typeof(admission_ordinal) || '|' || " +
			"typeof(source_kind) || '|' || typeof(logical_tracking_id) || '|' || " +
			"typeof(service_run_id) || '|' || typeof(source_instance_id) || '|' || " +
			"typeof(registration_generation) || '|' || " +
			"typeof(authorization_purpose_eligibility_mask) AS storage_class_signature, " +
			"CASE WHEN typeof(event_id) = 'text' THEN event_id END AS event_id, " +
			"CASE WHEN typeof(admission_ordinal) = 'integer' THEN admission_ordinal END " +
			"AS admission_ordinal, " +
			"CASE WHEN typeof(source_kind) = 'integer' THEN source_kind END AS source_kind, " +
			"CASE WHEN typeof(logical_tracking_id) = 'text' THEN logical_tracking_id END " +
			"AS logical_tracking_id, " +
			"CASE WHEN typeof(service_run_id) = 'text' THEN service_run_id END AS service_run_id, " +
			"CASE WHEN typeof(source_instance_id) = 'text' THEN source_instance_id END " +
			"AS source_instance_id, " +
			"CASE WHEN typeof(registration_generation) = 'integer' " +
			"THEN registration_generation END AS registration_generation, " +
			"CASE WHEN typeof(authorization_purpose_eligibility_mask) = 'integer' " +
			"THEN authorization_purpose_eligibility_mask END " +
			"AS authorization_purpose_eligibility_mask " +
			"FROM source_event_wal WHERE (" +
			"(" +
			"typeof(service_run_id) = 'text' AND service_run_id = :serviceRunId AND " +
			"(CAST(source_kind AS INTEGER) = :sourceKind OR typeof(source_kind) != 'integer')" +
			") OR (" +
			"(typeof(service_run_id) != 'text' OR (typeof(service_run_id) = 'text' AND " +
			"length(trim(service_run_id, ' ' || char(9) || char(10) || char(11) || " +
			"char(12) || char(13))) = 0)) AND " +
			"typeof(source_kind) = 'integer' AND source_kind = :sourceKind AND " +
			"typeof(logical_tracking_id) = 'text' AND logical_tracking_id = :logicalTrackingId AND " +
			"typeof(session_manifest_revision) = 'integer' AND " +
			"session_manifest_revision IN (:runManifestRevisions) AND " +
			"typeof(authorization_purpose_eligibility_mask) = 'integer' AND " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0" +
			")) AND (" +
			"typeof(event_id) != 'text' OR trim(event_id) = '' OR " +
			"typeof(admission_ordinal) != 'integer' OR admission_ordinal <= 0 OR " +
			"(typeof(authorization_purpose_eligibility_mask) = 'integer' AND " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0 AND " +
			"admission_ordinal > :throughOrdinal) OR " +
			"typeof(source_kind) != 'integer' OR source_kind != :sourceKind OR " +
			"typeof(logical_tracking_id) != 'text' OR logical_tracking_id != :logicalTrackingId OR " +
			"typeof(service_run_id) != 'text' OR service_run_id != :serviceRunId OR " +
			"typeof(source_instance_id) != 'text' OR trim(source_instance_id) = '' OR " +
			"typeof(registration_generation) != 'integer' OR registration_generation <= 0 OR " +
			"typeof(authorization_purpose_eligibility_mask) != 'integer' OR " +
			"authorization_purpose_eligibility_mask < 0 OR " +
			"(authorization_purpose_eligibility_mask & :allowedPurposeMask) != " +
			"authorization_purpose_eligibility_mask OR " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0) " +
			"ORDER BY CASE WHEN (" +
			"typeof(event_id) != 'text' OR trim(event_id) = '' OR " +
			"typeof(admission_ordinal) != 'integer' OR admission_ordinal <= 0 OR " +
			"(typeof(authorization_purpose_eligibility_mask) = 'integer' AND " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0 AND " +
			"admission_ordinal > :throughOrdinal) OR " +
			"typeof(source_kind) != 'integer' OR source_kind != :sourceKind OR " +
			"typeof(logical_tracking_id) != 'text' OR logical_tracking_id != :logicalTrackingId OR " +
			"typeof(service_run_id) != 'text' OR service_run_id != :serviceRunId OR " +
			"typeof(source_instance_id) != 'text' OR trim(source_instance_id) = '' OR " +
			"typeof(registration_generation) != 'integer' OR registration_generation <= 0 OR " +
			"typeof(authorization_purpose_eligibility_mask) != 'integer' OR " +
			"authorization_purpose_eligibility_mask < 0 OR " +
			"(authorization_purpose_eligibility_mask & :allowedPurposeMask) != " +
			"authorization_purpose_eligibility_mask) THEN 0 ELSE 1 END, " +
			"admission_ordinal DESC, rowid DESC LIMIT :limit",
	)
	suspend fun rawRunSourceCaptureHighWater(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		runManifestRevisions: List<Long>,
		throughOrdinal: Long,
		capturePurposeMask: Long,
		allowedPurposeMask: Long,
		limit: Int,
	): List<RawSourceRunWalHighWaterEvidence>

	/**
	 * Groups exact service-run WAL by provider generation without binding the manifest history.
	 *
	 * Manifest membership is audited separately so a valid row is never compared with only one
	 * chunk of the authenticated run revisions.
	 */
	@Query(
		"SELECT " +
			"CASE WHEN typeof(source_instance_id) = 'text' THEN source_instance_id END " +
			"AS source_instance_id, " +
			"CASE WHEN typeof(registration_generation) = 'integer' " +
			"THEN registration_generation END AS registration_generation, " +
			"CASE WHEN typeof(lifecycle_lease_generation) = 'integer' " +
			"THEN lifecycle_lease_generation END AS lifecycle_lease_generation, " +
			"SUM(CASE WHEN (" +
			"typeof(event_id) != 'text' OR trim(event_id) = '' OR " +
			"typeof(admission_ordinal) != 'integer' OR admission_ordinal <= 0 OR " +
			"admission_ordinal > :throughOrdinal OR " +
			"typeof(source_kind) != 'integer' OR source_kind != :sourceKind OR " +
			"typeof(logical_tracking_id) != 'text' OR logical_tracking_id != :logicalTrackingId OR " +
			"typeof(service_run_id) != 'text' OR service_run_id != :serviceRunId OR " +
			"typeof(source_instance_id) != 'text' OR trim(source_instance_id) = '' OR " +
			"typeof(registration_generation) != 'integer' OR registration_generation <= 0 OR " +
			"typeof(session_manifest_revision) != 'integer' OR session_manifest_revision <= 0 OR " +
			"typeof(lifecycle_lease_generation) != 'integer' OR " +
			"lifecycle_lease_generation <= 0 OR " +
			"typeof(authorization_purpose_eligibility_mask) != 'integer' OR " +
			"authorization_purpose_eligibility_mask < 0 OR " +
			"(authorization_purpose_eligibility_mask & :allowedPurposeMask) != " +
			"authorization_purpose_eligibility_mask" +
			") THEN 1 ELSE 0 END) AS malformed_row_count, " +
			"SUM(CASE WHEN (" +
			"typeof(authorization_purpose_eligibility_mask) = 'integer' AND " +
			"authorization_purpose_eligibility_mask >= 0 AND " +
			"(authorization_purpose_eligibility_mask & :allowedPurposeMask) = " +
			"authorization_purpose_eligibility_mask AND " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0" +
			") THEN 1 ELSE 0 END) AS product_eligible_row_count, " +
			"MAX(CASE WHEN (" +
			"typeof(admission_ordinal) = 'integer' AND admission_ordinal > 0 AND " +
			"admission_ordinal <= :throughOrdinal AND " +
			"typeof(authorization_purpose_eligibility_mask) = 'integer' AND " +
			"authorization_purpose_eligibility_mask >= 0 AND " +
			"(authorization_purpose_eligibility_mask & :allowedPurposeMask) = " +
			"authorization_purpose_eligibility_mask AND " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0" +
			") THEN admission_ordinal END) AS high_water_admission_ordinal " +
			"FROM source_event_wal WHERE " +
			"typeof(service_run_id) = 'text' AND service_run_id = :serviceRunId AND " +
			"(CAST(source_kind AS INTEGER) = :sourceKind OR typeof(source_kind) != 'integer') AND (" +
			"typeof(event_id) != 'text' OR trim(event_id) = '' OR " +
			"typeof(admission_ordinal) != 'integer' OR admission_ordinal <= 0 OR " +
			"(typeof(authorization_purpose_eligibility_mask) = 'integer' AND " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0 AND " +
			"admission_ordinal > :throughOrdinal) OR " +
			"typeof(source_kind) != 'integer' OR source_kind != :sourceKind OR " +
			"typeof(logical_tracking_id) != 'text' OR logical_tracking_id != :logicalTrackingId OR " +
			"typeof(service_run_id) != 'text' OR service_run_id != :serviceRunId OR " +
			"typeof(source_instance_id) != 'text' OR trim(source_instance_id) = '' OR " +
			"typeof(registration_generation) != 'integer' OR registration_generation <= 0 OR " +
			"typeof(session_manifest_revision) != 'integer' OR session_manifest_revision <= 0 OR " +
			"typeof(lifecycle_lease_generation) != 'integer' OR " +
			"lifecycle_lease_generation <= 0 OR " +
			"typeof(authorization_purpose_eligibility_mask) != 'integer' OR " +
			"authorization_purpose_eligibility_mask < 0 OR " +
			"(authorization_purpose_eligibility_mask & :allowedPurposeMask) != " +
			"authorization_purpose_eligibility_mask OR " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0) " +
			"GROUP BY typeof(source_instance_id), " +
			"CASE WHEN typeof(source_instance_id) = 'text' THEN source_instance_id END, " +
			"typeof(registration_generation), " +
			"CASE WHEN typeof(registration_generation) = 'integer' " +
			"THEN registration_generation END, " +
			"typeof(lifecycle_lease_generation), " +
			"CASE WHEN typeof(lifecycle_lease_generation) = 'integer' " +
			"THEN lifecycle_lease_generation END " +
			"ORDER BY malformed_row_count DESC, high_water_admission_ordinal DESC LIMIT :limit",
	)
	suspend fun rawExactRunSourceCaptureGenerations(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		throughOrdinal: Long,
		capturePurposeMask: Long,
		allowedPurposeMask: Long,
		limit: Int,
	): List<RawSourceRunWalGenerationEvidence>

	/**
	 * Groups every exact-run WAL row considered by [rawExactRunSourceCaptureGenerations] by its raw
	 * manifest revision. The bounded result lets callers compare each row with the complete
	 * authenticated revision set without a SQLite `IN` bind for that full set.
	 */
	@Query(
		"SELECT CASE WHEN typeof(session_manifest_revision) = 'integer' " +
			"THEN session_manifest_revision END AS session_manifest_revision, " +
			"COUNT(*) AS associated_row_count " +
			"FROM source_event_wal WHERE " +
			"typeof(service_run_id) = 'text' AND service_run_id = :serviceRunId AND " +
			"(CAST(source_kind AS INTEGER) = :sourceKind OR typeof(source_kind) != 'integer') AND (" +
			"typeof(event_id) != 'text' OR trim(event_id) = '' OR " +
			"typeof(admission_ordinal) != 'integer' OR admission_ordinal <= 0 OR " +
			"(typeof(authorization_purpose_eligibility_mask) = 'integer' AND " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0 AND " +
			"admission_ordinal > :throughOrdinal) OR " +
			"typeof(source_kind) != 'integer' OR source_kind != :sourceKind OR " +
			"typeof(logical_tracking_id) != 'text' OR logical_tracking_id != :logicalTrackingId OR " +
			"typeof(service_run_id) != 'text' OR service_run_id != :serviceRunId OR " +
			"typeof(source_instance_id) != 'text' OR trim(source_instance_id) = '' OR " +
			"typeof(registration_generation) != 'integer' OR registration_generation <= 0 OR " +
			"typeof(session_manifest_revision) != 'integer' OR session_manifest_revision <= 0 OR " +
			"typeof(lifecycle_lease_generation) != 'integer' OR " +
			"lifecycle_lease_generation <= 0 OR " +
			"typeof(authorization_purpose_eligibility_mask) != 'integer' OR " +
			"authorization_purpose_eligibility_mask < 0 OR " +
			"(authorization_purpose_eligibility_mask & :allowedPurposeMask) != " +
			"authorization_purpose_eligibility_mask OR " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0) " +
			"GROUP BY typeof(session_manifest_revision), " +
			"CASE WHEN typeof(session_manifest_revision) = 'integer' " +
			"THEN session_manifest_revision END " +
			"ORDER BY associated_row_count DESC, session_manifest_revision ASC LIMIT :limit",
	)
	suspend fun rawExactRunSourceCaptureManifestRevisions(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		throughOrdinal: Long,
		capturePurposeMask: Long,
		allowedPurposeMask: Long,
		limit: Int,
	): List<RawSourceRunWalManifestRevisionEvidence>

	/**
	 * Finds non-exact service-run identities only through an exact source, logical owner,
	 * authenticated revision chunk, and capture-purpose association.
	 */
	@Query(
		"SELECT rowid FROM source_event_wal WHERE " +
			"(typeof(service_run_id) != 'text' OR service_run_id != :serviceRunId) AND " +
			"typeof(source_kind) = 'integer' AND source_kind = :sourceKind AND " +
			"typeof(logical_tracking_id) = 'text' AND logical_tracking_id = :logicalTrackingId AND " +
			"typeof(session_manifest_revision) = 'integer' AND " +
			"session_manifest_revision IN (:runManifestRevisions) AND " +
			"typeof(authorization_purpose_eligibility_mask) = 'integer' AND " +
			"(authorization_purpose_eligibility_mask & :capturePurposeMask) != 0 " +
			"ORDER BY rowid ASC LIMIT :limit",
	)
	suspend fun rawMalformedServiceRunSourceCaptureAssociations(
		sourceKind: Int,
		logicalTrackingId: String,
		serviceRunId: String,
		runManifestRevisions: List<Long>,
		capturePurposeMask: Long,
		limit: Int,
	): List<Long>

	/** Payload-free ordered preflight for a bounded source-local projection pass. */
	@Query(
		"SELECT event_id, admission_ordinal FROM source_event_wal " +
			"WHERE source_kind = :sourceKind AND admission_ordinal > :afterOrdinal " +
			"AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun sourceProjectionEventsAfterThrough(
		sourceKind: Int,
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<SourceProjectionEventIdentityRow>

	/** Payload-free global ordering and source sequence evidence for cursor continuity proofs. */
	@Query(
		"SELECT event_id, admission_ordinal, source_kind, source_instance_id, " +
			"registration_generation, source_sequence, logical_tracking_id, service_run_id " +
			"FROM source_event_wal WHERE admission_ordinal > :afterOrdinal " +
			"AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal ASC LIMIT :limit",
	)
	suspend fun continuityEventsAfterThrough(
		afterOrdinal: Long,
		throughOrdinal: Long,
		limit: Int,
	): List<SourceEventContinuityRow>

	@Query(
		"SELECT event_id, admission_ordinal, source_kind, source_instance_id, " +
			"registration_generation, source_sequence, logical_tracking_id, service_run_id " +
			"FROM source_event_wal WHERE source_kind = :sourceKind " +
			"AND source_instance_id = :sourceInstanceId " +
			"AND registration_generation = :registrationGeneration " +
			"AND admission_ordinal <= :throughOrdinal " +
			"ORDER BY admission_ordinal DESC LIMIT 1",
	)
	suspend fun latestContinuityEventAtOrBefore(
		sourceKind: Int,
		sourceInstanceId: String,
		registrationGeneration: Long,
		throughOrdinal: Long,
	): SourceEventContinuityRow?

	@Query("SELECT MAX(admission_ordinal) FROM source_event_wal")
	suspend fun maximumAdmissionOrdinal(): Long?

	/** Durable allocator high-water, including rows removed by retention or deletion. */
	@Query(
		"SELECT MAX(" +
			"COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'source_event_wal'), 0), " +
			"COALESCE((SELECT MAX(admission_ordinal) FROM source_event_wal), 0))",
	)
	suspend fun admissionAllocatorHighWater(): Long

	@Query("SELECT MIN(admission_ordinal) FROM source_event_wal")
	suspend fun minimumAdmissionOrdinal(): Long?

	@Query(
		"SELECT MIN(admission_ordinal) FROM source_event_wal " +
			"WHERE integrity_identity IN ('LEGACY_PENDING_CHECKSUM', 'LEGACY_CHECKSUM_VERIFIED', " +
			"'LEGACY_CHECKSUM_MISMATCH', 'LEGACY_UNKNOWN')",
	)
	suspend fun minimumUnqualifiedIntegrityOrdinal(): Long?

	@Query(
		"UPDATE source_event_wal SET integrity_identity = :classification " +
			"WHERE admission_ordinal = :admissionOrdinal AND integrity_identity = 'LEGACY_PENDING_CHECKSUM'",
	)
	suspend fun classifyPendingLegacyPayload(admissionOrdinal: Long, classification: String): Int

	@Query("SELECT COUNT(*) FROM source_event_wal")
	suspend fun countAll(): Long

	@Query("SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM source_event_wal")
	suspend fun payloadBytes(): Long

	@Query(
		"SELECT DISTINCT source_kind FROM source_event_wal " +
			"WHERE admission_ordinal <= :safeOrdinal ORDER BY source_kind",
	)
	suspend fun sourceKindsThrough(safeOrdinal: Long): List<Int>

	/**
	 * Deletes TTL-eligible facts for one source through its own safe checkpoint.
	 *
	 * Outbox delivery is independent once its payload is durable, so a pending non-retaining control
	 * effect cannot pin raw WAL. Receipt cleanup runs first while the source association is present;
	 * any bounded remainder is handled conservatively by writer identity as an outbox-only row.
	 */
	@Query(
		"DELETE FROM source_event_wal WHERE admission_ordinal IN (" +
			"SELECT wal.admission_ordinal FROM source_event_wal AS wal " +
			"WHERE wal.source_kind = :sourceKind AND wal.created_at_ms < :createdBeforeMs " +
			"AND wal.admission_ordinal <= :safeOrdinal AND NOT (" +
			":sourceKind = " + STEPS_SOURCE_KIND_SQL + " AND " +
			"wal.logical_tracking_id IS NOT NULL AND wal.service_run_id IS NOT NULL AND " +
			"wal.admission_ordinal = (" +
			"SELECT MAX(candidate.admission_ordinal) FROM source_event_wal AS candidate " +
			"WHERE candidate.source_kind = wal.source_kind " +
			"AND candidate.source_instance_id = wal.source_instance_id " +
			"AND candidate.registration_generation = wal.registration_generation " +
			"AND candidate.logical_tracking_id = wal.logical_tracking_id " +
			"AND candidate.service_run_id = wal.service_run_id " +
			"AND (candidate.authorization_purpose_eligibility_mask & " +
			SESSION_CAPTURE_MASK_SQL + ") != 0" +
			") AND (" +
			"EXISTS (SELECT 1 FROM provider_registration_generation AS registration " +
			"WHERE registration.source_kind = wal.source_kind " +
			"AND registration.source_instance_id = wal.source_instance_id " +
			"AND registration.registration_generation = wal.registration_generation " +
			"AND registration.status IN ('RESERVED', 'ACTIVE', 'RETIRING')) " +
			"OR EXISTS (SELECT 1 FROM source_service_run AS run " +
			"WHERE run.service_run_id = wal.service_run_id " +
			"AND run.logical_tracking_id = wal.logical_tracking_id " +
			"AND (run.completed_at_ms IS NULL OR run.state NOT IN ('FINALIZED', 'CLOSED', 'FAILED')))" +
			")) ORDER BY wal.created_at_ms, wal.admission_ordinal LIMIT :limit)",
	)
	suspend fun deleteProjectedSourceBatch(
		sourceKind: Int,
		safeOrdinal: Long,
		createdBeforeMs: Long,
		limit: Int,
	): Int

	@Query("DELETE FROM source_event_wal")
	fun deleteAll()
}

private const val STEPS_SOURCE_KIND_SQL = "3"
private const val SESSION_CAPTURE_MASK_SQL = "4"

/** Covering projection used by duplicate admission checks so payload BLOBs are never read. */
data class SourceEventIdentityRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "provider_dedup_key") val providerDedupKey: String?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint") val physicalConfigurationFingerprint: String?,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long?,
	@ColumnInfo(name = "authorization_purpose_eligibility_mask") val authorizationPurposeEligibilityMask: Long,
	@ColumnInfo(name = "authorization_fingerprint") val authorizationFingerprint: String?,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "activity_automation_epoch") val activityAutomationEpoch: Long?,
	@ColumnInfo(name = "source_policy_revision") val sourcePolicyRevision: Long?,
	@ColumnInfo(name = "capture_consent_epoch") val captureConsentEpoch: Long?,
	@ColumnInfo(name = "session_manifest_revision") val sessionManifestRevision: Long?,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long?,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
	@ColumnInfo(name = "integrity_identity") val integrityIdentity: String,
)

/** Payload-free lifecycle identity used to reconcile a terminal source-writer failure. */
data class SourceEventProjectionEligibilityRow(
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "captured_collected_data_epoch") val capturedCollectedDataEpoch: Long,
	@ColumnInfo(name = "acquired_at_ms") val acquiredAtMs: Long,
	@ColumnInfo(name = "wall_time_ms") val wallTimeMs: Long?,
	@ColumnInfo(name = "wall_time_uncertainty_ms") val wallTimeUncertaintyMs: Long?,
	@ColumnInfo(name = "authorization_purpose_eligibility_mask")
	val authorizationPurposeEligibilityMask: Long,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
)

/** Payload-free identity used to prove a decoded source projection page is complete. */
data class SourceProjectionEventIdentityRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
)

data class SourceEventContinuityRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "source_sequence") val sourceSequence: Long,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
)

/** Raw payload-free evidence used before accepting a source/run WAL high-water. */
data class RawSourceRunWalHighWaterEvidence(
	@ColumnInfo(name = "storage_class_signature") val storageClassSignature: String?,
	@ColumnInfo(name = "event_id") val eventId: String?,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long?,
	@ColumnInfo(name = "source_kind") val sourceKind: Long?,
	@ColumnInfo(name = "logical_tracking_id") val logicalTrackingId: String?,
	@ColumnInfo(name = "service_run_id") val serviceRunId: String?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
	@ColumnInfo(name = "authorization_purpose_eligibility_mask")
	val authorizationPurposeEligibilityMask: Long?,
)

/** Raw grouped provider-generation evidence for one bounded source/run WAL settlement. */
data class RawSourceRunWalGenerationEvidence(
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String?,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long?,
	@ColumnInfo(name = "lifecycle_lease_generation") val lifecycleLeaseGeneration: Long?,
	@ColumnInfo(name = "malformed_row_count") val malformedRowCount: Long?,
	@ColumnInfo(name = "product_eligible_row_count") val productEligibleRowCount: Long?,
	@ColumnInfo(name = "high_water_admission_ordinal") val highWaterAdmissionOrdinal: Long?,
)

/** Raw manifest-revision grouping for an exact service-run WAL audit. */
data class RawSourceRunWalManifestRevisionEvidence(
	@ColumnInfo(name = "session_manifest_revision") val sessionManifestRevision: Long?,
	@ColumnInfo(name = "associated_row_count") val associatedRowCount: Long?,
)

/** Payload-free identity for one bounded Cell/Wi-Fi projection scheduling attempt. */
data class SourceEventProjectionCandidateRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "authorization_purpose_eligibility_mask")
	val authorizationPurposeEligibilityMask: Long,
)

/** Payload-free projection used to recognize an exact replay of a process-stable delivery. */
data class SourceDeliveryUnitIdentityRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "admission_ordinal") val admissionOrdinal: Long,
	@ColumnInfo(name = "delivery_unit_index") val deliveryUnitIndex: Int?,
	@ColumnInfo(name = "delivery_unit_count") val deliveryUnitCount: Int?,
	@ColumnInfo(name = "source_instance_id") val sourceInstanceId: String,
	@ColumnInfo(name = "registration_generation") val registrationGeneration: Long,
	@ColumnInfo(name = "physical_configuration_fingerprint")
	val physicalConfigurationFingerprint: String?,
	@ColumnInfo(name = "authorization_revision") val authorizationRevision: Long?,
	@ColumnInfo(name = "observed_elapsed_nanos") val observedElapsedNanos: Long,
	@ColumnInfo(name = "observed_interval_start_nanos") val observedIntervalStartNanos: Long?,
	@ColumnInfo(name = "payload_version") val payloadVersion: Int,
	@ColumnInfo(name = "payload_checksum") val payloadChecksum: String,
)

/** Payload-free preflight for the selected WAL event. */
data class SourceEventWalPayloadPreflightRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "source_kind") val sourceKind: Int,
	@ColumnInfo(name = "captured_collected_data_epoch") val capturedCollectedDataEpoch: Long,
	@ColumnInfo(name = "clock_domain_id") val clockDomainId: String,
	@ColumnInfo(name = "delivery_identity") val deliveryIdentity: String?,
	@ColumnInfo(name = "delivery_unit_index") val deliveryUnitIndex: Int?,
	@ColumnInfo(name = "delivery_unit_count") val deliveryUnitCount: Int?,
	@ColumnInfo(name = "payload_bytes") val payloadBytes: Long,
)

/** Bounded payload-free delivery member used to reject oversized siblings before BLOB reads. */
data class SourceEventWalDeliveryPayloadPreflightRow(
	@ColumnInfo(name = "event_id") val eventId: String,
	@ColumnInfo(name = "delivery_unit_index") val deliveryUnitIndex: Int?,
	@ColumnInfo(name = "delivery_unit_count") val deliveryUnitCount: Int?,
	@ColumnInfo(name = "payload_bytes") val payloadBytes: Long,
)
