package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainRetirementEvidence
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
import com.adsamcik.tracker.shared.base.database.withMonotonicStepsCountDomainRevision
import com.adsamcik.tracker.shared.base.database.dao.PriorProcessRegistrationReconciliationResult
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRegistrationStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.process.ProcessIncarnationIdProvider
import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
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
	val providerAcceptedElapsedRealtimeNanos: Long? = null,
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

internal enum class CellCaptureCallbackBarrierBlockedReason {
	STALE_LIFECYCLE,
	STALE_REGISTRATION,
	CAPTURE_AUTHORIZATION_ACTIVE,
	AUTHORIZATION_UNVERIFIABLE,
}

internal sealed interface CellCaptureCallbackBarrierPublication {
	data class Established(
		val throughAuthorizationRevision: Long,
	) : CellCaptureCallbackBarrierPublication

	data class Blocked(
		val reason: CellCaptureCallbackBarrierBlockedReason,
	) : CellCaptureCallbackBarrierPublication
}

internal enum class WifiCaptureCallbackBarrierBlockedReason {
	STALE_LIFECYCLE,
	STALE_REGISTRATION,
	CAPTURE_AUTHORIZATION_ACTIVE,
	AUTHORIZATION_UNVERIFIABLE,
}

internal sealed interface WifiCaptureCallbackBarrierPublication {
	data class Established(
		val throughAuthorizationRevision: Long,
	) : WifiCaptureCallbackBarrierPublication

	data class Blocked(
		val reason: WifiCaptureCallbackBarrierBlockedReason,
	) : WifiCaptureCallbackBarrierPublication
}

/**
 * Allocates durable source identities and monotonically increasing source sequences.
 *
 * A shared broker/source owner coalesces compatible demands. A purpose-scoped owner is used only
 * when a provider physically cannot satisfy another purpose, such as the direct Step Counter versus
 * Ambient Steps continuity. Each owner retains its own sequence space. A fresh immutable physical
 * generation is allocated only when provider configuration, boot domain, or collected-data epoch
 * changes. Authority-only changes append an observed-time authorization revision without restarting
 * compatible provider work.
 */
