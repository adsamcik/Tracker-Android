package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.PriorProcessRegistrationReconciliationResult
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

typealias BootClockDomainProvider =
	com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
typealias AndroidBootClockDomainProvider =
	com.adsamcik.tracker.shared.base.time.AndroidBootClockDomainProvider

data class SourceRegistration(
	val ownerScope: String,
	val state: SourceRegistrationStateEntity,
	val physicalConfigurationFingerprint: String,
	val authorization: SourceAuthorizationSnapshot,
	val requiresProviderAcceptance: Boolean,
	val predecessorState: SourceRegistrationStateEntity? = null,
) {
	val purposeEligibilityMask: Long get() = authorization.purposeEligibilityMask
	val eligibilityFingerprint: String get() = authorization.authorizationFingerprint
}

/** Exact durable authority for one process-bound provider removal attempt. */
internal data class SourceRegistrationRetirementToken(
	val source: SourceKind,
	val sourceInstanceId: SourceInstanceId,
	val registrationGeneration: Long,
	val processIncarnationId: String,
	val retiredAtMs: Long,
	val retiredElapsedRealtimeNanos: Long,
	val reason: String?,
)

/**
 * Allocates durable source identities and monotonically increasing source sequences.
 *
 * The broker/source pair is the owner scope, so compatible control, capture, and ambient demands
 * share one sequence space. A fresh immutable physical generation is allocated only when the
 * normalized provider configuration, boot domain, or collected-data epoch changes. Authority-only
 * changes append an observed-time authorization revision without restarting compatible provider
 * work.
 */
