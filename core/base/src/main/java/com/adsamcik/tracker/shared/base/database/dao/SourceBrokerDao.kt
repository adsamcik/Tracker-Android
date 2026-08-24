package com.adsamcik.tracker.shared.base.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull

@Dao
interface SourceBrokerDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertDemands(entities: List<SourceDemandEntity>): List<Long>

	@Query(
		// RETIRING remains registration-eligible until the source callback barrier is drained.
		"SELECT * FROM source_demand WHERE source_kind = :sourceKind " +
			"AND status IN ('ACTIVE', 'RETIRING') " +
			"ORDER BY purpose, consumer_id, demand_id",
	)
	suspend fun activeDemands(sourceKind: Int): List<SourceDemandEntity>

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

	@Query(
		"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = :bootId, " +
			"retire_elapsed_realtime_nanos = :elapsedRealtimeNanos, retired_at_ms = :wallTimeMs " +
			"WHERE consumer_id = :consumerId AND status = 'ACTIVE'",
	)
	suspend fun markConsumerRetiring(
		consumerId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int

	@Query(
		"UPDATE source_demand SET status = 'RETIRING', retire_boot_id = :bootId, " +
			"retire_elapsed_realtime_nanos = :elapsedRealtimeNanos, retired_at_ms = :wallTimeMs " +
			"WHERE source_kind = :sourceKind AND purpose IN (:purposes) AND status = 'ACTIVE'",
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

	@Query("SELECT COALESCE(MAX(authorization_revision), 0) FROM source_authorization WHERE source_kind = :sourceKind")
	suspend fun maximumAuthorizationRevision(sourceKind: Int): Long

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status IN ('RESERVED', 'ACTIVE') ORDER BY registration_generation DESC LIMIT 1",
	)
	suspend fun currentPhysicalRegistration(sourceKind: Int): ProviderRegistrationGenerationEntity?

	@Query(
		"SELECT * FROM provider_registration_generation WHERE source_kind = :sourceKind " +
			"AND status IN ('ACTIVE', 'RESERVED') ORDER BY registration_generation",
	)
	suspend fun currentPhysicalRegistrations(sourceKind: Int): List<ProviderRegistrationGenerationEntity>

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

		val demands = authorizationDemands(reservedState.sourceKind)
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
