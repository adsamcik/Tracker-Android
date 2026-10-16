package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCaptureAdmissionBarrierEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

data class PriorProcessRegistrationReconciliationResult(
	val failedReservations: Int,
	val retiredActiveRegistrations: Int,
	val completedRetirements: Int,
) {
	val affectedRegistrations: Int
		get() = failedReservations + retiredActiveRegistrations + completedRetirements
}

/**
 * Greatest saved admission ordinal and source sequence for the selected run; both values are
 * nonnegative.
 */
data class SourceRunAdmissionHighWaterRow(
	@ColumnInfo(name = "last_admission_ordinal") val lastAdmissionOrdinal: Long,
	@ColumnInfo(name = "last_source_sequence") val lastSourceSequence: Long,
) {
	init {
		require(lastAdmissionOrdinal >= 0L)
		require(lastSourceSequence >= 0L)
	}
}

// Keep the SourceBrokerDao SQL contract together; query text accounts for this declaration's size.
@Suppress("LargeClass")
@Dao
interface SourceBrokerDao {
	/**
	 * Returns the greatest `admission_ordinal` in `source_event_wal`. Query conditions include
	 * `source_kind = :sourceKind`, `source_instance_id = :sourceInstanceId`, `registration_generation
	 * = :registrationGeneration`, `logical_tracking_id = :logicalTrackingId`. Returns 0 when no saved
	 * value exists.
	 */
	@Query(
		"""
		SELECT
			COALESCE(MAX(admission_ordinal), 0) AS last_admission_ordinal,
			COALESCE(MAX(source_sequence), 0) AS last_source_sequence
		FROM source_event_wal
		WHERE source_kind = :sourceKind
			AND source_instance_id = :sourceInstanceId
			AND registration_generation = :registrationGeneration
			AND logical_tracking_id = :logicalTrackingId
			AND service_run_id = :serviceRunId
			AND (authorization_purpose_eligibility_mask & :capturePurposeMask) != 0
		""",
	)
	suspend fun runCaptureAdmissionHighWater(
		sourceKind: Int,
		sourceInstanceId: String,
		registrationGeneration: Long,
		logicalTrackingId: String,
		serviceRunId: String,
		capturePurposeMask: Long,
	): SourceRunAdmissionHighWaterRow
	/**
	 * Reads `source_capture_admission_barrier` rows. Query conditions include `source_kind =
	 * :sourceKind`, `registration_generation = :registrationGeneration`. Returns null when no row
	 * matches.
	 */
	@Query(
		"SELECT * FROM source_capture_admission_barrier " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration",
	)
	suspend fun captureAdmissionBarrier(
		sourceKind: Int,
		registrationGeneration: Long,
	): SourceCaptureAdmissionBarrierEntity?

	/**
	 * Writes `source_capture_admission_barrier` rows using the SQL conflict policy. Query conditions
	 * include `registration.source_kind = :sourceKind`, `registration.registration_generation =
	 * :registrationGeneration`, `registration.source_instance_id = :sourceInstanceId`,
	 * `registration.capture_callback_barrier_authorization_revision >=
	 * :throughAuthorizationRevision`.
	 */
	@Query(
		"""
		INSERT INTO source_capture_admission_barrier (
			source_kind,
			registration_generation,
			source_instance_id,
			through_authorization_revision,
			last_admission_ordinal,
			last_source_sequence,
			sealed_elapsed_realtime_nanos,
			sealed_at_ms
		)
		SELECT
			:sourceKind,
			:registrationGeneration,
			:sourceInstanceId,
			:throughAuthorizationRevision,
			COALESCE(MAX(wal.admission_ordinal), 0),
			COALESCE(MAX(wal.source_sequence), 0),
			:sealedElapsedRealtimeNanos,
			:sealedAtMs
		FROM provider_registration_generation AS registration
		LEFT JOIN source_event_wal AS wal
			ON wal.source_kind = registration.source_kind
			AND wal.registration_generation = registration.registration_generation
			AND wal.source_instance_id = registration.source_instance_id
			AND wal.authorization_revision <= :throughAuthorizationRevision
			AND (wal.authorization_purpose_eligibility_mask & :capturePurposeMask) != 0
		WHERE registration.source_kind = :sourceKind
			AND registration.registration_generation = :registrationGeneration
			AND registration.source_instance_id = :sourceInstanceId
			AND registration.capture_callback_barrier_authorization_revision >=
				:throughAuthorizationRevision
		GROUP BY registration.source_kind,
			registration.registration_generation,
			registration.source_instance_id
		HAVING COUNT(DISTINCT registration.registration_generation) = 1
		ON CONFLICT(source_kind, registration_generation) DO UPDATE SET
			source_instance_id = excluded.source_instance_id,
			through_authorization_revision = excluded.through_authorization_revision,
			last_admission_ordinal = excluded.last_admission_ordinal,
			last_source_sequence = excluded.last_source_sequence,
			sealed_elapsed_realtime_nanos = excluded.sealed_elapsed_realtime_nanos,
			sealed_at_ms = excluded.sealed_at_ms
		WHERE excluded.source_instance_id =
				source_capture_admission_barrier.source_instance_id
			AND excluded.through_authorization_revision >
				source_capture_admission_barrier.through_authorization_revision
		""",
	)
	suspend fun sealCaptureAdmissionBarrier(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		throughAuthorizationRevision: Long,
		capturePurposeMask: Long,
		sealedElapsedRealtimeNanos: Long,
		sealedAtMs: Long,
	)
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertDemands(entities: List<SourceDemandEntity>): List<Long>

	@Query(
		// RETIRING remains registration-eligible until the source callback barrier is drained.
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND status IN ('ACTIVE', 'RETIRING') " +
			"ORDER BY purpose, consumer_id, demand_id",
	)
	suspend fun activeDemands(sourceKind: Int): List<SourceDemandEntity>