@Singleton
class SourceRegistrationRepository @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val clockDomainProvider: BootClockDomainProvider,
	private val processIncarnationIdProvider: ProcessIncarnationIdProvider,
	private val trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	private val sourceBroker: SourceBroker = SourceBroker(database),
) {
	internal suspend fun verifySourceEraseProviderSettled(
		source: SourceKind,
		expectedCollectedDataEpoch: Long,
		expectedRegistrationGeneration: Long?,
	): Boolean {
		require(expectedCollectedDataEpoch >= 0L)
		require(expectedRegistrationGeneration == null || expectedRegistrationGeneration > 0L)
		val dao = database.sourceBrokerDao()
		if (expectedRegistrationGeneration == null) {
			return dao.currentPhysicalRegistration(source.stableCode) == null &&
				dao.pendingProviderRemovals(source.stableCode).isEmpty()
		}
		val registration = dao.registration(source.stableCode, expectedRegistrationGeneration)
			?: return false
		return registration.collectedDataEpoch == expectedCollectedDataEpoch &&
			registration.status == ProviderRegistrationGenerationEntity.STATUS_RETIRED &&
			dao.currentPhysicalRegistration(source.stableCode)?.let { current ->
				current.registrationGeneration == expectedRegistrationGeneration &&
					current.sourceInstanceId == registration.sourceInstanceId
			} != false &&
			dao.pendingProviderRemovals(source.stableCode).isEmpty()
	}

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
	): SourceRegistration = beginInternal(
		source = source,
		appliedRevision = appliedRevision,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		updatedAtMs = updatedAtMs,
		updatedElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
		purposeEligibilityMask = SourceBrokerPurpose.ALL_MASK,
	)

	/** Reserves a provider that is physically capable of satisfying only the selected purposes. */
	internal suspend fun beginPurposeScoped(
		source: SourceKind,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
		purposeEligibilityMask: Long,
	): SourceRegistration = beginInternal(
		source = source,
		appliedRevision = appliedRevision,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		updatedAtMs = updatedAtMs,
		updatedElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
		purposeEligibilityMask = purposeEligibilityMask,
	)

	private suspend fun beginInternal(
		source: SourceKind,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
		purposeEligibilityMask: Long,
	): SourceRegistration {
		require(source != SourceKind.ACTIVITY) {
			"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
		}
		require(physicalConfigurationFingerprint.isNotBlank())
		require(
			purposeEligibilityMask > 0L &&
				(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK.inv()) == 0L,
		)
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = clockDomainProvider.current()
		val processIncarnationId = processIncarnationIdProvider.current()
		val ownerScope = if (purposeEligibilityMask == SourceBrokerPurpose.ALL_MASK) {
			SourceProviderPurposeScope.sharedOwnerScope(source.stableCode)
		} else {
			SourceProviderPurposeScope.exactOwnerScope(source.stableCode, purposeEligibilityMask)
		}
		val observedDemands = SourceProviderPurposeScope.selectDemands(
			source.stableCode,
			ownerScope,
			database.sourceBrokerDao().authorizationDemands(source.stableCode),
		)
		val retentionSnapshot = sourceBroker.captureLiveAmbientRetentionSnapshot(
			demands = observedDemands,
			expectedCollectedDataEpoch = lifecycle.epoch,
			currentBootId = clockDomainId,
			currentElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
			currentWallTimeMs = updatedAtMs,
		)
		return database.withTransaction {
			requireSourceAcquisitionReachable(source)
			val brokerDao = database.sourceBrokerDao()
			check(
				!brokerDao.hasNonterminalProcessBoundRegistrationsFromAnotherIncarnation(
					processIncarnationId,
				),
			) { "Prior-process provider registrations must be reconciled before provider startup" }
			check(
				!brokerDao.hasPendingCurrentProcessProviderRemoval(
					sourceKind = source.stableCode,
					currentProcessId = processIncarnationId,
				),
			) { "Pending provider removal must complete before a replacement can be reserved" }
			val demands = SourceProviderPurposeScope.selectDemands(
				source.stableCode,
				ownerScope,
				brokerDao.authorizationDemands(source.stableCode),
			)
			require(demands.isNotEmpty()) {
				"A source registration requires a compatible durable active broker demand"
			}
			check(demands == observedDemands) {
				"Broker demand changed while retention authority was being acquired"
			}
			require(
				(SourceBrokerAuthorization.purposeMask(demands) and purposeEligibilityMask) != 0L,
			) {
				"Broker demand has no recognized purpose eligibility"
			}
			require(supportsExactDemandPurposes(source, demands)) {
				"Pressure provider registration requires exact SESSION_CAPTURE demand only"
			}
			check(retentionSnapshot != null &&
				sourceBroker.areLiveAmbientDemandsCurrentInTransaction(
				demands = demands,
				expectedCollectedDataEpoch = lifecycle.epoch,
				currentBootId = clockDomainId,
				currentElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
				currentWallTimeMs = updatedAtMs,
				retentionSnapshot = retentionSnapshot,
			)) {
				"LIVE_AMBIENT retention authority changed before provider reservation"
			}
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
					providerAcceptedElapsedRealtimeNanos = when (currentPhysical.status) {
						ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
							requireNotNull(currentPhysical.acceptedElapsedRealtimeNanos)
						else -> null
					},
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
	): SourceRegistration? = refreshActiveAuthorizationInternal(
		source = source,
		expectedRegistration = expectedRegistration,
		appliedRevision = appliedRevision,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		updatedAtMs = updatedAtMs,
		updatedElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
		purposeEligibilityMask = SourceBrokerPurpose.ALL_MASK,
	)

	internal suspend fun refreshPurposeScopedAuthorization(
		source: SourceKind,
		expectedRegistration: SourceRegistration,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
		purposeEligibilityMask: Long,
	): SourceRegistration? = refreshActiveAuthorizationInternal(
		source = source,
		expectedRegistration = expectedRegistration,
		appliedRevision = appliedRevision,
		physicalConfigurationFingerprint = physicalConfigurationFingerprint,
		updatedAtMs = updatedAtMs,
		updatedElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
		purposeEligibilityMask = purposeEligibilityMask,
	)

	private suspend fun refreshActiveAuthorizationInternal(
		source: SourceKind,
		expectedRegistration: SourceRegistration,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
		purposeEligibilityMask: Long,
	): SourceRegistration? {
		require(source != SourceKind.ACTIVITY) {
			"Activity registrations are system-rearmable and must use ActivityRegistrationArbiter"
		}
		require(physicalConfigurationFingerprint.isNotBlank())
		require(expectedRegistration.state.sourceKind == source.stableCode)
		require(
			purposeEligibilityMask > 0L &&
				(purposeEligibilityMask and SourceBrokerPurpose.ALL_MASK.inv()) == 0L,
		)
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = clockDomainProvider.current()
		val processIncarnationId = processIncarnationIdProvider.current()
		val expectedOwnerScope = if (purposeEligibilityMask == SourceBrokerPurpose.ALL_MASK) {
			SourceProviderPurposeScope.sharedOwnerScope(source.stableCode)
		} else {
			SourceProviderPurposeScope.exactOwnerScope(source.stableCode, purposeEligibilityMask)
		}
		val observedDemands = SourceProviderPurposeScope.selectDemands(
			source.stableCode,
			expectedOwnerScope,
			database.sourceBrokerDao().authorizationDemands(source.stableCode),
		)
		val retentionSnapshot = sourceBroker.captureLiveAmbientRetentionSnapshot(
			demands = observedDemands,
			expectedCollectedDataEpoch = lifecycle.epoch,
			currentBootId = clockDomainId,
			currentElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
			currentWallTimeMs = updatedAtMs,
		)
		return database.withTransaction {
			if (!isSourceAcquisitionReachable(source)) return@withTransaction null
			val brokerDao = database.sourceBrokerDao()
			if (expectedRegistration.ownerScope != expectedOwnerScope) return@withTransaction null
			val demands = SourceProviderPurposeScope.selectDemands(
				source.stableCode,
				expectedOwnerScope,
				brokerDao.authorizationDemands(source.stableCode),
			)
			if (demands != observedDemands) return@withTransaction null
			if (demands.isEmpty()) return@withTransaction null
			if ((SourceBrokerAuthorization.purposeMask(demands) and purposeEligibilityMask) == 0L) {
				return@withTransaction null
			}
			if (!supportsExactDemandPurposes(source, demands)) return@withTransaction null
			if (retentionSnapshot == null ||
				!sourceBroker.areLiveAmbientDemandsCurrentInTransaction(
					demands = demands,
					expectedCollectedDataEpoch = lifecycle.epoch,
					currentBootId = clockDomainId,
					currentElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
					currentWallTimeMs = updatedAtMs,
					retentionSnapshot = retentionSnapshot,
				)
			) return@withTransaction null
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
				requiresProviderAcceptance = false, predecessorState = current,
				providerAcceptedElapsedRealtimeNanos = requireNotNull(currentPhysical.acceptedElapsedRealtimeNanos),
			)
		}
	}

	suspend fun markAccepted(
		registration: SourceRegistration,
		acceptedAtMs: Long,
		acceptedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): ProviderRegistrationGenerationEntity? {
		if (!registration.requiresProviderAcceptance) return null
		val source = SourceKind.entries.single { it.stableCode == registration.state.sourceKind }
		val lifecycle = lifecycleStore.snapshot()
		check(lifecycle.epoch == registration.state.collectedDataEpoch) {
			"Collected-data epoch changed before provider acceptance"
		}
		val observedDemands = SourceProviderPurposeScope.selectDemands(
			source.stableCode,
			registration.ownerScope,
			database.sourceBrokerDao().authorizationDemands(source.stableCode),
		)
		val retentionSnapshot = sourceBroker.captureLiveAmbientRetentionSnapshot(
			demands = observedDemands,
			expectedCollectedDataEpoch = lifecycle.epoch,
			currentBootId = registration.state.clockDomainId,
			currentElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
			currentWallTimeMs = acceptedAtMs,
		)
		return database.withTransaction {
			requireSourceAcquisitionReachable(source)
			val demands = SourceProviderPurposeScope.selectDemands(
				source.stableCode,
				registration.ownerScope,
				database.sourceBrokerDao().authorizationDemands(source.stableCode),
			)
			check(demands == observedDemands) {
				"Broker demand changed before provider acceptance"
			}
			check(
				database.sourceEvidenceStateDao().get()?.collectedDataEpoch == lifecycle.epoch,
			) {
				"Collected-data epoch changed before provider acceptance"
			}
			check(supportsExactDemandPurposes(source, demands)) {
				"Pressure provider acceptance requires exact SESSION_CAPTURE demand only"
			}
			check(retentionSnapshot != null &&
				sourceBroker.areLiveAmbientDemandsCurrentInTransaction(
				demands = demands,
				expectedCollectedDataEpoch = lifecycle.epoch,
				currentBootId = registration.state.clockDomainId,
				currentElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
				currentWallTimeMs = acceptedAtMs,
				retentionSnapshot = retentionSnapshot,
			)) {
				"LIVE_AMBIENT retention authority changed before provider acceptance"
			}
			database.sourceBrokerDao().acceptReservedReplacement(
				reservedState = registration.state,
				expectedPointerGeneration = registration.predecessorState?.registrationGeneration,
				expectedPointerInstanceId = registration.predecessorState?.sourceInstanceId,
				requiredAuthorizationFingerprint = registration.authorization.authorizationFingerprint,
				acceptedAtMs = acceptedAtMs,
				acceptedElapsedRealtimeNanos = acceptedElapsedRealtimeNanos,
			)
		}
	}

	/**
	 * Publishes Cell's source-local callback barrier only for the exact current process generation.
	 * The runtime calls this after closing callback entry and draining every earlier FIFO item.
	 */
	internal suspend fun publishCellCaptureCallbackBarrier(
		expectedRegistration: SourceRegistration,
		expectedCollectedDataEpoch: Long,
	): CellCaptureCallbackBarrierPublication {
		require(expectedRegistration.state.sourceKind == SourceKind.CELL.stableCode)
		require(expectedCollectedDataEpoch >= 0L)
		val lifecycle = lifecycleStore.snapshot()
		if (lifecycle.epoch != expectedCollectedDataEpoch) {
			return CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.STALE_LIFECYCLE,
			)
		}
		val currentProcessId = processIncarnationIdProvider.current()
		return database.withTransaction {
			val state = database.sourceRegistrationStateDao().get(
				SourceKind.CELL.stableCode,
				expectedRegistration.ownerScope,
			)
			val physical = database.sourceBrokerDao().registration(
				SourceKind.CELL.stableCode,
				expectedRegistration.state.registrationGeneration,
			)
			if (state == null || physical == null ||
				state.sourceInstanceId != expectedRegistration.state.sourceInstanceId ||
				state.registrationGeneration != expectedRegistration.state.registrationGeneration ||
				state.clockDomainId != expectedRegistration.state.clockDomainId ||
				state.collectedDataEpoch != expectedCollectedDataEpoch ||
				physical.sourceInstanceId != expectedRegistration.state.sourceInstanceId ||
				physical.ownerScope != expectedRegistration.ownerScope ||
				physical.clockDomainId != expectedRegistration.state.clockDomainId ||
				physical.physicalConfigurationFingerprint !=
					expectedRegistration.physicalConfigurationFingerprint ||
				physical.collectedDataEpoch != expectedCollectedDataEpoch ||
				physical.providerResidency !=
					ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
				physical.providerProcessIncarnationId != currentProcessId ||
				physical.status != ProviderRegistrationGenerationEntity.STATUS_ACTIVE
			) return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION,
			)

			val factDao = database.cellCapturedFactDao()
			val latestRevision = factDao.maximumRegistrationAuthorizationRevision(
				SourceKind.CELL.stableCode,
				expectedRegistration.state.registrationGeneration,
			)
			val rows = factDao.maintenanceAuthorizationMembers(
				SourceKind.CELL.stableCode,
				expectedRegistration.state.registrationGeneration,
				latestRevision,
				MAX_CELL_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS + 1,
			)
			val currentAuthorization = if (rows.size <= MAX_CELL_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS) {
				try {
					rows.toAuthorizationSnapshotOrNull()
				} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
					null
				}
			} else null
			if (latestRevision <= 0L || currentAuthorization == null ||
				!currentAuthorization.sameAuthorizationAs(expectedRegistration.authorization)
			) return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
			)
			val activeDemands = factDao.activeCellDemandsForDeletion(
				SourceKind.CELL.stableCode,
				MAX_CELL_CALLBACK_BARRIER_DEMANDS + 1,
			)
			val expectedRows = try {
				SourceBrokerAuthorization.rows(
					sourceKind = SourceKind.CELL.stableCode,
					registrationGeneration = expectedRegistration.state.registrationGeneration,
					authorizationRevision = latestRevision,
					demands = activeDemands,
					effectiveBootId = currentAuthorization.effectiveBootId,
					effectiveElapsedRealtimeNanos = currentAuthorization.effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs = rows.first().effectiveWallTimeMs,
				)
			} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
				return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
				)
			}
			if (activeDemands.size > MAX_CELL_CALLBACK_BARRIER_DEMANDS ||
				rows.sortedBy { row -> row.memberId } != expectedRows.sortedBy { row -> row.memberId }
			) return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
			)
			if (currentAuthorization.authorizedMembers.any { member ->
					member.persistenceEligible && member.purpose in setOf(
						SourceBrokerPurpose.SESSION_CAPTURE,
						SourceBrokerPurpose.AMBIENT_PRODUCT,
					)
				}) return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
			)

			val maximumCaptureRevision = database.sourceBrokerDao()
				.maximumCaptureAuthorizationRevision(
					SourceKind.CELL.stableCode,
					expectedRegistration.state.registrationGeneration,
				)
			if (physical.captureCallbackBarrierAuthorizationRevision > maximumCaptureRevision) {
				return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
				)
			}
			if (database.sourceBrokerDao().acknowledgeCaptureCallbackBarrier(
					sourceKind = SourceKind.CELL.stableCode,
					registrationGeneration = expectedRegistration.state.registrationGeneration,
					sourceInstanceId = expectedRegistration.state.sourceInstanceId,
					throughAuthorizationRevision = maximumCaptureRevision,
				) != 1
			) return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
				CellCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION,
			)
			val acknowledged = database.sourceBrokerDao().registration(
				SourceKind.CELL.stableCode,
				expectedRegistration.state.registrationGeneration,
			)
			if (acknowledged?.captureCallbackBarrierAuthorizationRevision != maximumCaptureRevision) {
				return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION,
				)
			}
			CellCaptureCallbackBarrierPublication.Established(maximumCaptureRevision)
		}
	}

	/**
	 * Publishes Wi-Fi's source-local callback barrier only for the exact current process
	 * generation. The runtime calls this after closing callback entry and draining every earlier
	 * FIFO item. Independently authorized CONTROL and AMBIENT_PRODUCT members may remain.
	 */
	internal suspend fun publishWifiCaptureCallbackBarrier(
		expectedRegistration: SourceRegistration,
		expectedCollectedDataEpoch: Long,
	): WifiCaptureCallbackBarrierPublication {
		require(expectedRegistration.state.sourceKind == SourceKind.WIFI.stableCode)
		require(expectedCollectedDataEpoch >= 0L)
		val lifecycle = lifecycleStore.snapshot()
		if (lifecycle.epoch != expectedCollectedDataEpoch) {
			return WifiCaptureCallbackBarrierPublication.Blocked(
				WifiCaptureCallbackBarrierBlockedReason.STALE_LIFECYCLE,
			)
		}
		val currentProcessId = processIncarnationIdProvider.current()
		return database.withTransaction {
			val state = database.sourceRegistrationStateDao().get(
				SourceKind.WIFI.stableCode,
				expectedRegistration.ownerScope,
			)
			val physical = database.sourceBrokerDao().registration(
				SourceKind.WIFI.stableCode,
				expectedRegistration.state.registrationGeneration,
			)
			if (state == null || physical == null ||
				state.sourceInstanceId != expectedRegistration.state.sourceInstanceId ||
				state.registrationGeneration != expectedRegistration.state.registrationGeneration ||
				state.clockDomainId != expectedRegistration.state.clockDomainId ||
				state.collectedDataEpoch != expectedCollectedDataEpoch ||
				physical.sourceInstanceId != expectedRegistration.state.sourceInstanceId ||
				physical.ownerScope != expectedRegistration.ownerScope ||
				physical.clockDomainId != expectedRegistration.state.clockDomainId ||
				physical.physicalConfigurationFingerprint !=
					expectedRegistration.physicalConfigurationFingerprint ||
				physical.collectedDataEpoch != expectedCollectedDataEpoch ||
				physical.providerResidency !=
					ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
				physical.providerProcessIncarnationId != currentProcessId ||
				physical.status != ProviderRegistrationGenerationEntity.STATUS_ACTIVE
			) return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
				WifiCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION,
			)

			val factDao = database.wifiCapturedFactDao()
			val latestRevision = factDao.maximumRegistrationAuthorizationRevision(
				SourceKind.WIFI.stableCode,
				expectedRegistration.state.registrationGeneration,
			)
			val rows = factDao.maintenanceAuthorizationMembers(
				SourceKind.WIFI.stableCode,
				expectedRegistration.state.registrationGeneration,
				latestRevision,
				MAX_WIFI_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS + 1,
			)
			val currentAuthorization = if (
				rows.size <= MAX_WIFI_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS
			) {
				try {
					rows.toAuthorizationSnapshotOrNull()
				} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
					null
				}
			} else null
			if (latestRevision <= 0L || currentAuthorization == null ||
				!currentAuthorization.sameWifiBarrierAuthorizationAs(expectedRegistration.authorization)
			) return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
				WifiCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
			)
			val activeDemands = factDao.activeDemandsForDeletion(
				SourceKind.WIFI.stableCode,
				MAX_WIFI_CALLBACK_BARRIER_DEMANDS + 1,
			)
			val expectedRows = try {
				SourceBrokerAuthorization.rows(
					sourceKind = SourceKind.WIFI.stableCode,
					registrationGeneration = expectedRegistration.state.registrationGeneration,
					authorizationRevision = latestRevision,
					demands = activeDemands,
					effectiveBootId = currentAuthorization.effectiveBootId,
					effectiveElapsedRealtimeNanos =
						currentAuthorization.effectiveElapsedRealtimeNanos,
					effectiveWallTimeMs = rows.first().effectiveWallTimeMs,
				)
			} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
				return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
				)
			}
			if (activeDemands.size > MAX_WIFI_CALLBACK_BARRIER_DEMANDS ||
				rows.sortedBy { row -> row.memberId } != expectedRows.sortedBy { row -> row.memberId }
			) return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
				WifiCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
			)
			if (currentAuthorization.authorizedMembers.any { member ->
					member.persistenceEligible && member.purpose == SourceBrokerPurpose.SESSION_CAPTURE
				}
			) return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
				WifiCaptureCallbackBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE,
			)

			val maximumProductRevision = database.sourceBrokerDao()
				.maximumCaptureAuthorizationRevision(
					SourceKind.WIFI.stableCode,
					expectedRegistration.state.registrationGeneration,
				)
			if (physical.captureCallbackBarrierAuthorizationRevision > maximumProductRevision) {
				return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE,
				)
			}
			if (database.sourceBrokerDao().acknowledgeCaptureCallbackBarrier(
					sourceKind = SourceKind.WIFI.stableCode,
					registrationGeneration = expectedRegistration.state.registrationGeneration,
					sourceInstanceId = expectedRegistration.state.sourceInstanceId,
					throughAuthorizationRevision = maximumProductRevision,
				) != 1
			) return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
				WifiCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION,
			)
			val acknowledged = database.sourceBrokerDao().registration(
				SourceKind.WIFI.stableCode,
				expectedRegistration.state.registrationGeneration,
			)
			if (acknowledged?.captureCallbackBarrierAuthorizationRevision !=
				maximumProductRevision
			) return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
				WifiCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION,
			)
			WifiCaptureCallbackBarrierPublication.Established(maximumProductRevision)
		}
	}

	private suspend fun requireSourceAcquisitionReachable(source: SourceKind) {
		require(isSourceAcquisitionReachable(source)) {
			"Source ${source.name} is contained by the current rollout state"
		}
	}

	private suspend fun isSourceAcquisitionReachable(source: SourceKind): Boolean =
		trackingRolloutStateStore.load().isAcquisitionReachable(source)

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
		demands: List<SourceDemandEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): SourceAuthorizationSnapshot {
		check(supportsExactDemandPurposes(source, demands)) {
			"Unsupported demand purpose cannot enter observed-time authorization"
		}
		val dao = database.sourceBrokerDao()
		val fingerprint = SourceBrokerAuthorization.fingerprint(demands)
		val latest = dao.latestAuthorization(source.stableCode, registrationGeneration)
			.toAuthorizationSnapshotOrNull()
		if (latest != null &&
			latest.authorizationFingerprint == fingerprint &&
			latest.effectiveBootId == bootId
		) return latest
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

	private fun supportsExactDemandPurposes(
		source: SourceKind,
		demands: List<SourceDemandEntity>,
	): Boolean = source != SourceKind.PRESSURE || (
		demands.isNotEmpty() && demands.all { demand ->
			demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}
	)

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
		terminalCompleteness: SourceSessionCompletenessEntity? = null,
		terminalStepsCountDomainEvidence: StepsCountDomainRetirementEvidence? = null,
	) {
		database.withTransaction {
			val currentRegistration = database.sourceRegistrationStateDao().get(
				registration.state.sourceKind,
				registration.ownerScope,
			)
			check(currentRegistration != null &&
				currentRegistration.sourceInstanceId == registration.state.sourceInstanceId &&
				currentRegistration.clockDomainId == registration.state.clockDomainId &&
				currentRegistration.registrationGeneration == registration.state.registrationGeneration
			) { "Source registration changed while its runtime checkpoint was being saved" }
			val incoming = SourceRuntimeStateEntity(
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
			)
			val dao = database.sourceRuntimeStateDao()
			val stored = if (stateVersion == SENSOR_RUNTIME_CHECKPOINT_VERSION) {
				mergeSensorRuntimeStates(
					current = dao.get(incoming.sourceKind, incoming.ownerScope),
					incoming = incoming,
					legacyComponentStateVersion = stateVersion,
				)
			} else {
				incoming
			}
			check(stored.sourceInstanceId == registration.state.sourceInstanceId &&
				stored.clockDomainId == registration.state.clockDomainId &&
				stored.registrationGeneration == registration.state.registrationGeneration
			) { "Runtime checkpoint was superseded by an incompatible registration" }
			dao.save(stored)
			terminalCompleteness?.let { candidate ->
				check(candidate.sourceKind == registration.state.sourceKind)
				check(candidate.sourceInstanceId == registration.state.sourceInstanceId)
				check(candidate.registrationGeneration == registration.state.registrationGeneration)
				val countDomainStore = StepsCountDomainStore(database)
				val completeness = if (
					candidate.sourceKind == SourceKind.STEPS.stableCode &&
					countDomainStore.isInstalled()
				) {
					val existing = database.sourceSessionDao().completenessForServiceRun(
						candidate.logicalTrackingId,
						candidate.serviceRunId,
					).singleOrNull {
						it.sourceKind == candidate.sourceKind &&
							it.sourceInstanceId == candidate.sourceInstanceId &&
							it.registrationGeneration == candidate.registrationGeneration
					}
					candidate.withMonotonicStepsCountDomainRevision(existing)
				} else {
					candidate
				}
				database.sourceSessionDao().saveCompleteness(completeness)
				if (completeness.sourceKind == SourceKind.STEPS.stableCode) {
					val countDomainResult =
						countDomainStore.recordSessionCompleteness(
							completeness,
							terminalStepsCountDomainEvidence
								?: StepsCountDomainRetirementEvidence(
									providerFlushOutcome = "UNOBSERVABLE",
									registrationRemovalOutcome = "UNOBSERVABLE",
								),
						)
					check(
						countDomainResult in setOf(
							StepsCountDomainWriteResult.INSERTED,
							StepsCountDomainWriteResult.EXACT_REPLAY,
							StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE,
						),
					) {
						"Unable to publish Steps completeness before exact count-domain authority"
					}
					if (countDomainResult == StepsCountDomainWriteResult.INSERTED) {
						check(
							database.sourceEvidenceStateDao().incrementRevision(updatedAtMs) == 1,
						) { "Unable to publish recovered Steps count-domain completeness" }
					}
				}
			}
		}
	}
}