@Singleton
class SourceRegistrationRepository @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val clockDomainProvider: BootClockDomainProvider,
	private val processIncarnationIdProvider: ProcessIncarnationIdProvider,
) {
	suspend fun reconcilePriorProcessRegistrations(
		reconciledAtMs: Long = System.currentTimeMillis(),
		reconciledElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): PriorProcessRegistrationReconciliationResult =
		database.sourceBrokerDao().reconcilePriorProcessRegistrations(
			currentProcessId = processIncarnationIdProvider.current(),
			currentBootId = clockDomainProvider.current(),
			reconciledAtMs = reconciledAtMs,
			reconciledElapsedRealtimeNanos = reconciledElapsedRealtimeNanos,
			reason = "PRIOR_PROCESS_INCARNATION_ENDED",
		)

	suspend fun begin(
		source: SourceKind,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): SourceRegistration {
		require(source != SourceKind.ACTIVITY) {
			"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
		}
		require(physicalConfigurationFingerprint.isNotBlank())
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = clockDomainProvider.current()
		val processIncarnationId = processIncarnationIdProvider.current()
		return database.withTransaction {
			val brokerDao = database.sourceBrokerDao()
			check(
				!brokerDao.hasPendingCurrentProcessProviderRemoval(
					sourceKind = source.stableCode,
					currentProcessId = processIncarnationId,
				),
			) { "Pending provider removal must complete before a replacement can be reserved" }
			val demands = brokerDao.authorizationDemands(source.stableCode)
			require(demands.isNotEmpty()) {
				"A source registration requires at least one durable active broker demand"
			}
			val ownerScope = "source-broker:${source.stableCode}"
			val purposeEligibilityMask = SourceBrokerAuthorization.purposeMask(demands)
			require(purposeEligibilityMask > 0L) { "Broker demand has no recognized purpose eligibility" }
			val dao = database.sourceRegistrationStateDao()
			val current = dao.get(source.stableCode, ownerScope)
			val currentPhysical = current?.let { state ->
				brokerDao.registration(state.sourceKind, state.registrationGeneration)
			}
			if (current != null && currentPhysical != null &&
				current.clockDomainId == clockDomainId &&
				current.collectedDataEpoch == lifecycle.epoch &&
				currentPhysical.providerResidency ==
					ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
				currentPhysical.providerProcessIncarnationId == processIncarnationId &&
				currentPhysical.status in setOf(
					ProviderRegistrationGenerationEntity.STATUS_RESERVED,
					ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				) &&
				currentPhysical.physicalConfigurationFingerprint == physicalConfigurationFingerprint
			) {
				val reused = current.copy(appliedRevision = appliedRevision, updatedAtMs = updatedAtMs)
				dao.replace(reused)
				val authorization = appendAuthorizationIfChanged(
					source = source,
					registrationGeneration = current.registrationGeneration,
					demands = demands,
					bootId = clockDomainId,
					elapsedRealtimeNanos = updatedElapsedRealtimeNanos,
					wallTimeMs = updatedAtMs,
				)
				return@withTransaction SourceRegistration(
					ownerScope = ownerScope,
					state = reused,
					physicalConfigurationFingerprint = physicalConfigurationFingerprint,
					authorization = authorization,
					requiresProviderAcceptance = currentPhysical.status ==
						ProviderRegistrationGenerationEntity.STATUS_RESERVED,
					predecessorState = current,
				)
			}
			val registrationGeneration = brokerDao.maximumRegistrationGeneration(source.stableCode) + 1L
			val next = when {
				current == null -> SourceRegistrationStateEntity(
					sourceKind = source.stableCode,
					ownerScope = ownerScope,
					sourceInstanceId = UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = registrationGeneration,
					nextSequence = 0L,
					appliedRevision = appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = updatedAtMs,
				)
				current.clockDomainId == clockDomainId && current.collectedDataEpoch == lifecycle.epoch ->
					current.copy(
						registrationGeneration = registrationGeneration,
						appliedRevision = appliedRevision,
						updatedAtMs = updatedAtMs,
					)
				else -> current.copy(
					sourceInstanceId = UUID.randomUUID().toString(),
					clockDomainId = clockDomainId,
					registrationGeneration = registrationGeneration,
					nextSequence = 0L,
					appliedRevision = appliedRevision,
					collectedDataEpoch = lifecycle.epoch,
					updatedAtMs = updatedAtMs,
				)
			}
			brokerDao.insertRegistration(
				ProviderRegistrationGenerationEntity(
					sourceKind = source.stableCode,
					registrationGeneration = next.registrationGeneration,
					sourceInstanceId = next.sourceInstanceId,
					ownerScope = ownerScope,
					clockDomainId = next.clockDomainId,
					physicalConfigurationFingerprint = physicalConfigurationFingerprint,
					collectedDataEpoch = lifecycle.epoch,
					providerResidency = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
					providerProcessIncarnationId = processIncarnationId,
					status = ProviderRegistrationGenerationEntity.STATUS_RESERVED,
					reservedAtMs = updatedAtMs,
					reservedElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
					acceptedAtMs = null,
					acceptedElapsedRealtimeNanos = null,
					retiredAtMs = null,
					retiredElapsedRealtimeNanos = null,
					failureCode = null,
				),
			)
			val authorization = appendAuthorizationIfChanged(
				source = source,
				registrationGeneration = next.registrationGeneration,
				demands = demands,
				bootId = clockDomainId,
				elapsedRealtimeNanos = updatedElapsedRealtimeNanos,
				wallTimeMs = updatedAtMs,
			)
			SourceRegistration(
				ownerScope,
				next,
				physicalConfigurationFingerprint,
				authorization,
				requiresProviderAcceptance = true,
				predecessorState = current,
			)
		}
	}

	/**
	 * Refreshes authorization metadata only when the caller's already-running provider still owns
	 * the active physical generation. An incompatible boot, deletion epoch, configuration, or
	 * provider state returns null without reserving a replacement generation.
	 */
	suspend fun refreshActiveAuthorization(
		source: SourceKind,
		expectedRegistration: SourceRegistration,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): SourceRegistration? {
		require(source != SourceKind.ACTIVITY) {
			"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
		}
		require(physicalConfigurationFingerprint.isNotBlank())
		require(expectedRegistration.state.sourceKind == source.stableCode)
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = clockDomainProvider.current()
		val processIncarnationId = processIncarnationIdProvider.current()
		return database.withTransaction {
			val brokerDao = database.sourceBrokerDao()
			val demands = brokerDao.authorizationDemands(source.stableCode)
			if (demands.isEmpty()) return@withTransaction null
			val purposeEligibilityMask = SourceBrokerAuthorization.purposeMask(demands)
			if (purposeEligibilityMask <= 0L) return@withTransaction null
			val ownerScope = expectedRegistration.ownerScope
			val dao = database.sourceRegistrationStateDao()
			val current = dao.get(source.stableCode, ownerScope) ?: return@withTransaction null
			val currentPhysical = brokerDao.registration(
				current.sourceKind,
				current.registrationGeneration,
			) ?: return@withTransaction null
			if (current.sourceInstanceId != expectedRegistration.state.sourceInstanceId ||
				current.registrationGeneration != expectedRegistration.state.registrationGeneration ||
				current.clockDomainId != expectedRegistration.state.clockDomainId ||
				current.collectedDataEpoch != expectedRegistration.state.collectedDataEpoch ||
				current.clockDomainId != clockDomainId ||
				current.collectedDataEpoch != lifecycle.epoch ||
				currentPhysical.providerResidency !=
					ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
				currentPhysical.providerProcessIncarnationId != processIncarnationId ||
				currentPhysical.status != ProviderRegistrationGenerationEntity.STATUS_ACTIVE ||
				expectedRegistration.physicalConfigurationFingerprint !=
					physicalConfigurationFingerprint ||
				currentPhysical.physicalConfigurationFingerprint != physicalConfigurationFingerprint
			) return@withTransaction null

			val refreshed = current.copy(
				appliedRevision = appliedRevision,
				updatedAtMs = updatedAtMs,
			)
			dao.replace(refreshed)
			val authorization = appendAuthorizationIfChanged(
				source = source,
				registrationGeneration = current.registrationGeneration,
				demands = demands,
				bootId = clockDomainId,
				elapsedRealtimeNanos = updatedElapsedRealtimeNanos,
				wallTimeMs = updatedAtMs,
			)
			SourceRegistration(
				ownerScope = ownerScope,
				state = refreshed,
				physicalConfigurationFingerprint = physicalConfigurationFingerprint,
				authorization = authorization,
				requiresProviderAcceptance = false,
				predecessorState = current,
			)
		}
	}

	suspend fun markAccepted(
		registration: SourceRegistration,
		acceptedAtMs: Long,
		acceptedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): ProviderRegistrationGenerationEntity? {
		if (!registration.requiresProviderAcceptance) return null
		return database.sourceBrokerDao().acceptReservedReplacement(
			reservedState = registration.state,
			expectedPointerGeneration = registration.predecessorState?.registrationGeneration,
			expectedPointerInstanceId = registration.predecessorState?.sourceInstanceId,
			requiredAuthorizationFingerprint = registration.authorization.authorizationFingerprint,
			acceptedAtMs = acceptedAtMs,
			acceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
		)
	}

	internal suspend fun failUnacceptedReservation(
		registration: SourceRegistration,
		failureCode: String,
		failedAtMs: Long,
		failedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): Boolean {
		require(registration.state.sourceKind != SourceKind.ACTIVITY.stableCode) {
			"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
		}
		require(failureCode.isNotBlank())
		require(failedAtMs >= 0L)
		require(failedElapsedRealtimeNanos >= 0L)
		return database.sourceBrokerDao().failUnacceptedCurrentProcessReservation(
			sourceKind = registration.state.sourceKind,
			registrationGeneration = registration.state.registrationGeneration,
			sourceInstanceId = registration.state.sourceInstanceId,
			currentProcessId = processIncarnationIdProvider.current(),
			failedAtMs = failedAtMs,
			failedElapsedRealtimeNanos = failedElapsedRealtimeNanos,
			failureCode = failureCode,
		) == 1
	}

	/**
	 * Persists the callback-admission cutoff before provider removal. Repeating this operation for
	 * the same registration returns the first token unchanged.
	 */
	internal suspend fun beginRetirement(
		registration: SourceRegistration,
		reason: String,
		retiredAtMs: Long,
		retiredElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): SourceRegistrationRetirementToken {
		require(registration.state.sourceKind != SourceKind.ACTIVITY.stableCode) {
			"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
		}
		require(reason.isNotBlank())
		require(retiredAtMs >= 0L)
		require(retiredElapsedRealtimeNanos >= 0L)
		val processIncarnationId = processIncarnationIdProvider.current()
		val retiring = checkNotNull(
			database.sourceBrokerDao().beginCurrentProcessRegistrationRetirement(
				sourceKind = registration.state.sourceKind,
				registrationGeneration = registration.state.registrationGeneration,
				sourceInstanceId = registration.state.sourceInstanceId,
				currentProcessId = processIncarnationId,
				retiredAtMs = retiredAtMs,
				retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
				reason = reason,
			),
		) { "Provider registration is not current-process retirement eligible" }
		return retiring.toRetirementToken()
	}

	internal suspend fun completeRetirement(token: SourceRegistrationRetirementToken): Boolean {
		val currentProcessId = processIncarnationIdProvider.current()
		if (token.processIncarnationId != currentProcessId) return false
		val dao = database.sourceBrokerDao()
		val completed = dao.completeCurrentProcessRegistrationRetirement(
			sourceKind = token.source.stableCode,
			registrationGeneration = token.registrationGeneration,
			sourceInstanceId = token.sourceInstanceId.value,
			currentProcessId = currentProcessId,
			retiredAtMs = token.retiredAtMs,
			retiredElapsedRealtimeNanos = token.retiredElapsedRealtimeNanos,
		) == 1
		return completed || dao.isCurrentProcessRegistrationRetirementComplete(
			sourceKind = token.source.stableCode,
			registrationGeneration = token.registrationGeneration,
			sourceInstanceId = token.sourceInstanceId.value,
			currentProcessId = currentProcessId,
			retiredAtMs = token.retiredAtMs,
			retiredElapsedRealtimeNanos = token.retiredElapsedRealtimeNanos,
		)
	}

	internal suspend fun pendingRetirements(
		source: SourceKind,
	): List<SourceRegistrationRetirementToken> {
		require(source != SourceKind.ACTIVITY) {
			"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
		}
		return database.sourceBrokerDao().pendingCurrentProcessProviderRemovals(
			sourceKind = source.stableCode,
			currentProcessId = processIncarnationIdProvider.current(),
		).map { it.toRetirementToken() }
	}

	suspend fun markFailed(
		registration: SourceRegistration,
		failureCode: String,
		failedAtMs: Long,
		failedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	) {
		database.sourceBrokerDao().finishRegistration(
			registration.state.sourceKind,
			registration.state.registrationGeneration,
			registration.state.sourceInstanceId,
			ProviderRegistrationGenerationEntity.STATUS_FAILED,
			failedAtMs,
			failedElapsedRealtimeNanos,
			failureCode,
		)
	}

	suspend fun markRetired(
		registration: SourceRegistration,
		retiredAtMs: Long,
		reason: String? = null,
		retiredElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	) {
		database.sourceBrokerDao().finishRegistration(
			registration.state.sourceKind,
			registration.state.registrationGeneration,
			registration.state.sourceInstanceId,
			ProviderRegistrationGenerationEntity.STATUS_RETIRED,
			retiredAtMs,
			retiredElapsedRealtimeNanos,
			reason,
		)
	}

	private fun ProviderRegistrationGenerationEntity.toRetirementToken():
		SourceRegistrationRetirementToken {
		check(providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND)
		check(status == ProviderRegistrationGenerationEntity.STATUS_RETIRING)
		return SourceRegistrationRetirementToken(
			source = SourceKind.entries.single { it.stableCode == sourceKind },
			sourceInstanceId = SourceInstanceId(sourceInstanceId),
			registrationGeneration = registrationGeneration,
			processIncarnationId = requireNotNull(providerProcessIncarnationId),
			retiredAtMs = requireNotNull(retiredAtMs),
			retiredElapsedRealtimeNanos = requireNotNull(retiredElapsedRealtimeNanos),
			reason = failureCode,
		)
	}

	private suspend fun appendAuthorizationIfChanged(
		source: SourceKind,
		registrationGeneration: Long,
		demands: List<com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): SourceAuthorizationSnapshot {
		val dao = database.sourceBrokerDao()
		val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
		val latest = dao.latestAuthorization(source.stableCode, registrationGeneration)
			.toAuthorizationSnapshotOrNull()
		if (latest?.authorizationFingerprint == fingerprint) return latest
		val revision = dao.maximumAuthorizationRevision(source.stableCode) + 1L
		val rows = SourceBrokerAuthorization.rows(
			source.stableCode,
			registrationGeneration,
			revision,
			demands,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		dao.insertAuthorizations(rows)
		return requireNotNull(rows.toAuthorizationSnapshotOrNull())
	}

	suspend fun allocateSequence(registration: SourceRegistration, updatedAtMs: Long): Long =
		database.sourceRegistrationStateDao().allocateSequence(
			registration.state.sourceKind,
			registration.ownerScope,
			updatedAtMs,
		).also { allocated ->
			check(allocated.sourceInstanceId == registration.state.sourceInstanceId) {
				"Source registration changed while a callback was being admitted"
			}
			check(allocated.registrationGeneration == registration.state.registrationGeneration) {
				"Source generation changed while a callback was being admitted"
			}
		}.nextSequence

	suspend fun loadRuntimeState(registration: SourceRegistration): SourceRuntimeStateEntity? =
		database.sourceRuntimeStateDao()
			.get(registration.state.sourceKind, registration.ownerScope)
			?.takeIf { state ->
				state.sourceInstanceId == registration.state.sourceInstanceId &&
					state.clockDomainId == registration.state.clockDomainId
			}

	suspend fun saveRuntimeState(
		registration: SourceRegistration,
		lastProviderSequence: Long,
		lastAdmittedSourceSequence: Long?,
		lastAdmissionOrdinal: Long?,
		stateVersion: Int,
		payload: ByteArray,
		updatedAtMs: Long,
	) {
		database.sourceRuntimeStateDao().save(
			SourceRuntimeStateEntity(
				sourceKind = registration.state.sourceKind,
				ownerScope = registration.ownerScope,
				sourceInstanceId = registration.state.sourceInstanceId,
				clockDomainId = registration.state.clockDomainId,
				registrationGeneration = registration.state.registrationGeneration,
				lastProviderSequence = lastProviderSequence,
				lastAdmittedSourceSequence = lastAdmittedSourceSequence,
				lastAdmissionOrdinal = lastAdmissionOrdinal,
				stateVersion = stateVersion,
				payload = payload,
				updatedAtMs = updatedAtMs,
			),
		)
	}
}