	/** Bounded quiescence audit; callers request one overflow row. */
	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND status IN ('ACTIVE', 'RETIRING') " +
			"ORDER BY purpose, consumer_id, demand_id LIMIT :limit",
	)
	suspend fun activeDemandsBounded(sourceKind: Int, limit: Int): List<SourceDemandEntity>

	@Query(
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind AND status = 'ACTIVE' " +
			"ORDER BY purpose, consumer_id, demand_id",
	)
	suspend fun authorizationDemands(sourceKind: Int): List<SourceDemandEntity>

	@Query(
		"SELECT * FROM source_demand WHERE consumer_id = :consumerId AND status IN ('ACTIVE', 'RETIRING') " +
			"ORDER BY source_kind, purpose, demand_id",
	)
	suspend fun currentDemands(consumerId: String): List<SourceDemandEntity>

	@Query(
		"SELECT * FROM source_demand WHERE consumer_id = :consumerId " +
			"ORDER BY requested_elapsed_realtime_nanos, source_kind, purpose, demand_id",
	)
	suspend fun demandHistory(consumerId: String): List<SourceDemandEntity>

	/**
	 * Reads `source_demand` rows. Query conditions include `consumer_id = :consumerId`,
	 * `service_run_id = :serviceRunId`, `manifest_revision = :manifestRevision`. Orders by
	 * `lifecycle_lease_generation, source_kind, purpose, demand_id`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM source_demand WHERE consumer_id = :consumerId " +
			"AND service_run_id = :serviceRunId AND manifest_revision = :manifestRevision " +
			"ORDER BY lifecycle_lease_generation, source_kind, purpose, demand_id LIMIT :limit",
	)
	suspend fun preparedSessionDemandHistoryBounded(
		consumerId: String,
		serviceRunId: String,
		manifestRevision: Long,
		limit: Int,
	): List<SourceDemandEntity>

	/**
	 * Reads distinct `source_caller_authority_reference` values from `source_demand`. Query
	 * conditions include `consumer_id = :consumerId`.
	 */
	@Query(
		"SELECT DISTINCT source_caller_authority_reference FROM source_demand " +
			"WHERE consumer_id = :consumerId AND source_caller_authority_reference IS NOT NULL",
	)
	suspend fun callerAuthorityReferences(consumerId: String): List<String>

	/** Immutable demand terms referenced by an observed-time authorization revision. */
	@Query("SELECT * FROM source_demand WHERE demand_id IN (:demandIds) ORDER BY demand_id")
	suspend fun demandsByIds(demandIds: Collection<String>): List<SourceDemandEntity>

	@Query(
		"UPDATE source_demand SET status = 'ACTIVE' " +
			"WHERE consumer_id = :consumerId AND service_run_id = :serviceRunId " +
			"AND manifest_revision = :manifestRevision " +
			"AND lifecycle_lease_generation = :leaseGeneration AND status = 'BLOCKED'",
	)
	suspend fun activatePreparedSessionDemands(
		consumerId: String,
		serviceRunId: String,
		manifestRevision: Long,
		leaseGeneration: Long,
	): Int

	/**
	 * Updates `source_demand` rows matching the supplied scope. Query conditions include `consumer_id
	 * = :consumerId`, `service_run_id = :serviceRunId`, `manifest_revision = :manifestRevision`,
	 * `lifecycle_lease_generation = :leaseGeneration`. Returns the affected row count.
	 */
	@Query(
		"UPDATE source_demand SET status = 'ACTIVE' " +
			"WHERE consumer_id = :consumerId AND service_run_id = :serviceRunId " +
			"AND manifest_revision = :manifestRevision " +
			"AND lifecycle_lease_generation = :leaseGeneration " +
			"AND demand_id IN (:demandIds) AND status = 'BLOCKED'",
	)
	suspend fun activatePreparedSessionDemandsByIds(
		consumerId: String,
		serviceRunId: String,
		manifestRevision: Long,
		leaseGeneration: Long,
		demandIds: Collection<String>,
	): Int

	/**
	 * Updates `source_demand` rows matching the supplied scope. Query conditions include `consumer_id
	 * = :consumerId`, `service_run_id = :serviceRunId`, `manifest_revision = :manifestRevision`,
	 * `lifecycle_lease_generation = :leaseGeneration`. Returns the affected row count.
	 */
	@Query(
		"UPDATE source_demand SET status = 'RETIRED', retire_boot_id = :bootId, " +
			"retire_elapsed_realtime_nanos = :elapsedRealtimeNanos, retired_at_ms = :wallTimeMs " +
			"WHERE consumer_id = :consumerId AND service_run_id = :serviceRunId " +
			"AND manifest_revision = :manifestRevision " +
			"AND lifecycle_lease_generation = :leaseGeneration " +
			"AND demand_id IN (:demandIds) AND status IN ('ACTIVE', 'BLOCKED')",
	)
	suspend fun retirePreparedSessionDemandsByIds(
		consumerId: String,
		serviceRunId: String,
		manifestRevision: Long,
		leaseGeneration: Long,
		demandIds: Collection<String>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	/**
	 * Updates `source_demand` rows matching the supplied scope. Query conditions include `consumer_id
	 * = :consumerId`, `status IN ('ACTIVE', 'BLOCKED')`. Returns the affected row count.
	 */
	@Query(
		"UPDATE source_demand SET status = CASE WHEN status = 'BLOCKED' THEN 'RETIRED' " +
			"ELSE 'RETIRING' END, retire_boot_id = :bootId, " +
			"retire_elapsed_realtime_nanos = :elapsedRealtimeNanos, retired_at_ms = :wallTimeMs " +
			"WHERE consumer_id = :consumerId AND status IN ('ACTIVE', 'BLOCKED')",
	)
	suspend fun markConsumerRetiring(
		consumerId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	/**
	 * Updates `source_demand` rows matching the supplied scope. Query conditions include `source_kind
	 * = :sourceKind`, `purpose IN (:purposes)`, `status IN ('ACTIVE', 'BLOCKED')`. Returns the
	 * affected row count.
	 */
	@Query(
		"UPDATE source_demand SET status = CASE WHEN status = 'BLOCKED' THEN 'RETIRED' " +
			"ELSE 'RETIRING' END, retire_boot_id = :bootId, " +
			"retire_elapsed_realtime_nanos = :elapsedRealtimeNanos, retired_at_ms = :wallTimeMs " +
			"WHERE source_kind = :sourceKind AND purpose IN (:purposes) " +
			"AND status IN ('ACTIVE', 'BLOCKED')",
	)
	suspend fun markSourcePurposesRetiring(
		sourceKind: Int,
		purposes: Collection<String>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	@Query(
		"UPDATE source_demand SET status = 'RETIRED', retire_boot_id = :bootId, " +
			"retire_elapsed_realtime_nanos = :elapsedRealtimeNanos, retired_at_ms = :wallTimeMs " +
		"WHERE consumer_id = :consumerId AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED')",
	)
	suspend fun retireConsumer(
		consumerId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	/** Keeps only the already authenticated live Steps demand; it does not revive any row. */
	@Query(
		"UPDATE source_demand SET status = 'RETIRED', retire_boot_id = :bootId, " +
			"retire_elapsed_realtime_nanos = :elapsedRealtimeNanos, retired_at_ms = :wallTimeMs " +
			"WHERE consumer_id = :consumerId AND demand_id != :retainedDemandId " +
			"AND status IN ('ACTIVE', 'RETIRING', 'BLOCKED')",
	)
	suspend fun retireConsumerExceptDemand(
		consumerId: String,
		retainedDemandId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	@Query("SELECT COALESCE(MAX(registration_generation), 0) FROM provider_registration_generation WHERE source_kind = :sourceKind")
	suspend fun maximumRegistrationGeneration(sourceKind: Int): Long

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertRegistration(entity: ProviderRegistrationGenerationEntity)

	@Insert(onConflict = OnConflictStrategy.ABORT)
	suspend fun insertAuthorizations(entities: List<SourceAuthorizationEntity>)

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration",
	)
	suspend fun registration(
		sourceKind: Int,
		registrationGeneration: Long,
	): ProviderRegistrationGenerationEntity?

	/**
	 * Searches the complete earlier accepted chain in the selected source clock/epoch. Failed or
	 * unaccepted generations do not mask a conflicting accepted physical registration.
	 */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM provider_registration_generation " +
			"WHERE source_kind = :sourceKind AND registration_generation < :registrationGeneration " +
			"AND clock_domain_id = :clockDomainId AND collected_data_epoch = :collectedDataEpoch AND (" +
			"(accepted_at_ms IS NULL) != (accepted_elapsed_realtime_nanos IS NULL) OR " +
			"(accepted_at_ms IS NOT NULL AND accepted_elapsed_realtime_nanos IS NOT NULL AND (" +
			"source_instance_id != :sourceInstanceId OR retired_at_ms IS NULL OR " +
			"retired_elapsed_realtime_nanos IS NULL OR " +
			"retired_elapsed_realtime_nanos > :reservedElapsedRealtimeNanos))) LIMIT 1)",
	)
	suspend fun hasConflictingAcceptedRegistrationBefore(
		sourceKind: Int,
		registrationGeneration: Long,
		clockDomainId: String,
		collectedDataEpoch: Long,
		sourceInstanceId: String,
		reservedElapsedRealtimeNanos: Long,
	): Boolean

	/** Complete later accepted-chain counterpart to [hasConflictingAcceptedRegistrationBefore]. */
	@Query(
		"SELECT EXISTS(SELECT 1 FROM provider_registration_generation " +
			"WHERE source_kind = :sourceKind AND registration_generation > :registrationGeneration " +
			"AND clock_domain_id = :clockDomainId AND collected_data_epoch = :collectedDataEpoch AND (" +
			"(accepted_at_ms IS NULL) != (accepted_elapsed_realtime_nanos IS NULL) OR " +
			"(accepted_at_ms IS NOT NULL AND accepted_elapsed_realtime_nanos IS NOT NULL AND (" +
			"source_instance_id != :sourceInstanceId OR " +
			":retiredElapsedRealtimeNanos > reserved_elapsed_realtime_nanos))) LIMIT 1)",
	)
	suspend fun hasConflictingAcceptedRegistrationAfter(
		sourceKind: Int,
		registrationGeneration: Long,
		clockDomainId: String,
		collectedDataEpoch: Long,
		sourceInstanceId: String,
		retiredElapsedRealtimeNanos: Long,
	): Boolean

	/**
	 * Counts distinct `registration_generation` in `source_authorization`. Query conditions include
	 * `source_kind = :sourceKind`, `authorization_revision = :authorizationRevision`.
	 */
	@Query(
		"SELECT COUNT(DISTINCT registration_generation) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND authorization_revision = :authorizationRevision",
	)
	suspend fun authorizationRevisionRegistrationCount(
		sourceKind: Int,
		authorizationRevision: Long,
	): Int

	/**
	 * Returns the greatest `authorization_revision` in `source_authorization`. Query conditions
	 * include `source_kind = :sourceKind`, `registration_generation < :registrationGeneration`.
	 * Returns 0 when no saved value exists.
	 */
	@Query(
		"SELECT COALESCE(MAX(authorization_revision), 0) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation < :registrationGeneration",
	)
	suspend fun maximumAuthorizationRevisionBeforeRegistration(
		sourceKind: Int,
		registrationGeneration: Long,
	): Long

	/**
	 * Returns the smallest `authorization_revision` in `source_authorization`. Query conditions
	 * include `source_kind = :sourceKind`, `registration_generation > :registrationGeneration`.
	 * Returns 0 when no saved value exists.
	 */
	@Query(
		"SELECT COALESCE(MIN(authorization_revision), 0) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation > :registrationGeneration",
	)
	suspend fun minimumAuthorizationRevisionAfterRegistration(
		sourceKind: Int,
		registrationGeneration: Long,
	): Long

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = (SELECT MAX(authorization_revision) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration) " +
			"ORDER BY member_id",
	)
	suspend fun latestAuthorization(
		sourceKind: Int,
		registrationGeneration: Long,
	): List<SourceAuthorizationEntity>

	/** Exact immutable authorization envelope captured by an already-entered provider callback. */
	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = :authorizationRevision ORDER BY member_id",
	)
	suspend fun authorizationRevision(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
	): List<SourceAuthorizationEntity>

	/** Exact immutable authorization envelope with a caller-owned member bound. */
	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = :authorizationRevision ORDER BY member_id LIMIT :limit",
	)
	suspend fun authorizationRevisionBounded(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	/** Bounded immutable authorization evidence for a source-specific historical product read. */
	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation IN (:registrationGenerations) " +
			"AND authorization_revision IN (:authorizationRevisions) " +
			"ORDER BY registration_generation, authorization_revision, member_id LIMIT :limit",
	)
	suspend fun authorizationRevisionsBounded(
		sourceKind: Int,
		registrationGenerations: List<Long>,
		authorizationRevisions: List<Long>,
		limit: Int,
	): List<SourceAuthorizationEntity>

	/**
	 * The next immutable authorization boundary for one exact provider registration.
	 *
	 * Authorization revisions are allocated across a source, so a registration's timeline may be
	 * sparse when another generation receives the intervening revision. Do not infer this boundary
	 * with `authorizationRevision + 1` or from a mutable latest-registration pointer.
	 */
	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = (SELECT MIN(authorization_revision) " +
			"FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision > :authorizationRevision) ORDER BY member_id",
	)
	suspend fun nextAuthorizationRevision(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
	): List<SourceAuthorizationEntity>

	/**
	 * Next immutable authorization for this physical registration. Authorization revisions are
	 * source-global, so a registration's successor is not necessarily revision + 1.
	 */
	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = (SELECT MIN(authorization_revision) " +
			"FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision > :authorizationRevision) ORDER BY member_id LIMIT :limit",
	)
	suspend fun nextAuthorizationRevisionBounded(
		sourceKind: Int,
		registrationGeneration: Long,
		authorizationRevision: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query(
		"SELECT EXISTS(SELECT 1 FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND purpose IN ('SESSION_CAPTURE', 'AMBIENT_PRODUCT') " +
			"AND persistence_eligible = 1)",
	)
	suspend fun registrationHasCaptureAuthorizationHistory(
		sourceKind: Int,
		registrationGeneration: Long,
	): Boolean

	@Query(
		"SELECT COALESCE(MAX(authorization_revision), 0) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND purpose IN ('SESSION_CAPTURE', 'AMBIENT_PRODUCT') " +
			"AND persistence_eligible = 1",
	)
	suspend fun maximumCaptureAuthorizationRevision(
		sourceKind: Int,
		registrationGeneration: Long,
	): Long



	/** Fixed shared radio session boundary; Ambient products keep their independent lifetime. */
	@Query(
		"SELECT COALESCE(MAX(authorization_revision), 0) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND source_kind IN " +
			"(${SourceDestinationOwnerEntity.SOURCE_WIFI}, ${SourceDestinationOwnerEntity.SOURCE_CELL}) " +
			"AND registration_generation = :registrationGeneration " +
			"AND purpose = 'SESSION_CAPTURE' AND persistence_eligible = 1",
	)
	suspend fun maximumRadioSessionCaptureAuthorizationRevision(sourceKind: Int, registrationGeneration: Long): Long

	/**
	 * Reads `source_authorization` rows. Query conditions include `source_kind = :sourceKind`,
	 * `source_kind IN (${SourceDestinationOwnerEntity.SOURCE_WIFI},
	 * ${SourceDestinationOwnerEntity.SOURCE_CELL})`, `registration_generation =
	 * :registrationGeneration`. Orders by `member_id`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind AND source_kind IN " +
			"(${SourceDestinationOwnerEntity.SOURCE_WIFI}, ${SourceDestinationOwnerEntity.SOURCE_CELL}) " +
			"AND registration_generation = :registrationGeneration AND authorization_revision = " +
			"(SELECT MAX(authorization_revision) FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration) ORDER BY member_id LIMIT :limit",
	)
	suspend fun latestRadioAuthorizationBounded(sourceKind: Int, registrationGeneration: Long,
        limit: Int): List<SourceAuthorizationEntity>

	/** Published only after the runtime has drained the finite prefix before this no-CAP snapshot. */
	@Query(
		"UPDATE provider_registration_generation SET capture_callback_barrier_authorization_revision = " +
			"CASE WHEN capture_callback_barrier_authorization_revision < :throughAuthorizationRevision " +
			"THEN :throughAuthorizationRevision ELSE capture_callback_barrier_authorization_revision END " +
			"WHERE source_kind = :sourceKind AND source_kind IN " +
			"(${SourceDestinationOwnerEntity.SOURCE_WIFI}, ${SourceDestinationOwnerEntity.SOURCE_CELL}) " +
			"AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId AND status = 'ACTIVE' AND :throughAuthorizationRevision > 0 " +
			"AND :throughAuthorizationRevision = (SELECT MAX(authorization_revision) FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration) " +
			"AND NOT EXISTS(SELECT 1 FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration AND authorization_revision = :throughAuthorizationRevision " +
			"AND purpose = 'SESSION_CAPTURE' AND persistence_eligible = 1)",
	)
	suspend fun acknowledgeRadioSessionCaptureCallbackBarrier(
		sourceKind: Int, registrationGeneration: Long, sourceInstanceId: String, throughAuthorizationRevision: Long,
	): Int

	/** Location setup changes session capture while independently authorized all-day work remains. */
	@Query(
		"SELECT COALESCE(MAX(authorization_revision), 0) FROM source_authorization " +
			"WHERE source_kind = 1 AND registration_generation = :registrationGeneration " +
			"AND purpose = 'SESSION_CAPTURE' AND persistence_eligible = 1",
	)
	suspend fun maximumLocationSessionCaptureAuthorizationRevision(registrationGeneration: Long): Long

	/** The runtime has drained the finite callback prefix preceding this complete no-session snapshot. */
	@Query(
		"UPDATE provider_registration_generation SET " +
			"capture_callback_barrier_authorization_revision = CASE " +
			"WHEN capture_callback_barrier_authorization_revision < :throughAuthorizationRevision " +
			"THEN :throughAuthorizationRevision ELSE capture_callback_barrier_authorization_revision END " +
			"WHERE source_kind = 1 AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId AND status = 'ACTIVE' " +
			"AND :throughAuthorizationRevision > 0 " +
			"AND :throughAuthorizationRevision = (SELECT MAX(authorization_revision) " +
			"FROM source_authorization WHERE source_kind = 1 " +
			"AND registration_generation = :registrationGeneration) " +
			"AND NOT EXISTS(SELECT 1 FROM source_authorization WHERE source_kind = 1 " +
			"AND registration_generation = :registrationGeneration " +
			"AND authorization_revision = :throughAuthorizationRevision " +
			"AND purpose = 'SESSION_CAPTURE' AND persistence_eligible = 1)",
	)
	suspend fun acknowledgeLocationSessionCaptureCallbackBarrier(
		registrationGeneration: Long,
		sourceInstanceId: String,
		throughAuthorizationRevision: Long,
	): Int

	/**
	 * A provider owner may publish this acknowledgement only after it has fenced new callback
	 * entries and every earlier entry reached durable admission or a permanent rejection.
	 */
	@Query(
		"UPDATE provider_registration_generation SET " +
			"capture_callback_barrier_authorization_revision = CASE " +
			"WHEN capture_callback_barrier_authorization_revision < :throughAuthorizationRevision " +
			"THEN :throughAuthorizationRevision " +
			"ELSE capture_callback_barrier_authorization_revision END " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId AND status IN ('ACTIVE', 'RETIRING') " +
			"AND :throughAuthorizationRevision = (SELECT COALESCE(MAX(authorization_revision), 0) " +
			"FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND purpose IN ('SESSION_CAPTURE', 'AMBIENT_PRODUCT') " +
			"AND persistence_eligible = 1)",
	)
	suspend fun acknowledgeCaptureCallbackBarrier(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		throughAuthorizationRevision: Long,
	): Int

	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration AND effective_boot_id = :bootId " +
			"AND authorization_revision = (SELECT authorization_revision FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND effective_boot_id = :bootId AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, authorization_revision DESC LIMIT 1) " +
			"ORDER BY member_id",
	)
	suspend fun authorizationAt(
		sourceKind: Int,
		registrationGeneration: Long,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
	): List<SourceAuthorizationEntity>

	/**
	 * Reads `source_authorization` rows. Query conditions include `source_kind = :sourceKind`,
	 * `registration_generation = :registrationGeneration`, `effective_boot_id = :bootId`. Orders by
	 * `member_id`. Returns at most `limit` rows.
	 */
	@Query(
		"SELECT * FROM source_authorization WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration AND effective_boot_id = :bootId " +
			"AND authorization_revision = (SELECT authorization_revision FROM source_authorization " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND effective_boot_id = :bootId " +
			"AND effective_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"ORDER BY effective_elapsed_realtime_nanos DESC, authorization_revision DESC LIMIT 1) " +
			"ORDER BY member_id LIMIT :limit",
	)
	suspend fun authorizationAtBounded(
		sourceKind: Int,
		registrationGeneration: Long,
		bootId: String,
		observedElapsedRealtimeNanos: Long,
		limit: Int,
	): List<SourceAuthorizationEntity>

	@Query("SELECT COALESCE(MAX(authorization_revision), 0) FROM source_authorization WHERE source_kind = :sourceKind")
	suspend fun maximumAuthorizationRevision(sourceKind: Int): Long

	/**
	 * Reads `provider_registration_generation` rows. Query conditions include `source_kind =
	 * :sourceKind`, `status IN ('RESERVED', 'ACTIVE')`. Orders by `registration_generation DESC`.
	 * Returns at most 1 row. Returns null when no row matches.
	 */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND collected_data_epoch = (SELECT collected_data_epoch FROM source_evidence_state WHERE id=1) " +
			"AND status IN ('RESERVED', 'ACTIVE') ORDER BY registration_generation DESC LIMIT 1",
	)
	suspend fun currentPhysicalRegistration(sourceKind: Int): ProviderRegistrationGenerationEntity?

	/**
	 * Reads `provider_registration_generation` rows. Query conditions include `source_kind =
	 * :sourceKind`, `status IN ('ACTIVE', 'RESERVED')`. Orders by `registration_generation`.
	 */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND collected_data_epoch = (SELECT collected_data_epoch FROM source_evidence_state WHERE id=1) " +
			"AND status IN ('ACTIVE', 'RESERVED') ORDER BY registration_generation",
	)
	suspend fun currentPhysicalRegistrations(sourceKind: Int): List<ProviderRegistrationGenerationEntity>

	/** Bounded nonterminal registration audit; callers request one overflow row. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND collected_data_epoch = (SELECT collected_data_epoch FROM source_evidence_state WHERE id=1) " +
			"AND status IN ('ACTIVE', 'RESERVED') ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun currentPhysicalRegistrationsBounded(
		sourceKind: Int,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND owner_scope = :ownerScope AND status = 'RESERVED' " +
			"ORDER BY registration_generation DESC LIMIT 1",
	)
	suspend fun latestReservedRegistration(
		sourceKind: Int,
		ownerScope: String,
	): ProviderRegistrationGenerationEntity?

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status = 'RETIRING' ORDER BY registration_generation",
	)
	suspend fun pendingProviderRemovals(sourceKind: Int): List<ProviderRegistrationGenerationEntity>

	/** Bounded retiring-registration audit; callers request one overflow row. */
	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status = 'RETIRING' ORDER BY registration_generation LIMIT :limit",
	)
	suspend fun pendingProviderRemovalsBounded(
		sourceKind: Int,
		limit: Int,
	): List<ProviderRegistrationGenerationEntity>

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id = :currentProcessId AND status = 'RETIRING' " +
			"ORDER BY registration_generation",
	)
	suspend fun pendingCurrentProcessProviderRemovals(
		sourceKind: Int,
		currentProcessId: String,
	): List<ProviderRegistrationGenerationEntity>

	/** A rearmable Location handle can outlive its creating process. Always read with an overflow row. */
	@Query("SELECT * FROM provider_registration_generation WHERE source_kind = 1 " +
		"AND provider_residency = 'SYSTEM_REARMABLE' " +
		"AND status IN ('RESERVED', 'ACTIVE', 'RETIRING') " +
		"ORDER BY registration_generation LIMIT :limit")
	suspend fun nonterminalSystemLocationRegistrations(limit: Int): List<ProviderRegistrationGenerationEntity>

	/**
	 * Updates `provider_registration_generation` rows matching the supplied scope. Query conditions
	 * include `source_kind = 1`, `registration_generation = :generation`, `source_instance_id =
	 * :instanceId`, `provider_residency = 'SYSTEM_REARMABLE'`. Returns the affected row count.
	 */
	@Query("UPDATE provider_registration_generation SET status = 'RETIRING', " +
		"retired_at_ms = COALESCE(retired_at_ms, :retiredAtMs), " +
		"retired_elapsed_realtime_nanos = COALESCE(retired_elapsed_realtime_nanos, :retiredElapsedNanos), " +
		"failure_code = COALESCE(failure_code, :reason) " +
		"WHERE source_kind = 1 AND registration_generation = :generation " +
		"AND source_instance_id = :instanceId AND provider_residency = 'SYSTEM_REARMABLE' " +
		"AND status IN ('RESERVED', 'ACTIVE')")
	suspend fun markSystemLocationRetiring(
		generation: Long, instanceId: String, retiredAtMs: Long, retiredElapsedNanos: Long,
		reason: String,
	): Int

	/**
	 * Updates `provider_registration_generation` rows matching the supplied scope. Query conditions
	 * include `source_kind = 1`, `registration_generation = :generation`, `source_instance_id =
	 * :instanceId`, `provider_residency = 'SYSTEM_REARMABLE'`. Returns the affected row count.
	 */
	@Query("UPDATE provider_registration_generation SET status = 'RETIRED' " +
		"WHERE source_kind = 1 AND registration_generation = :generation " +
		"AND source_instance_id = :instanceId AND provider_residency = 'SYSTEM_REARMABLE' " +
		"AND status = 'RETIRING' AND retired_at_ms = :retiredAtMs " +
		"AND retired_elapsed_realtime_nanos = :retiredElapsedNanos")
	suspend fun completeSystemLocationRetirement(
		generation: Long, instanceId: String, retiredAtMs: Long, retiredElapsedNanos: Long,
	): Int

	/**
	 * Updates `provider_registration_generation` rows matching the supplied scope. Query conditions
	 * include `source_kind = 1`, `registration_generation = :generation`, `source_instance_id =
	 * :instanceId`, `provider_residency = 'SYSTEM_REARMABLE'`. Returns the affected row count.
	 */
	@Query("UPDATE provider_registration_generation SET status = 'FAILED', " +
		"retired_at_ms = :failedAtMs, retired_elapsed_realtime_nanos = :failedElapsedNanos, " +
		"failure_code = :reason WHERE source_kind = 1 AND registration_generation = :generation " +
		"AND source_instance_id = :instanceId AND provider_residency = 'SYSTEM_REARMABLE' " +
		"AND status = 'RESERVED'")
	suspend fun failUnacceptedSystemLocationReservation(
		generation: Long, instanceId: String, failedAtMs: Long, failedElapsedNanos: Long,
		reason: String,
	): Int

	/** A prior boot has no comparable current elapsed clock. Exact removal precedes this update. */
	@Query("UPDATE provider_registration_generation SET status = 'RETIRED', " +
		"failure_code = :reason " +
		"WHERE source_kind = 1 AND registration_generation = :generation " +
		"AND source_instance_id = :instanceId AND provider_residency = 'SYSTEM_REARMABLE' " +
		"AND clock_domain_id != :currentBootId AND status IN ('RESERVED', 'ACTIVE', 'RETIRING')")
	suspend fun completeRemovedPriorBootSystemLocation(
		generation: Long, instanceId: String, currentBootId: String, reason: String,
	): Int

	@Query(
		"SELECT EXISTS(SELECT 1 FROM provider_registration_generation " +
			"WHERE source_kind = :sourceKind AND provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id = :currentProcessId AND status = 'RETIRING')",
	)
	suspend fun hasPendingCurrentProcessProviderRemoval(
		sourceKind: Int,
		currentProcessId: String,
	): Boolean

	@Query(
		"SELECT EXISTS(SELECT 1 FROM provider_registration_generation " +
			"WHERE provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id IS NOT NULL " +
			"AND provider_process_incarnation_id != :currentProcessId " +
			"AND status IN ('RESERVED', 'ACTIVE', 'RETIRING'))",
	)
	suspend fun hasNonterminalProcessBoundRegistrationsFromAnotherIncarnation(
		currentProcessId: String,
	): Boolean

	@Query(
		"UPDATE provider_registration_generation SET status = 'FAILED', " +
			"retired_at_ms = CASE WHEN clock_domain_id = :currentBootId " +
			"THEN COALESCE(retired_at_ms, :reconciledAtMs) ELSE retired_at_ms END, " +
			"retired_elapsed_realtime_nanos = CASE WHEN clock_domain_id = :currentBootId " +
			"THEN COALESCE(retired_elapsed_realtime_nanos, :reconciledElapsedRealtimeNanos) " +
			"ELSE retired_elapsed_realtime_nanos END, failure_code = COALESCE(failure_code, :reason) " +
			"WHERE provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id IS NOT NULL " +
			"AND provider_process_incarnation_id != :currentProcessId AND status = 'RESERVED'",
	)
	suspend fun failPriorProcessReservations(
		currentProcessId: String,
		currentBootId: String,
		reconciledAtMs: Long,
		reconciledElapsedRealtimeNanos: Long,
		reason: String,
	): Int

	@Query(
		"UPDATE provider_registration_generation SET status = 'RETIRED', " +
			"retired_at_ms = CASE WHEN clock_domain_id = :currentBootId " +
			"THEN COALESCE(retired_at_ms, :reconciledAtMs) ELSE retired_at_ms END, " +
			"retired_elapsed_realtime_nanos = CASE WHEN clock_domain_id = :currentBootId " +
			"THEN COALESCE(retired_elapsed_realtime_nanos, :reconciledElapsedRealtimeNanos) " +
			"ELSE retired_elapsed_realtime_nanos END, failure_code = COALESCE(failure_code, :reason) " +
			"WHERE provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id IS NOT NULL " +
			"AND provider_process_incarnation_id != :currentProcessId AND status = 'ACTIVE'",
	)
	suspend fun retirePriorProcessActiveRegistrations(
		currentProcessId: String,
		currentBootId: String,
		reconciledAtMs: Long,
		reconciledElapsedRealtimeNanos: Long,
		reason: String,
	): Int

	@Query(
		"UPDATE provider_registration_generation SET status = 'RETIRED', " +
			"failure_code = COALESCE(failure_code, :reason) " +
			"WHERE provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id IS NOT NULL " +
			"AND provider_process_incarnation_id != :currentProcessId AND status = 'RETIRING'",
	)
	suspend fun completePriorProcessRetirements(
		currentProcessId: String,
		reason: String,
	): Int

	/**
	 * Finalizes registrations whose process-bound provider cannot still exist.
	 *
	 * Elapsed realtime is only a valid retirement boundary inside its originating boot. Prior-boot
	 * rows therefore become terminal without borrowing the current boot's elapsed timestamp. A
	 * RETIRING row already owns its authoritative boundary, which this operation never rewrites.
	 */
	@Transaction
	suspend fun reconcilePriorProcessRegistrations(
		currentProcessId: String,
		currentBootId: String,
		reconciledAtMs: Long,
		reconciledElapsedRealtimeNanos: Long,
		reason: String,
	): PriorProcessRegistrationReconciliationResult {
		require(currentProcessId.isNotBlank())
		require(currentBootId.isNotBlank())
		require(reconciledAtMs >= 0L)
		require(reconciledElapsedRealtimeNanos >= 0L)
		require(reason.isNotBlank())
		return PriorProcessRegistrationReconciliationResult(
			failedReservations = failPriorProcessReservations(
				currentProcessId = currentProcessId,
				currentBootId = currentBootId,
				reconciledAtMs = reconciledAtMs,
				reconciledElapsedRealtimeNanos = reconciledElapsedRealtimeNanos,
				reason = reason,
			),
			retiredActiveRegistrations = retirePriorProcessActiveRegistrations(
				currentProcessId = currentProcessId,
				currentBootId = currentBootId,
				reconciledAtMs = reconciledAtMs,
				reconciledElapsedRealtimeNanos = reconciledElapsedRealtimeNanos,
				reason = reason,
			),
			completedRetirements = completePriorProcessRetirements(
				currentProcessId = currentProcessId,
				reason = reason,
			),
		)
	}

	@Query(
		"SELECT * FROM source_registration_state WHERE source_kind = :sourceKind AND owner_scope = :ownerScope",
	)
	suspend fun registrationState(sourceKind: Int, ownerScope: String): SourceRegistrationStateEntity?

	@Insert(onConflict = OnConflictStrategy.REPLACE)
	suspend fun replaceRegistrationState(entity: SourceRegistrationStateEntity)

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId AND clock_domain_id = :bootId " +
			"AND physical_configuration_fingerprint = :physicalConfigurationFingerprint " +
			"AND accepted_elapsed_realtime_nanos IS NOT NULL " +
			"AND accepted_elapsed_realtime_nanos <= :observedElapsedRealtimeNanos " +
			"AND (retired_elapsed_realtime_nanos IS NULL " +
			"OR :observedElapsedRealtimeNanos < retired_elapsed_realtime_nanos) LIMIT 1",
	)
	suspend fun registrationAtObservedTime(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		bootId: String,
		physicalConfigurationFingerprint: String,
		observedElapsedRealtimeNanos: Long,
	): ProviderRegistrationGenerationEntity?

	@Query(
		"UPDATE provider_registration_generation SET status = 'ACTIVE', accepted_at_ms = :acceptedAtMs, " +
			"accepted_elapsed_realtime_nanos = :acceptedElapsedRealtimeNanos, " +
			"failure_code = NULL WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId AND status = 'RESERVED'",
	)
	suspend fun markRegistrationActive(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		acceptedAtMs: Long,
		acceptedElapsedRealtimeNanos: Long,
	): Int

	@Query(
		"UPDATE provider_registration_generation SET status = 'FAILED', " +
			"retired_at_ms = :failedAtMs, " +
			"retired_elapsed_realtime_nanos = :failedElapsedRealtimeNanos, " +
			"failure_code = :failureCode " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId " +
			"AND provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id = :currentProcessId AND status = 'RESERVED'",
	)
	suspend fun failUnacceptedCurrentProcessReservation(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		currentProcessId: String,
		failedAtMs: Long,
		failedElapsedRealtimeNanos: Long,
		failureCode: String,
	): Int

	@Query(
		"UPDATE provider_registration_generation SET status = 'RETIRING', " +
			"retired_at_ms = COALESCE(retired_at_ms, :retiredAtMs), " +
			"retired_elapsed_realtime_nanos = " +
			"COALESCE(retired_elapsed_realtime_nanos, :retiredElapsedRealtimeNanos), " +
			"failure_code = COALESCE(failure_code, :reason) " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId " +
			"AND provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id = :currentProcessId " +
			"AND status IN ('RESERVED', 'ACTIVE')",
	)
	suspend fun markCurrentProcessRegistrationRetiring(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		currentProcessId: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
		reason: String,
	): Int

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId " +
			"AND provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id = :currentProcessId AND status = 'RETIRING' " +
			"LIMIT 1",
	)
	suspend fun currentProcessRetiringRegistration(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		currentProcessId: String,
	): ProviderRegistrationGenerationEntity?

	/**
	 * Opens one exact provider-removal interval and returns its immutable durable boundary.
	 * Repeating the call for an already-RETIRING identity returns the same row without moving it.
	 */
	@Transaction
	suspend fun beginCurrentProcessRegistrationRetirement(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		currentProcessId: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
		reason: String,
	): ProviderRegistrationGenerationEntity? {
		require(sourceKind >= 0)
		require(registrationGeneration > 0L)
		require(sourceInstanceId.isNotBlank())
		require(currentProcessId.isNotBlank())
		require(retiredAtMs >= 0L)
		require(retiredElapsedRealtimeNanos >= 0L)
		require(reason.isNotBlank())
		markCurrentProcessRegistrationRetiring(
			sourceKind = sourceKind,
			registrationGeneration = registrationGeneration,
			sourceInstanceId = sourceInstanceId,
			currentProcessId = currentProcessId,
			retiredAtMs = retiredAtMs,
			retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
			reason = reason,
		)
		return currentProcessRetiringRegistration(
			sourceKind = sourceKind,
			registrationGeneration = registrationGeneration,
			sourceInstanceId = sourceInstanceId,
			currentProcessId = currentProcessId,
		)
	}

	@Query(
		"UPDATE provider_registration_generation SET status = 'RETIRED' " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId " +
			"AND provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id = :currentProcessId AND status = 'RETIRING' " +
			"AND retired_at_ms = :retiredAtMs " +
			"AND retired_elapsed_realtime_nanos = :retiredElapsedRealtimeNanos",
	)
	suspend fun completeCurrentProcessRegistrationRetirement(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		currentProcessId: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
	): Int

	@Query(
		"SELECT EXISTS(SELECT 1 FROM provider_registration_generation " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId " +
			"AND provider_residency = 'PROCESS_BOUND' " +
			"AND provider_process_incarnation_id = :currentProcessId AND status = 'RETIRED' " +
			"AND retired_at_ms = :retiredAtMs " +
			"AND retired_elapsed_realtime_nanos = :retiredElapsedRealtimeNanos)",
	)
	suspend fun isCurrentProcessRegistrationRetirementComplete(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		currentProcessId: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
	): Boolean

	@Query(
		"UPDATE provider_registration_generation SET status = 'RETIRING', retired_at_ms = :retiredAtMs, " +
			"retired_elapsed_realtime_nanos = :retiredElapsedRealtimeNanos, failure_code = :reason " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId AND status = 'ACTIVE'",
	)
	suspend fun markRegistrationRetiring(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
		reason: String?,
	): Int

	@Query(
		"UPDATE provider_registration_generation SET status = 'RETIRED' " +
			"WHERE source_kind = :sourceKind AND registration_generation = :registrationGeneration " +
			"AND source_instance_id = :sourceInstanceId AND status = 'RETIRING'",
	)
	suspend fun completeRegistrationRetirement(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
	): Int

	/**
	 * Makes a provider-accepted reservation authoritative at one half-open elapsed-time boundary.
	 * The prior accepted generation remains authoritative until this transaction commits and remains
	 * durably discoverable as RETIRING until its provider-specific removal is acknowledged.
	 */
	@Transaction
	suspend fun acceptReservedReplacement(
		reservedState: SourceRegistrationStateEntity,
		expectedPointerGeneration: Long?,
		expectedPointerInstanceId: String?,
		requiredAuthorizationFingerprint: String?,
		acceptedAtMs: Long,
		acceptedElapsedRealtimeNanos: Long,
	): ProviderRegistrationGenerationEntity? {
		check((expectedPointerGeneration == null) == (expectedPointerInstanceId == null))
		val reservation = requireNotNull(
			registration(reservedState.sourceKind, reservedState.registrationGeneration),
		) { "Provider registration reservation is missing" }
		check(reservation.status == ProviderRegistrationGenerationEntity.STATUS_RESERVED) {
			"Provider registration reservation is stale"
		}
		check(reservation.sourceInstanceId == reservedState.sourceInstanceId)
		check(reservation.ownerScope == reservedState.ownerScope)
		check(acceptedElapsedRealtimeNanos >= reservation.reservedElapsedRealtimeNanos)

		val pointer = registrationState(reservedState.sourceKind, reservedState.ownerScope)
		if (pointer?.registrationGeneration == reservedState.registrationGeneration &&
			pointer.sourceInstanceId == reservedState.sourceInstanceId
		) {
			// Compatibility with a pre-fix local v28 reservation that was prematurely made current.
			check(expectedPointerGeneration == pointer.registrationGeneration)
			check(expectedPointerInstanceId == pointer.sourceInstanceId)
		} else {
			check(pointer?.registrationGeneration == expectedPointerGeneration)
			check(pointer?.sourceInstanceId == expectedPointerInstanceId)
		}

		val demands = SourceProviderPurposeScope.selectDemands(
			reservedState.sourceKind,
			reservation.ownerScope,
			authorizationDemands(reservedState.sourceKind),
		)
		check(demands.isNotEmpty()) { "Provider registration no longer has an active demand" }
		val currentFingerprint = SourceBrokerAuthorization.fingerprint(demands)
		val authorization = requireNotNull(
			latestAuthorization(reservedState.sourceKind, reservedState.registrationGeneration)
				.toAuthorizationSnapshotOrNull(),
		) { "Provider registration authorization is missing" }
		check(authorization.authorizationFingerprint == currentFingerprint) {
			"Provider registration authorization is stale"
		}
		if (requiredAuthorizationFingerprint != null) {
			check(authorization.authorizationFingerprint == requiredAuthorizationFingerprint) {
				"Provider registration authorization changed during provider acceptance"
			}
		}

		check(
			markRegistrationActive(
				reservedState.sourceKind,
				reservedState.registrationGeneration,
				reservedState.sourceInstanceId,
				acceptedAtMs,
				acceptedElapsedRealtimeNanos,
			) == 1,
		) { "Provider registration reservation is stale" }

		val previous = pointer
			?.takeUnless { it.registrationGeneration == reservedState.registrationGeneration }
			?.let { state -> registration(state.sourceKind, state.registrationGeneration) }
		if (previous != null) {
			check(previous.sourceInstanceId == pointer.sourceInstanceId)
			check(previous.status != ProviderRegistrationGenerationEntity.STATUS_RESERVED) {
				"A different unaccepted reservation cannot be superseded"
			}
		}
		if (previous?.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE) {
			check(
				markRegistrationRetiring(
					previous.sourceKind,
					previous.registrationGeneration,
					previous.sourceInstanceId,
					acceptedAtMs,
					acceptedElapsedRealtimeNanos,
					"SUPERSEDED_BY_NEW_GENERATION",
				) == 1,
			) { "Prior provider registration changed during replacement" }
		}

		val nextSequence = if (
			pointer != null &&
			pointer.sourceInstanceId == reservedState.sourceInstanceId &&
			pointer.clockDomainId == reservedState.clockDomainId &&
			pointer.collectedDataEpoch == reservedState.collectedDataEpoch
		) pointer.nextSequence else reservedState.nextSequence
		replaceRegistrationState(reservedState.copy(nextSequence = nextSequence, updatedAtMs = acceptedAtMs))
		return previous?.takeIf { it.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE }
	}

	@Query(
		"UPDATE provider_registration_generation SET status = :status, retired_at_ms = :retiredAtMs, " +
			"retired_elapsed_realtime_nanos = :retiredElapsedRealtimeNanos, " +
			"failure_code = :failureCode WHERE source_kind = :sourceKind " +
			"AND registration_generation = :registrationGeneration AND source_instance_id = :sourceInstanceId " +
			"AND status IN ('RESERVED', 'ACTIVE')",
	)
	suspend fun finishRegistration(
		sourceKind: Int,
		registrationGeneration: Long,
		sourceInstanceId: String,
		status: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long,
		failureCode: String?,
	): Int

	@Query("DELETE FROM source_authorization")
	fun deleteAllAuthorizations()

	@Query("DELETE FROM provider_registration_generation")
	fun deleteAllRegistrations()

	@Query("DELETE FROM source_demand")
	fun deleteAllDemands()
}