private fun SourceAuthorizationSnapshot.sameAuthorizationAs(
	other: SourceAuthorizationSnapshot,
): Boolean = authorizationRevision == other.authorizationRevision &&
	authorizationFingerprint == other.authorizationFingerprint &&
	purposeEligibilityMask == other.purposeEligibilityMask &&
	effectiveBootId == other.effectiveBootId &&
	effectiveElapsedRealtimeNanos == other.effectiveElapsedRealtimeNanos &&
	members.sortedBy { row -> row.memberId } == other.members.sortedBy { row -> row.memberId }

private const val MAX_CELL_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS = 64
private const val MAX_CELL_CALLBACK_BARRIER_DEMANDS = 256

private fun SourceAuthorizationSnapshot.sameWifiBarrierAuthorizationAs(
	other: SourceAuthorizationSnapshot,
): Boolean = authorizationRevision == other.authorizationRevision &&
	authorizationFingerprint == other.authorizationFingerprint &&
	purposeEligibilityMask == other.purposeEligibilityMask &&
	effectiveBootId == other.effectiveBootId &&
	effectiveElapsedRealtimeNanos == other.effectiveElapsedRealtimeNanos &&
	members.sortedBy { row -> row.memberId } == other.members.sortedBy { row -> row.memberId }

private const val MAX_WIFI_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS = 64
private const val MAX_WIFI_CALLBACK_BARRIER_DEMANDS = 256
