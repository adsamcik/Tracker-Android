package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.shared.base.database.hasExactRadioSessionCaptureCallbackBarrier
import android.os.SystemClock
import com.adsamcik.tracker.shared.base.database.data.CellWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.CellWalPayloadDecodeResult
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadCodec
import com.adsamcik.tracker.shared.base.database.data.WifiWalPayloadDecodeResult
import com.adsamcik.tracker.shared.base.database.data.MAX_WIFI_WAL_CHILDREN
import com.adsamcik.tracker.shared.base.database.data.MAX_CELL_WAL_CHILDREN
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemAuthority
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualification
import com.adsamcik.tracker.shared.base.database.data.RadioPurposeItemQualifier
import com.adsamcik.tracker.tracker.source.ingress.DefaultSourcePayloadCodec
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.sourceAuthorizationPublicationBoundary
import com.adsamcik.tracker.shared.base.database.recordLocationRegistrationPlan
import com.adsamcik.tracker.shared.base.database.data.LocationRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.LocationRegistrationPlanIntegrity
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.LocationPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.shared.base.database.StepsCountDomainRetirementEvidence
import com.adsamcik.tracker.shared.base.database.StepsCountDomainStore
import com.adsamcik.tracker.shared.base.database.StepsCountDomainWriteResult
import com.adsamcik.tracker.shared.base.database.publishStepsCountDomainEvidenceRevisionAtWallTime
import com.adsamcik.tracker.shared.base.database.withMonotonicStepsCountDomainRevision
import com.adsamcik.tracker.shared.base.database.dao.PriorProcessRegistrationReconciliationResult
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.hasExactEligibleAmbientConsentReference
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.isEffectiveAtOrBefore
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
	val processIncarnationId: String?,
	val retiredAtMs: Long,
	val retiredElapsedRealtimeNanos: Long,
	val reason: String?,
	val providerResidency: String = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
	val clockDomainId: String? = null,
	val physicalConfigurationFingerprint: String? = null,
	val collectedDataEpoch: Long? = null,
)

internal data class PassiveLocationAdmission(
	val attribution: LocationDeliveryAttribution,
	val approximate: Boolean,
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

/** Outcome of saving runtime progress under the exact current source registration. */
sealed interface SourceRuntimeStateSaveResult {
	/** The registration, sequence progress and optional terminal evidence were saved together. */
	data object Saved : SourceRuntimeStateSaveResult
	/**
	 * The current registration or data authority could not be verified; progress was not acknowledged.
	 */
	data object Unverifiable : SourceRuntimeStateSaveResult
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
// Registration changes and callback barriers share one transaction owner; method checks remain enabled.
@Suppress("LargeClass")
@Singleton
class SourceRegistrationRepository @Inject constructor(
	private val database: AppDatabase,
	private val lifecycleStore: CollectedDataLifecycleStore,
	private val clockDomainProvider: BootClockDomainProvider,
	private val processIncarnationIdProvider: ProcessIncarnationIdProvider,
	private val trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	private val sourceBroker: SourceBroker = SourceBroker(database),
) {

	/** A durable shared callback satisfies a live request only through its exact capture items. */
	internal suspend fun captureQualifiedRadioObservationTimes(
		registration: SourceRegistration,
		evidence: SourceEvidenceCandidate<*>,
	): List<Long> {
		val capture = registration.authorization.authorizedMembers.singleOrNull {
			it.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}?.takeIf { it.persistenceEligible }
		return if (capture == null) {
			emptyList()
		} else {
			qualifiedRadioObservationTimes(registration, evidence, capture)
		}
	}

	private suspend fun qualifiedRadioObservationTimes(
		registration: SourceRegistration,
		evidence: SourceEvidenceCandidate<*>,
		capture: SourceAuthorizationEntity,
	): List<Long> {
		val snapshot = registration.authorization
		val accepted = registration.providerAcceptedElapsedRealtimeNanos ?: return emptyList()
		val ids = snapshot.authorizedMembers.mapNotNull { it.demandId }
		if (!SourceRegistrationCallbackAuthority.matchesRadioEvidence(registration, evidence, ids)) {
			return emptyList()
		}
		val times = radioProviderObservationTimes(evidence) ?: return emptyList()
		return try {
			database.withTransaction {
				val demands = database.sourceBrokerDao().demandsByIds(ids)
				if (demands.size != ids.size) {
					return@withTransaction emptyList()
				}
				val authority = RadioPurposeItemAuthority(evidence.source.stableCode,
					evidence.registrationGeneration, evidence.clockDomainId, accepted, Long.MAX_VALUE,
					evidence.receivedElapsedRealtimeNanos, listOf(snapshot), demands)
				val qualified = qualifyRadioCallbackPayload(evidence, authority)
					?: return@withTransaction emptyList()
				qualified.acceptedIndexesByDemandId[capture.demandId].orEmpty().mapNotNull { times.getOrNull(it) }
			}
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			// Qualification failure leaves the finite fresh request eligible; admitted history remains.
			emptyList()
		}
	}

	/** Preserve the actual request alongside the immutable user/session target. */
	internal suspend fun recordLocationPlan(registration: SourceRegistration, plan: LocationPlan) {
		check(registration.state.appliedRevision == plan.revision)
		check(registration.physicalConfigurationFingerprint == plan.physicalConfigurationFingerprint())
		val encoded = SourcePlanCodec().encode(plan)
		database.recordLocationRegistrationPlan(LocationRegistrationPlanIntegrity.create(
			registrationGeneration = registration.state.registrationGeneration,
			sourceInstanceId = registration.state.sourceInstanceId,
			configurationRevision = plan.revision,
			authorizationRevision = registration.authorization.authorizationRevision,
			physicalConfigurationFingerprint = registration.physicalConfigurationFingerprint,
			payloadVersion = encoded.formatVersion,
			payloadChecksum = encoded.checksum,
			payload = encoded.bytes,
		))
	}

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
			reason =
				ProviderRegistrationGenerationEntity.FAILURE_PRIOR_PROCESS_INCARNATION_ENDED,
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

	/** Sessionless passive Location reserves the same shared owner with system residency. */
	internal suspend fun beginSystemRearmableLocation(
		plan: LocationPlan,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): SourceRegistration {
		require(plan.passiveSystemDelivery)
		return beginInternal(SourceKind.LOCATION, plan.revision,
			plan.physicalConfigurationFingerprint(), updatedAtMs, updatedElapsedRealtimeNanos,
			SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE)
	}

	internal suspend fun systemLocationRegistrations(): List<ProviderRegistrationGenerationEntity> =
		database.sourceBrokerDao().nonterminalSystemLocationRegistrations(2)

	internal fun currentBootId(): String = clockDomainProvider.current()

	internal fun currentProcessIncarnationId(): String = processIncarnationIdProvider.current()

	internal suspend fun canReuseSystemLocation(
		row: ProviderRegistrationGenerationEntity,
		fingerprint: String,
	): Boolean {
		val current = database.sourceRegistrationStateDao().get(row.sourceKind, row.ownerScope)
		return row.clockDomainId == clockDomainProvider.current() &&
			row.collectedDataEpoch == lifecycleStore.snapshot().epoch &&
			row.physicalConfigurationFingerprint == fingerprint &&
			row.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE &&
			current?.registrationGeneration == row.registrationGeneration &&
			current?.sourceInstanceId == row.sourceInstanceId
	}

	internal suspend fun abandonUnplannedSystemLocationReservation(
		row: ProviderRegistrationGenerationEntity,
	): Boolean {
		if (row.status != ProviderRegistrationGenerationEntity.STATUS_RESERVED ||
			database.locationPurposeDao().latestRegistrationPlan(row.registrationGeneration) != null
		) {
			return false
		}
		if (row.clockDomainId != clockDomainProvider.current()) {
			return completeRemovedPriorBootSystemLocation(row)
		}
		return database.sourceBrokerDao().failUnacceptedSystemLocationReservation(
			row.registrationGeneration, row.sourceInstanceId, System.currentTimeMillis(),
			SystemClock.elapsedRealtimeNanos(), "UNPLANNED_PASSIVE_LOCATION_RESERVATION") == 1
	}

	/** Reconstruct only an exact, authenticated v4 request; an unknown handle fences replacement. */
	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	internal suspend fun passiveLocationIdentity(
		row: ProviderRegistrationGenerationEntity,
	): PassiveLocationIdentity? {
		if (row.sourceKind != SourceKind.LOCATION.stableCode ||
			row.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE
		) {
			return null
		}
		val stored = database.locationPurposeDao().latestRegistrationPlan(row.registrationGeneration)
			?: return null
		if (stored.registrationGeneration != row.registrationGeneration) {
			return null
		}
		val plan = stored.passiveLocationPlanOrNull(row.sourceInstanceId, row.physicalConfigurationFingerprint)
			?: return null
		return PassiveLocationIdentity(plan.backend, row.sourceInstanceId,
			row.registrationGeneration, row.clockDomainId,
			row.physicalConfigurationFingerprint, row.collectedDataEpoch)
	}

	/** Authorize each delivered fix at its provider time; result extras carry no authority. */
	internal suspend fun passiveLocationAdmissionAt(
		identity: PassiveLocationIdentity,
		observedElapsedRealtimeNanos: Long,
	): PassiveLocationAdmission? {
		if (observedElapsedRealtimeNanos <= 0L || identity.bootId != clockDomainProvider.current() ||
			identity.collectedDataEpoch != lifecycleStore.snapshot().epoch) {
			return null
		}
		return database.withTransaction { admitPassiveLocationInTransaction(identity, observedElapsedRealtimeNanos) }
	}

	// Ordered bounded provider, snapshot, member and demand failures preserve their original read order.
	@Suppress("ReturnCount")
	private suspend fun admitPassiveLocationInTransaction(
		identity: PassiveLocationIdentity, observedElapsedRealtimeNanos: Long,
	): PassiveLocationAdmission? {
		val dao = database.sourceBrokerDao()
		val row = dao.registrationAtObservedTime(SourceKind.LOCATION.stableCode,
			identity.generation, identity.sourceInstanceId, identity.bootId,
			identity.fingerprint, observedElapsedRealtimeNanos) ?: return null
		if (hasConflictingPassiveResidency(row, identity)) return null
		val rows = dao.authorizationAtBounded(row.sourceKind, row.registrationGeneration,
			identity.bootId, observedElapsedRealtimeNanos, MAX_PASSIVE_LOCATION_AUTHORIZATION_MEMBERS + 1)
		val authorization = boundedPassiveAuthorization(rows) ?: return null
		val ambientMember = authenticatedPassiveMember(identity, observedElapsedRealtimeNanos, authorization) ?: return null
		if (!hasExactPassiveDemandAuthority(row, rows, authorization)) return null
		return loadPassiveAdmissionPlan(identity, ambientMember, authorization)
	}

	private fun boundedPassiveAuthorization(rows: List<SourceAuthorizationEntity>): SourceAuthorizationSnapshot? {
		if (rows.isEmpty() || rows.size > MAX_PASSIVE_LOCATION_AUTHORIZATION_MEMBERS) {
			return null
		}
		return runCatching { rows.toAuthorizationSnapshotOrNull() }.getOrNull()
	}

	private fun hasConflictingPassiveResidency(
		row: ProviderRegistrationGenerationEntity, identity: PassiveLocationIdentity,
	): Boolean = row.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE ||
			row.collectedDataEpoch != identity.collectedDataEpoch ||
			!SourceProviderPurposeScope.supportsPurpose(row.sourceKind, row.ownerScope,
				SourceBrokerPurpose.AMBIENT_PRODUCT) ||
			SourceProviderPurposeScope.supportsPurpose(row.sourceKind, row.ownerScope,
				SourceBrokerPurpose.SESSION_CAPTURE)

	private suspend fun loadPassiveAdmissionPlan(
		identity: PassiveLocationIdentity, ambientMember: SourceAuthorizationEntity,
		authorization: SourceAuthorizationSnapshot,
	): PassiveLocationAdmission? {
		val stored = database.locationPurposeDao().registrationPlan(identity.generation,
			ambientMember.sourcePolicyRevision ?: return null,
			authorization.authorizationRevision) ?: return null
		val plan = stored.passiveLocationPlanOrNull(identity.sourceInstanceId, identity.fingerprint) {
			it.backend == identity.backend && it.revision == stored.configurationRevision
		} ?: return null
		return PassiveLocationAdmission(LocationDeliveryAttribution(
			SourceInstanceId(identity.sourceInstanceId), identity.generation, identity.fingerprint,
			authorization.authorizationRevision, authorization.purposeEligibilityMask,
			authorization.authorizationFingerprint, plan.revision, identity.bootId,
			identity.collectedDataEpoch), !plan.preciseLocationAvailable)
	}

	private suspend fun hasExactPassiveDemandAuthority(
		row: ProviderRegistrationGenerationEntity, rows: List<SourceAuthorizationEntity>,
		authorization: SourceAuthorizationSnapshot,
	): Boolean {
		val ids = authorization.authorizedMembers.mapNotNull { it.demandId }
		val demands = database.sourceBrokerDao().demandsByIds(ids)
		val expected = runCatching { SourceBrokerAuthorization.rows(row.sourceKind,
			row.registrationGeneration, authorization.authorizationRevision, demands,
			authorization.effectiveBootId, authorization.effectiveElapsedRealtimeNanos,
			rows.first().effectiveWallTimeMs) }.getOrNull()
		return !(ids.size != authorization.authorizedMembers.size || ids.distinct().size != ids.size ||
			demands.size != ids.size || expected?.sortedBy { it.memberId } !=
			rows.sortedBy { it.memberId })
	}

	private suspend fun authenticatedPassiveMember(
		identity: PassiveLocationIdentity, observedElapsedRealtimeNanos: Long, authorization: SourceAuthorizationSnapshot,
	): SourceAuthorizationEntity? {
		val ambientMember = authorization.authorizedMembers.singleOrNull {
			it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT && it.persistenceEligible &&
				it.logicalTrackingId == null && it.serviceRunId == null
		} ?: return null
		if (!passiveAuthorityStillCurrent(identity, observedElapsedRealtimeNanos, ambientMember)) return null
		if (authorization.isDenied || authorization.effectiveBootId != identity.bootId ||
			authorization.purposeEligibilityMask != SourceBrokerPurpose.MASK_AMBIENT_PRODUCT
		) {
			return null
		}
		return ambientMember
	}

	private suspend fun passiveAuthorityStillCurrent(
		identity: PassiveLocationIdentity, observedElapsedRealtimeNanos: Long, ambientMember: SourceAuthorizationEntity,
	): Boolean {
		// A cold provider result is a new delivery. Historical authority proves its observed
		// time, while this live fence prevents Off or a new consent epoch from admitting it.
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
			?.takeIf { it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE }
			?: return false
		val currentPolicy = policyDao.policyAtRevision(authority.currentPolicyRevision,
			SourceKind.LOCATION.stableCode) ?: return false
		val currentConsent = policyDao.latestConsentEpoch(SourceKind.LOCATION.stableCode,
			SourceBrokerPurpose.AMBIENT_PRODUCT)
		val currentRetention = database.locationPurposeDao().latestRetentionAuthority(
			AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
		val historicalRetention = database.locationPurposeDao().retentionAuthorityAt(
			AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			identity.bootId, observedElapsedRealtimeNanos)
		if (historicalRetention == null || !historicalRetention.isActive ||
			!AmbientLocationRetentionAuthorityIntegrity.isAuthentic(historicalRetention) ||
			historicalRetention.hasHistoricalPassiveMemberConflict(ambientMember, identity)) return false
		return currentPassiveAuthorityMatches(currentPolicy, currentConsent, currentRetention, ambientMember, identity)
	}

	private suspend fun currentPassiveAuthorityMatches(
		currentPolicy: SourcePolicyEntity, currentConsent: SourceConsentEpochEntity?,
		currentRetention: AmbientLocationRetentionAuthorityEntity?, ambientMember: SourceAuthorizationEntity,
		identity: PassiveLocationIdentity,
	): Boolean {
		if (!currentPolicy.hasExactEligibleAmbientConsentReference(currentConsent) ||
			currentPolicy.ambientConsentEpoch != ambientMember.consentEpoch) return false
		if (currentRetention == null || !currentRetention.isActive ||
			!AmbientLocationRetentionAuthorityIntegrity.isAuthentic(currentRetention)) return false
		return currentRetention.ambientConsentEpoch == ambientMember.consentEpoch &&
			currentRetention.collectedDataEpoch == identity.collectedDataEpoch &&
			currentRetention.opaquePolicyId == ambientMember.liveAmbientRetentionPolicyId &&
			currentRetention.retainedFromMs == database.sourceEvidenceStateDao().get()?.retainedFromMs
	}

	// Fixed historical retention/member identity; integrity is checked before this comparison.
	@Suppress("ComplexCondition")
	private fun AmbientLocationRetentionAuthorityEntity.hasHistoricalPassiveMemberConflict(
		ambientMember: SourceAuthorizationEntity, identity: PassiveLocationIdentity,
	): Boolean = sourcePolicyRevision != ambientMember.sourcePolicyRevision ||
		ambientConsentEpoch != ambientMember.consentEpoch ||
		collectedDataEpoch != identity.collectedDataEpoch ||
		opaquePolicyId != ambientMember.liveAmbientRetentionPolicyId ||
		approvalRevision != ambientMember.liveAmbientRetentionApprovalRevision

	internal suspend fun beginSystemLocationRetirement(
		row: ProviderRegistrationGenerationEntity,
		reason: String,
	): SourceRegistrationRetirementToken? {
		if (row.sourceKind != SourceKind.LOCATION.stableCode ||
			row.providerResidency != ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE ||
			row.clockDomainId != clockDomainProvider.current()) {
			return null
		}
		val dao = database.sourceBrokerDao()
		if (row.status != ProviderRegistrationGenerationEntity.STATUS_RETIRING) {
			dao.markSystemLocationRetiring(row.registrationGeneration, row.sourceInstanceId,
				System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), reason)
		}
		return dao.registration(row.sourceKind, row.registrationGeneration)
			?.takeIf { it.sourceInstanceId == row.sourceInstanceId &&
				it.status == ProviderRegistrationGenerationEntity.STATUS_RETIRING }
			?.toRetirementToken()
	}

	internal suspend fun completeRemovedPriorBootSystemLocation(
		row: ProviderRegistrationGenerationEntity,
	): Boolean {
		val currentBootId = clockDomainProvider.current()
		if (row.clockDomainId == currentBootId) {
			return false
		}
		return database.sourceBrokerDao().completeRemovedPriorBootSystemLocation(
			row.registrationGeneration, row.sourceInstanceId, currentBootId,
			ProviderRegistrationGenerationEntity.FAILURE_PRIOR_BOOT_SYSTEM_REMOVED) == 1
	}

	private suspend fun beginInternal(
		source: SourceKind,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
		purposeEligibilityMask: Long,
		providerResidency: String = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
	): SourceRegistration {
		requireSourceRegistrationRequest(source, physicalConfigurationFingerprint, purposeEligibilityMask)
		val lifecycle = lifecycleStore.snapshot()
		val clockDomainId = clockDomainProvider.current()
		val processIncarnationId = processIncarnationIdProvider.current()
		require(providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND ||
			(source == SourceKind.LOCATION &&
				providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE))
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
			requireProviderReservationLifetimes(
				source, providerResidency, processIncarnationId, ownerScope,
				physicalConfigurationFingerprint, clockDomainId, lifecycle.epoch,
			)
			val demands = requireProviderReservationDemands(
				source, ownerScope, purposeEligibilityMask, observedDemands, retentionSnapshot,
				lifecycle.epoch, clockDomainId, updatedElapsedRealtimeNanos, updatedAtMs,
			)
			settleProviderReservation(
				source, appliedRevision, physicalConfigurationFingerprint, updatedAtMs, updatedElapsedRealtimeNanos,
				providerResidency, lifecycle.epoch, clockDomainId, processIncarnationId, ownerScope, demands,
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

	internal suspend fun refreshSystemRearmableLocation(
		expectedRegistration: SourceRegistration,
		plan: LocationPlan,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): SourceRegistration? {
		require(plan.passiveSystemDelivery)
		return refreshActiveAuthorizationInternal(SourceKind.LOCATION, expectedRegistration,
			plan.revision, plan.physicalConfigurationFingerprint(), updatedAtMs,
			updatedElapsedRealtimeNanos, SourceBrokerPurpose.MASK_AMBIENT_PRODUCT,
			ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE)
	}

	private suspend fun refreshActiveAuthorizationInternal(
		source: SourceKind,
		expectedRegistration: SourceRegistration,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
		purposeEligibilityMask: Long,
		providerResidency: String = ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND,
	): SourceRegistration? {
		requireSourceRegistrationRefreshRequest(
			source, expectedRegistration, physicalConfigurationFingerprint, purposeEligibilityMask,
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
			if (!isSourceAcquisitionReachable(source)) {
				return@withTransaction null
			}
			if (expectedRegistration.ownerScope != expectedOwnerScope) {
				return@withTransaction null
			}
			val demands = readCurrentAuthorizationRefreshDemands(
				source, expectedOwnerScope, purposeEligibilityMask, observedDemands, retentionSnapshot,
				lifecycle.epoch, clockDomainId, updatedElapsedRealtimeNanos, updatedAtMs,
			) ?: return@withTransaction null
			publishRefreshedProviderAuthorization(
				source, expectedRegistration, appliedRevision, physicalConfigurationFingerprint,
				updatedAtMs, updatedElapsedRealtimeNanos, providerResidency, lifecycle.epoch,
				clockDomainId, processIncarnationId, demands,
			)
		}
	}

	// The current process, exact shared owner and captured epoch form one provider-removal boundary.
	@Suppress("LongParameterList")
	private suspend fun requireProviderReservationLifetimes(
		source: SourceKind,
		providerResidency: String,
		processIncarnationId: String,
		ownerScope: String,
		physicalConfigurationFingerprint: String,
		clockDomainId: String,
		collectedDataEpoch: Long,
	) {
		requireSourceAcquisitionReachable(source)
		val brokerDao = database.sourceBrokerDao()
		check(
			!brokerDao.hasNonterminalProcessBoundRegistrationsFromAnotherIncarnation(processIncarnationId),
		) { "Prior-process provider registrations must be reconciled before provider startup" }
		check(
			!brokerDao.hasPendingCurrentProcessProviderRemoval(
				sourceKind = source.stableCode,
				currentProcessId = processIncarnationId,
			),
		) { "Pending provider removal must complete before a replacement can be reserved" }
		if (source == SourceKind.LOCATION) {
			val system = brokerDao.nonterminalSystemLocationRegistrations(2)
			val systemPointer = database.sourceRegistrationStateDao().get(source.stableCode, ownerScope)
			check(system.size <= 1 && system.none { row ->
				!row.isCompatibleSystemLocationReservation(
					providerResidency, ownerScope, systemPointer, physicalConfigurationFingerprint,
					clockDomainId, collectedDataEpoch,
				)
			}) { "Rearmable Location removal must be acknowledged before replacement" }
		}
	}

	// Preserve the exact demands and retention snapshot captured before Room; no new authority is acquired here.
	@Suppress("LongParameterList")
	private suspend fun requireProviderReservationDemands(
		source: SourceKind,
		ownerScope: String,
		purposeEligibilityMask: Long,
		observedDemands: List<SourceDemandEntity>,
		retentionSnapshot: LiveAmbientRetentionSnapshot?,
		collectedDataEpoch: Long,
		clockDomainId: String,
		updatedElapsedRealtimeNanos: Long,
		updatedAtMs: Long,
	): List<SourceDemandEntity> {
		val brokerDao = database.sourceBrokerDao()
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
			"Pressure provider registration requires recording demand"
		}
		check(retentionSnapshot != null &&
			sourceBroker.areLiveAmbientDemandsCurrentInTransaction(
				demands = demands,
				expectedCollectedDataEpoch = collectedDataEpoch,
				currentBootId = clockDomainId,
				currentElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
				currentWallTimeMs = updatedAtMs,
				retentionSnapshot = retentionSnapshot,
			)
		) {
			"LIVE_AMBIENT retention authority changed before provider reservation"
		}
		return demands
	}

	// One captured registration request is published under its original Room transaction.
	@Suppress("LongParameterList")
	private suspend fun settleProviderReservation(
		source: SourceKind,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
		providerResidency: String,
		collectedDataEpoch: Long,
		clockDomainId: String,
		processIncarnationId: String,
		ownerScope: String,
		demands: List<SourceDemandEntity>,
	): SourceRegistration {
		val dao = database.sourceRegistrationStateDao()
		val current = dao.get(source.stableCode, ownerScope)
		val currentPhysical = current?.let { state ->
			database.sourceBrokerDao().registration(state.sourceKind, state.registrationGeneration)
		}
		return if (current.canReuseProviderReservation(
				currentPhysical, clockDomainId, collectedDataEpoch, providerResidency,
				processIncarnationId, physicalConfigurationFingerprint,
			)
		) {
			updateReusableProviderRegistration(
				source, requireNotNull(current), requireNotNull(currentPhysical), appliedRevision,
				physicalConfigurationFingerprint, updatedAtMs, updatedElapsedRealtimeNanos, demands,
			)
		} else {
			reserveFreshProviderRegistration(
				source, current, appliedRevision, physicalConfigurationFingerprint, updatedAtMs,
				updatedElapsedRealtimeNanos, providerResidency, collectedDataEpoch, clockDomainId,
				processIncarnationId, ownerScope, demands,
			)
		}
	}

	// The saved pointer has already matched the exact boot, epoch and reusable physical owner.
	@Suppress("LongParameterList")
	private suspend fun updateReusableProviderRegistration(
		source: SourceKind,
		current: SourceRegistrationStateEntity,
		currentPhysical: ProviderRegistrationGenerationEntity,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
		demands: List<SourceDemandEntity>,
	): SourceRegistration {
		val dao = database.sourceRegistrationStateDao()
		val reused = current.copy(appliedRevision = appliedRevision, updatedAtMs = updatedAtMs)
		dao.replace(reused)
		val authorization = appendAuthorizationIfChanged(
			source = source,
			registrationGeneration = current.registrationGeneration,
			demands = demands,
			bootId = current.clockDomainId,
			elapsedRealtimeNanos = updatedElapsedRealtimeNanos,
			wallTimeMs = updatedAtMs,
		)
		return SourceRegistration(
			ownerScope = current.ownerScope,
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

	// Preserve the original generation allocation, pointer construction, physical row and authorization publication.
	@Suppress("LongParameterList")
	private suspend fun reserveFreshProviderRegistration(
		source: SourceKind,
		current: SourceRegistrationStateEntity?,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
		providerResidency: String,
		collectedDataEpoch: Long,
		clockDomainId: String,
		processIncarnationId: String,
		ownerScope: String,
		demands: List<SourceDemandEntity>,
	): SourceRegistration {
		val registrationGeneration = database.sourceBrokerDao().maximumRegistrationGeneration(source.stableCode) + 1L
		val next = nextSourceRegistrationState(
			source, current, ownerScope, registrationGeneration, appliedRevision, collectedDataEpoch,
			clockDomainId, updatedAtMs,
		)
		insertReservedPhysicalProvider(
			next, physicalConfigurationFingerprint, providerResidency, processIncarnationId,
			updatedAtMs, updatedElapsedRealtimeNanos,
		)
		val authorization = appendAuthorizationIfChanged(
			source = source,
			registrationGeneration = next.registrationGeneration,
			demands = demands,
			bootId = clockDomainId,
			elapsedRealtimeNanos = updatedElapsedRealtimeNanos,
			wallTimeMs = updatedAtMs,
		)
		return SourceRegistration(
			ownerScope,
			next,
			physicalConfigurationFingerprint,
			authorization,
			requiresProviderAcceptance = true,
			predecessorState = current,
		)
	}

	private suspend fun insertReservedPhysicalProvider(
		next: SourceRegistrationStateEntity,
		physicalConfigurationFingerprint: String,
		providerResidency: String,
		processIncarnationId: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
	) {
		database.sourceBrokerDao().insertRegistration(
			ProviderRegistrationGenerationEntity(
				sourceKind = next.sourceKind,
				registrationGeneration = next.registrationGeneration,
				sourceInstanceId = next.sourceInstanceId,
				ownerScope = next.ownerScope,
				clockDomainId = next.clockDomainId,
				physicalConfigurationFingerprint = physicalConfigurationFingerprint,
				collectedDataEpoch = next.collectedDataEpoch,
				providerResidency = providerResidency,
				providerProcessIncarnationId = processIncarnationId.takeIf {
					providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND
				},
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
	}

	// Refresh revalidates the captured complete demand set without acquiring new retention authority inside Room.
	@Suppress("LongParameterList")
	private suspend fun readCurrentAuthorizationRefreshDemands(
		source: SourceKind,
		expectedOwnerScope: String,
		purposeEligibilityMask: Long,
		observedDemands: List<SourceDemandEntity>,
		retentionSnapshot: LiveAmbientRetentionSnapshot?,
		collectedDataEpoch: Long,
		clockDomainId: String,
		updatedElapsedRealtimeNanos: Long,
		updatedAtMs: Long,
	): List<SourceDemandEntity>? {
		val demands = SourceProviderPurposeScope.selectDemands(
			source.stableCode,
			expectedOwnerScope,
			database.sourceBrokerDao().authorizationDemands(source.stableCode),
		)
		if (demands != observedDemands || demands.isEmpty() ||
			(SourceBrokerAuthorization.purposeMask(demands) and purposeEligibilityMask) == 0L ||
			!supportsExactDemandPurposes(source, demands)
		) {
			return null
		}
		return if (retentionSnapshot == null ||
			!sourceBroker.areLiveAmbientDemandsCurrentInTransaction(
				demands = demands,
				expectedCollectedDataEpoch = collectedDataEpoch,
				currentBootId = clockDomainId,
				currentElapsedRealtimeNanos = updatedElapsedRealtimeNanos,
				currentWallTimeMs = updatedAtMs,
				retentionSnapshot = retentionSnapshot,
			)
		) {
			null
		} else {
			demands
		}
	}

	// Publish only the caller's exact active physical owner; refresh never reserves a replacement.
	@Suppress("LongParameterList")
	private suspend fun publishRefreshedProviderAuthorization(
		source: SourceKind,
		expectedRegistration: SourceRegistration,
		appliedRevision: Long,
		physicalConfigurationFingerprint: String,
		updatedAtMs: Long,
		updatedElapsedRealtimeNanos: Long,
		providerResidency: String,
		collectedDataEpoch: Long,
		clockDomainId: String,
		processIncarnationId: String,
		demands: List<SourceDemandEntity>,
	): SourceRegistration? {
		val brokerDao = database.sourceBrokerDao()
		val ownerScope = expectedRegistration.ownerScope
		val dao = database.sourceRegistrationStateDao()
		val current = dao.get(source.stableCode, ownerScope) ?: return null
		val currentPhysical = brokerDao.registration(
			current.sourceKind,
			current.registrationGeneration,
		) ?: return null
		if (!current.matchesRefreshedRegistrationIdentity(expectedRegistration.state, clockDomainId, collectedDataEpoch) ||
			!currentPhysical.matchesRefreshedRegistrationOwner(
				expectedRegistration, providerResidency, processIncarnationId, physicalConfigurationFingerprint,
			)
		) {
			return null
		}

		// The pointer records configuration/progress changes, not periodic liveness checks.
		val refreshed = if (current.appliedRevision == appliedRevision) current else {
			current.copy(appliedRevision = appliedRevision, updatedAtMs = updatedAtMs).also { dao.replace(it) }
		}
		val authorization = appendAuthorizationIfChanged(
			source = source,
			registrationGeneration = current.registrationGeneration,
			demands = demands,
			bootId = clockDomainId,
			elapsedRealtimeNanos = updatedElapsedRealtimeNanos,
			wallTimeMs = updatedAtMs,
		)
		return SourceRegistration(
			ownerScope = ownerScope,
			state = refreshed,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
			authorization = authorization,
			requiresProviderAcceptance = false, predecessorState = current,
			providerAcceptedElapsedRealtimeNanos = requireNotNull(currentPhysical.acceptedElapsedRealtimeNanos),
		)
	}

	suspend fun markAccepted(
		registration: SourceRegistration,
		acceptedAtMs: Long,
		acceptedElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
	): ProviderRegistrationGenerationEntity? {
		if (!registration.requiresProviderAcceptance) {
			return null
		}
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
				"Pressure provider acceptance requires recording demand"
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


	/** Rebinds a queued radio callback to the saved receive-time header without changing its owner. */
	internal suspend fun radioCallbackAuthorizationAtReceipt(
		expected: SourceRegistration,
		receivedElapsedRealtimeNanos: Long,
	): SourceRegistration? {
		val source = expected.state.sourceKind
		if (source !in setOf(SourceKind.WIFI.stableCode, SourceKind.CELL.stableCode) ||
			receivedElapsedRealtimeNanos < 0L || lifecycleStore.snapshot().epoch != expected.state.collectedDataEpoch ||
			clockDomainProvider.current() != expected.state.clockDomainId) {
			return null
		}
		val process = processIncarnationIdProvider.current()
		return database.withTransaction {
			readRadioCallbackAuthorizationAtReceipt(expected, receivedElapsedRealtimeNanos, process)
		}
	}

	// Saved/current authorization failures remain distinct ordered guards inside one Room snapshot.
	@Suppress("ReturnCount")
	private suspend fun readRadioCallbackAuthorizationAtReceipt(
		expected: SourceRegistration, receivedElapsedRealtimeNanos: Long, process: String,
	): SourceRegistration? {
		val source = expected.state.sourceKind
		val broker = database.sourceBrokerDao()
		val state = database.sourceRegistrationStateDao().get(source, expected.ownerScope)
			?: return null
		val physical = broker.registrationAtObservedTime(source, expected.state.registrationGeneration,
			expected.state.sourceInstanceId, expected.state.clockDomainId,
			expected.physicalConfigurationFingerprint, receivedElapsedRealtimeNanos)
			?: return null
		if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != expected.state.collectedDataEpoch ||
			!SourceRegistrationCallbackAuthority.ownsRecordedRadioCallback(state, physical, expected, process)) {
			return null
		}
		val original = boundedCallbackAuthorization(broker.authorizationRevisionBounded(source,
			state.registrationGeneration, expected.authorization.authorizationRevision,
			MAX_RADIO_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS + 1)) ?: return null
		if (!original.sameAuthorizationAs(expected.authorization)) {
			return null
		}
		val received = boundedCallbackAuthorization(broker.authorizationAtBounded(source,
			state.registrationGeneration, state.clockDomainId, receivedElapsedRealtimeNanos,
			MAX_RADIO_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS + 1)) ?: return null
		if (received.authorizationRevision < original.authorizationRevision ||
			received.effectiveBootId != state.clockDomainId) {
			return null
		}
		return expected.copy(authorization = received)
	}

	/** Read-only reuse of publication checks; it grants no callback acknowledgement. */
	internal suspend fun isCurrentRadioSessionCaptureCallbackOwner(expectedRegistration: SourceRegistration): Boolean =
		radioSessionCaptureCallbackBarrier(expectedRegistration, publish = false)

	/** Called after the shared runtime drains the finite callback prefix following session removal. */
	internal suspend fun publishRadioSessionCaptureCallbackBarrier(expectedRegistration: SourceRegistration): Boolean =
		radioSessionCaptureCallbackBarrier(expectedRegistration, publish = true)

	private suspend fun radioSessionCaptureCallbackBarrier(
		expectedRegistration: SourceRegistration,
		publish: Boolean,
	): Boolean {
		val source = expectedRegistration.state.sourceKind
		require(source in setOf(SourceKind.WIFI.stableCode, SourceKind.CELL.stableCode))
		val epoch = expectedRegistration.state.collectedDataEpoch
		if (lifecycleStore.snapshot().epoch != epoch) {
			return false
		}
		val process = processIncarnationIdProvider.current()
		val boot = clockDomainProvider.current()
		return database.withTransaction {
			val broker = database.sourceBrokerDao()
			val state = database.sourceRegistrationStateDao().get(source, expectedRegistration.ownerScope)
				?: return@withTransaction false
			val physical = broker.registration(source, state.registrationGeneration) ?: return@withTransaction false
			if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != epoch ||
				!SourceRegistrationCallbackAuthority.ownsLiveRadioCallback(
					state, physical, expectedRegistration, epoch, boot, process)) {
				return@withTransaction false
			}
			val authorization = currentRadioSessionCaptureAuthorization(expectedRegistration, state, boot)
				?: return@withTransaction false
			if (physical.captureCallbackBarrierAuthorizationRevision > authorization.authorizationRevision) {
				return@withTransaction false
			}
			val lastCapture = broker.maximumRadioSessionCaptureAuthorizationRevision(source,
                state.registrationGeneration)
			val proposed = physical.copy(captureCallbackBarrierAuthorizationRevision =
                authorization.authorizationRevision)
			if (!database.hasExactRadioSessionCaptureCallbackBarrier(proposed, authorization, lastCapture)) {
				return@withTransaction false
			}
			if (!publish) {
				return@withTransaction true
			}
			publishRadioSessionBarrier(state, authorization)
		}
	}

	private suspend fun publishRadioSessionBarrier(
		state: SourceRegistrationStateEntity, authorization: SourceAuthorizationSnapshot,
	): Boolean {
		val source = state.sourceKind
		val broker = database.sourceBrokerDao()
		if (broker.acknowledgeRadioSessionCaptureCallbackBarrier(source, state.registrationGeneration,
				state.sourceInstanceId, authorization.authorizationRevision) != 1) {
			return false
		}
		val acknowledged = broker.registration(source, state.registrationGeneration) ?: return false
		return acknowledged.captureCallbackBarrierAuthorizationRevision == authorization.authorizationRevision
	}

	/** The session boundary uses current bounded radio rows before proposing its prefix acknowledgement. */
	private suspend fun currentRadioSessionCaptureAuthorization(
		expected: SourceRegistration,
		state: SourceRegistrationStateEntity,
		boot: String,
	): SourceAuthorizationSnapshot? {
		val source = expected.state.sourceKind
		val rows = database.sourceBrokerDao().latestRadioAuthorizationBounded(
			source, state.registrationGeneration, MAX_RADIO_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS + 1)
		val authorization = boundedCallbackAuthorization(rows) ?: return null
		if (!SourceRegistrationCallbackAuthority.matchesNoncaptureAuthorization(expected, authorization, boot)) {
			return null
		}
		val demands = if (source == SourceKind.WIFI.stableCode) {
			database.wifiCapturedFactDao().activeDemandsForDeletion(source, MAX_RADIO_CALLBACK_BARRIER_DEMANDS + 1)
		} else {
			database.cellCapturedFactDao().activeCellDemandsForDeletion(source, MAX_RADIO_CALLBACK_BARRIER_DEMANDS + 1)
		}
		if (demands.size > MAX_RADIO_CALLBACK_BARRIER_DEMANDS ||
			!authenticateRadioAmbientCallbackDemands(source, demands, authorization)) {
			return null
		}
		val expectedRows = reconstructedCallbackAuthorization(source, state.registrationGeneration, authorization, demands)
		return authorization.takeIf {
			rows.sortedBy { row -> row.memberId } == expectedRows?.sortedBy { row -> row.memberId }
		}
	}

	/**
	 * Called only after Location's FIFO has settled the prefix captured before session removal.
	 * The complete current authorization and exact physical owner are checked in one transaction;
	 * all-day members remain active and no admission or retention authority is changed.
	 */
	internal suspend fun publishLocationSessionCaptureCallbackBarrier(
		expectedRegistration: SourceRegistration,
	): Boolean {
		require(expectedRegistration.state.sourceKind == SourceKind.LOCATION.stableCode)
		val expectedEpoch = expectedRegistration.state.collectedDataEpoch
		if (lifecycleStore.snapshot().epoch != expectedEpoch) {
			return false
		}
		val currentProcess = processIncarnationIdProvider.current()
		val currentBoot = clockDomainProvider.current()
		return database.withTransaction {
			val broker = database.sourceBrokerDao()
			val state = database.sourceRegistrationStateDao().get(
				SourceKind.LOCATION.stableCode, expectedRegistration.ownerScope,
			) ?: return@withTransaction false
			val physical = broker.registration(SourceKind.LOCATION.stableCode,
				expectedRegistration.state.registrationGeneration) ?: return@withTransaction false
			if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != expectedEpoch ||
				!SourceRegistrationCallbackAuthority.ownsLiveLocationCallback(
					state, physical, expectedRegistration, expectedEpoch, currentBoot, currentProcess)) {
				return@withTransaction false
			}
			val rows = broker.latestAuthorization(SourceKind.LOCATION.stableCode, state.registrationGeneration)
			val authorization = try {
				rows.toAuthorizationSnapshotOrNull()
			} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
				null
			} ?: return@withTransaction false
			if (!SourceRegistrationCallbackAuthority.matchesNoncaptureAuthorization(
					expectedRegistration, authorization, currentBoot)) {
				return@withTransaction false
			}
			val demands = SourceProviderPurposeScope.selectDemands(SourceKind.LOCATION.stableCode,
				expectedRegistration.ownerScope, broker.authorizationDemands(SourceKind.LOCATION.stableCode))
			val expectedRows = SourceBrokerAuthorization.rows(SourceKind.LOCATION.stableCode,
				state.registrationGeneration, authorization.authorizationRevision, demands,
				authorization.effectiveBootId, authorization.effectiveElapsedRealtimeNanos,
				rows.first().effectiveWallTimeMs)
			if (rows.sortedBy { it.memberId } != expectedRows.sortedBy { it.memberId }) {
				return@withTransaction false
			}
			broker.acknowledgeLocationSessionCaptureCallbackBarrier(state.registrationGeneration,
				state.sourceInstanceId, authorization.authorizationRevision) == 1
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
				CellCaptureCallbackBarrierBlockedReason.STALE_LIFECYCLE)
		}
		val currentProcessId = processIncarnationIdProvider.current()
		return database.withTransaction {
			val state = database.sourceRegistrationStateDao().get(
				SourceKind.CELL.stableCode, expectedRegistration.ownerScope)
			val physical = database.sourceBrokerDao().registration(
				SourceKind.CELL.stableCode, expectedRegistration.state.registrationGeneration)
			if (!SourceRegistrationCallbackAuthority.ownsProcessBoundRadioCallback(
					state, physical, expectedRegistration, expectedCollectedDataEpoch, currentProcessId)) {
				return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION)
			}
			val (authorizationRevision, authorization) = currentRadioCallbackAuthorization(expectedRegistration)
				?: return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE)
			val demands = currentRadioCallbackDemands(expectedRegistration, authorization, authorizationRevision)
				?: return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE)
			if (authorization.authorizedMembers.any { member ->
					member.persistenceEligible && member.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
				return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE)
			}
			if (!authenticateRadioAmbientCallbackDemands(SourceKind.CELL.stableCode, demands, authorization)) {
				return@withTransaction CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE)
			}
			settleRadioCaptureCallbackBarrier(expectedRegistration, requireNotNull(physical), authorization,
				unverifiable = { CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE) },
				stale = { CellCaptureCallbackBarrierPublication.Blocked(
					CellCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION) },
				established = { CellCaptureCallbackBarrierPublication.Established(it) })
		}
	}

	/** The surviving Cell Ambient purpose keeps its own current policy and consent authority. */
	@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
	private suspend fun authenticateRadioAmbientCallbackDemands(
		sourceKind: Int,
		demands: List<SourceDemandEntity>,
		authorization: SourceAuthorizationSnapshot,
	): Boolean {
		val ambient = demands.filter { it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		if (ambient.isEmpty()) {
			return true
		}
		val policies = database.sourcePolicyDao()
		val authority = policies.authority()?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		} ?: return false
		val current = policies.policyAtRevision(authority.currentPolicyRevision, sourceKind)
			?: return false
		val consent = policies.latestConsentEpoch(sourceKind, SourceBrokerPurpose.AMBIENT_PRODUCT)
		if (!current.hasExactEligibleAmbientConsentReference(consent)) {
			return false
		}
		return ambient.all { demand ->
			val historical = policies.policyAtRevision(demand.sourcePolicyRevision, sourceKind)
				?: return@all false
			demand.isLiveSessionlessRadioAmbientDemand(sourceKind) &&
				demand.hasExactRadioAmbientCallbackGrant(current, historical, consent, authorization)
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
				WifiCaptureCallbackBarrierBlockedReason.STALE_LIFECYCLE)
		}
		val currentProcessId = processIncarnationIdProvider.current()
		return database.withTransaction {
			val state = database.sourceRegistrationStateDao().get(
				SourceKind.WIFI.stableCode, expectedRegistration.ownerScope)
			val physical = database.sourceBrokerDao().registration(
				SourceKind.WIFI.stableCode, expectedRegistration.state.registrationGeneration)
			if (!SourceRegistrationCallbackAuthority.ownsProcessBoundRadioCallback(
					state, physical, expectedRegistration, expectedCollectedDataEpoch, currentProcessId)) {
				return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION)
			}
			val (authorizationRevision, authorization) = currentRadioCallbackAuthorization(expectedRegistration)
				?: return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE)
			if (currentRadioCallbackDemands(expectedRegistration, authorization, authorizationRevision) == null) {
				return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE)
			}
			if (authorization.authorizedMembers.any { member ->
					member.persistenceEligible && member.purpose == SourceBrokerPurpose.SESSION_CAPTURE }) {
				return@withTransaction WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.CAPTURE_AUTHORIZATION_ACTIVE)
			}
			settleRadioCaptureCallbackBarrier(expectedRegistration, requireNotNull(physical), authorization,
				unverifiable = { WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.AUTHORIZATION_UNVERIFIABLE) },
				stale = { WifiCaptureCallbackBarrierPublication.Blocked(
					WifiCaptureCallbackBarrierBlockedReason.STALE_REGISTRATION) },
				established = { WifiCaptureCallbackBarrierPublication.Established(it) })
		}
	}

	/** Reads the same source-local bounded snapshot used by both radio callback barriers. */
	private suspend fun currentRadioCallbackAuthorization(
		expected: SourceRegistration,
	): Pair<Long, SourceAuthorizationSnapshot>? {
		val source = expected.state.sourceKind
		val generation = expected.state.registrationGeneration
		val latestRevision = if (source == SourceKind.WIFI.stableCode) {
			database.wifiCapturedFactDao().maximumRegistrationAuthorizationRevision(source, generation)
		} else {
			database.cellCapturedFactDao().maximumRegistrationAuthorizationRevision(source, generation)
		}
		val rows = if (source == SourceKind.WIFI.stableCode) {
			database.wifiCapturedFactDao().maintenanceAuthorizationMembers(
				source, generation, latestRevision, MAX_RADIO_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS + 1)
		} else {
			database.cellCapturedFactDao().maintenanceAuthorizationMembers(
				source, generation, latestRevision, MAX_RADIO_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS + 1)
		}
		val authorization = boundedCallbackAuthorization(rows) ?: return null
		return authorization.takeIf {
			latestRevision > 0L && it.sameAuthorizationAs(expected.authorization)
		}?.let { latestRevision to it }
	}

	/** Reconstructs every active member before the caller checks whether capture is absent. */
	private suspend fun currentRadioCallbackDemands(
		expected: SourceRegistration,
		authorization: SourceAuthorizationSnapshot,
		authorizationRevision: Long,
	): List<SourceDemandEntity>? {
		val source = expected.state.sourceKind
		val demands = if (source == SourceKind.WIFI.stableCode) {
			database.wifiCapturedFactDao().activeDemandsForDeletion(source, MAX_RADIO_CALLBACK_BARRIER_DEMANDS + 1)
		} else {
			database.cellCapturedFactDao().activeCellDemandsForDeletion(source, MAX_RADIO_CALLBACK_BARRIER_DEMANDS + 1)
		}
		val expectedRows = reconstructedCallbackAuthorization(
			source, expected.state.registrationGeneration, authorization, demands, authorizationRevision)
			?: return null
		return demands.takeIf {
			it.size <= MAX_RADIO_CALLBACK_BARRIER_DEMANDS &&
				authorization.members.sortedBy { row -> row.memberId } == expectedRows.sortedBy { row -> row.memberId }
		}
	}

	/** Acknowledges the same immutable capture frontier and verifies the saved revision. */
	private suspend fun <Publication> settleRadioCaptureCallbackBarrier(
		expected: SourceRegistration,
		physical: ProviderRegistrationGenerationEntity,
		authorization: SourceAuthorizationSnapshot,
		unverifiable: () -> Publication,
		stale: () -> Publication,
		established: (Long) -> Publication,
	): Publication {
		val broker = database.sourceBrokerDao()
		val source = expected.state.sourceKind
		val generation = expected.state.registrationGeneration
		val maximumCaptureRevision = broker.maximumCaptureAuthorizationRevision(source, generation)
		if (physical.captureCallbackBarrierAuthorizationRevision > maximumCaptureRevision &&
			!database.hasExactRadioSessionCaptureCallbackBarrier(physical, authorization, maximumCaptureRevision)) {
			return unverifiable()
		}
		if (broker.acknowledgeCaptureCallbackBarrier(source, generation,
				expected.state.sourceInstanceId, maximumCaptureRevision) != 1) {
			return stale()
		}
		val acknowledged = broker.registration(source, generation)
		val publishedRevision = maxOf(physical.captureCallbackBarrierAuthorizationRevision, maximumCaptureRevision)
		return if (acknowledged?.captureCallbackBarrierAuthorizationRevision != publishedRevision) {
			stale()
		} else {
			established(publishedRevision)
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
		val physical = database.sourceBrokerDao().registration(
			registration.state.sourceKind, registration.state.registrationGeneration)
		if (physical?.providerResidency ==
			ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE) {
			return database.sourceBrokerDao().failUnacceptedSystemLocationReservation(
				registration.state.registrationGeneration, registration.state.sourceInstanceId,
				failedAtMs, failedElapsedRealtimeNanos, failureCode) == 1
		}
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
		val brokerDao = database.sourceBrokerDao()
		val physical = checkNotNull(brokerDao.registration(registration.state.sourceKind,
			registration.state.registrationGeneration))
		check(physical.sourceInstanceId == registration.state.sourceInstanceId)
		val retiring = if (physical.providerResidency ==
			ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE) {
			brokerDao.markSystemLocationRetiring(registration.state.registrationGeneration,
				registration.state.sourceInstanceId, retiredAtMs, retiredElapsedRealtimeNanos, reason)
			checkNotNull(brokerDao.registration(registration.state.sourceKind,
				registration.state.registrationGeneration)).also {
				check(it.status == ProviderRegistrationGenerationEntity.STATUS_RETIRING)
			}
		} else {
			checkNotNull(
			brokerDao.beginCurrentProcessRegistrationRetirement(
				sourceKind = registration.state.sourceKind,
				registrationGeneration = registration.state.registrationGeneration,
				sourceInstanceId = registration.state.sourceInstanceId,
				currentProcessId = processIncarnationIdProvider.current(),
				retiredAtMs = retiredAtMs,
				retiredElapsedRealtimeNanos = retiredElapsedRealtimeNanos,
				reason = reason,
			),
		) { "Provider registration is not current-process retirement eligible" }
		}
		return retiring.toRetirementToken()
	}

	internal suspend fun completeRetirement(token: SourceRegistrationRetirementToken): Boolean {
		if (token.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_SYSTEM_REARMABLE) {
			val dao = database.sourceBrokerDao()
			return dao.completeSystemLocationRetirement(token.registrationGeneration,
				token.sourceInstanceId.value, token.retiredAtMs, token.retiredElapsedRealtimeNanos) == 1 ||
				dao.registration(token.source.stableCode, token.registrationGeneration)?.status ==
					ProviderRegistrationGenerationEntity.STATUS_RETIRED
		}
		val currentProcessId = processIncarnationIdProvider.current()
		if (token.processIncarnationId != currentProcessId) {
			return false
		}
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
		val process = database.sourceBrokerDao().pendingCurrentProcessProviderRemovals(
			sourceKind = source.stableCode,
			currentProcessId = processIncarnationIdProvider.current(),
		).map { it.toRetirementToken() }
		val system = if (source == SourceKind.LOCATION) {
			database.sourceBrokerDao().nonterminalSystemLocationRegistrations(2)
				.filter { it.status == ProviderRegistrationGenerationEntity.STATUS_RETIRING }
				.map { it.toRetirementToken() }
		} else {
			emptyList()
		}
		return process + system
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
		check(status == ProviderRegistrationGenerationEntity.STATUS_RETIRING)
		return SourceRegistrationRetirementToken(
			source = SourceKind.entries.single { it.stableCode == sourceKind },
			sourceInstanceId = SourceInstanceId(sourceInstanceId),
			registrationGeneration = registrationGeneration,
			processIncarnationId = providerProcessIncarnationId,
			retiredAtMs = requireNotNull(retiredAtMs),
			retiredElapsedRealtimeNanos = requireNotNull(retiredElapsedRealtimeNanos),
			reason = failureCode,
			providerResidency = providerResidency,
			clockDomainId = clockDomainId,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
			collectedDataEpoch = collectedDataEpoch,
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
		) {
			return latest
		}
		val revision = dao.maximumAuthorizationRevision(source.stableCode) + 1L
		val boundary = sourceAuthorizationPublicationBoundary(demands, latest, bootId,
			elapsedRealtimeNanos, wallTimeMs)
		val rows = SourceBrokerAuthorization.rows(
			source.stableCode,
			registrationGeneration,
			revision,
			demands,
			bootId,
			boundary.elapsedRealtimeNanos,
			boundary.wallTimeMs,
		)
		dao.insertAuthorizations(rows)
		return requireNotNull(rows.toAuthorizationSnapshotOrNull())
	}

	private fun supportsExactDemandPurposes(
		source: SourceKind,
		demands: List<SourceDemandEntity>,
	): Boolean = source != SourceKind.PRESSURE || (
		demands.isNotEmpty() && demands.all { demand ->
			demand.purpose in setOf(SourceBrokerPurpose.SESSION_CAPTURE, SourceBrokerPurpose.AMBIENT_PRODUCT)
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

	/**
	 * Saves provider and admission progress transactionally for this exact registration; updatedAtMs is
	 * UTC milliseconds.
	 */
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
	): SourceRuntimeStateSaveResult = try {
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
			saveMergedRuntimeCheckpoint(registration, incoming)
			terminalCompleteness?.let { candidate ->
				saveTerminalRuntimeCompleteness(registration, candidate, terminalStepsCountDomainEvidence)
			}
			SourceRuntimeStateSaveResult.Saved
		}
	} catch (_: StoredCompletenessUnverifiableException) {
		SourceRuntimeStateSaveResult.Unverifiable
	}
	/** Keeps the existing sensor checkpoint merge and identity check before the durable save. */
	private suspend fun saveMergedRuntimeCheckpoint(
		registration: SourceRegistration,
		incoming: SourceRuntimeStateEntity,
	) {
		val dao = database.sourceRuntimeStateDao()
		val stored = if (incoming.stateVersion == SENSOR_RUNTIME_CHECKPOINT_VERSION) {
			mergeSensorRuntimeStates(
				current = dao.get(incoming.sourceKind, incoming.ownerScope),
				incoming = incoming,
				legacyComponentStateVersion = incoming.stateVersion)
		} else {
			incoming
		}
		check(stored.sourceInstanceId == registration.state.sourceInstanceId &&
			stored.clockDomainId == registration.state.clockDomainId &&
			stored.registrationGeneration == registration.state.registrationGeneration) {
			"Runtime checkpoint was superseded by an incompatible registration"
		}
		dao.save(stored)
	}

	/** Saves terminal source completeness in the same transaction as its runtime checkpoint. */
	private suspend fun saveTerminalRuntimeCompleteness(
		registration: SourceRegistration,
		candidate: SourceSessionCompletenessEntity,
		countDomainEvidence: StepsCountDomainRetirementEvidence?,
	) {
		check(candidate.sourceKind == registration.state.sourceKind)
		check(candidate.sourceInstanceId == registration.state.sourceInstanceId)
		check(candidate.registrationGeneration == registration.state.registrationGeneration)
		if (candidate.updatedAtMs !in 0L until Long.MAX_VALUE) {
			throw StoredCompletenessUnverifiableException()
		}
		val countDomainStore = StepsCountDomainStore(database)
		val completeness = monotonicTerminalStepsCompleteness(candidate, countDomainStore)
		database.sourceSessionDao().saveCompleteness(completeness)
		if (completeness.sourceKind == SourceKind.STEPS.stableCode) {
			recordTerminalStepsCountDomain(completeness, countDomainStore, countDomainEvidence)
		}
	}

	private suspend fun monotonicTerminalStepsCompleteness(
		candidate: SourceSessionCompletenessEntity,
		countDomainStore: StepsCountDomainStore,
	): SourceSessionCompletenessEntity {
		if (candidate.sourceKind != SourceKind.STEPS.stableCode || !countDomainStore.isInstalled()) {
			return candidate
		}
		val existing = loadExactStepsCompletenessPredecessor(candidate)
		return try {
			candidate.withMonotonicStepsCountDomainRevision(existing)
		} catch (_: IllegalArgumentException) {
			throw StoredCompletenessUnverifiableException()
		}
	}

	private suspend fun loadExactStepsCompletenessPredecessor(
		candidate: SourceSessionCompletenessEntity,
	): SourceSessionCompletenessEntity? {
		val rawExisting = database.sourceSessionDao().rawSourceCompletenessForServiceRun(
			serviceRunId = candidate.serviceRunId,
			sourceKind = SourceKind.STEPS.stableCode,
			limit = MAX_STEPS_COMPLETENESS_ROWS + 1)
		if (rawExisting.size > MAX_STEPS_COMPLETENESS_ROWS) {
			throw StoredCompletenessUnverifiableException()
		}
		val existingRows = rawExisting.map { raw ->
			requireStepsCompletenessRun(raw.validatedOrNull(), candidate)
		}
		val exactRows = existingRows.filter {
			it.sourceInstanceId == candidate.sourceInstanceId &&
				it.registrationGeneration == candidate.registrationGeneration
		}
		if (exactRows.size > 1) {
			throw StoredCompletenessUnverifiableException()
		}
		return exactRows.singleOrNull()
	}

	private suspend fun recordTerminalStepsCountDomain(
		completeness: SourceSessionCompletenessEntity,
		countDomainStore: StepsCountDomainStore,
		countDomainEvidence: StepsCountDomainRetirementEvidence?,
	) {
		val result = countDomainStore.recordSessionCompleteness(completeness,
			countDomainEvidence ?: StepsCountDomainRetirementEvidence(
				providerFlushOutcome = "UNOBSERVABLE", registrationRemovalOutcome = "UNOBSERVABLE"))
		if (result !in setOf(StepsCountDomainWriteResult.INSERTED, StepsCountDomainWriteResult.EXACT_REPLAY,
				StepsCountDomainWriteResult.SCHEMA_UNAVAILABLE)) {
			throw StoredCompletenessUnverifiableException()
		}
		if (result == StepsCountDomainWriteResult.INSERTED) {
			publishStepsCountDomainEvidenceRevisionAtWallTime(database, completeness.updatedAtMs)
		}
	}

}

/** Authenticates immutable passive request bytes; callers retain their own request-selection rules. */
private fun LocationRegistrationPlanEntity.passiveLocationPlanOrNull(
	expectedSourceInstanceId: String,
	expectedFingerprint: String,
	matchesRequest: (LocationPlan) -> Boolean = { true },
): LocationPlan? {
	if (sourceInstanceId != expectedSourceInstanceId ||
		physicalConfigurationFingerprint != expectedFingerprint ||
		payloadVersion != PASSIVE_LOCATION_PLAN_PAYLOAD_VERSION ||
		LocationRegistrationPlanIntegrity.checksum(this) != effectChecksum
	) {
		return null
	}
	val plan = runCatching { SourcePlanCodec().decode(payload) as? LocationPlan }.getOrNull()
		?: return null
	return plan.takeIf { isCanonicalPassivePlan(it, expectedFingerprint, matchesRequest) }
}

private fun LocationRegistrationPlanEntity.isCanonicalPassivePlan(
	plan: LocationPlan, expectedFingerprint: String, matchesRequest: (LocationPlan) -> Boolean,
): Boolean {
	val encoded = runCatching { SourcePlanCodec().encode(plan) }.getOrNull()
		?: return false
	return plan.passiveSystemDelivery && matchesRequest(plan) &&
		encoded.formatVersion == PASSIVE_LOCATION_PLAN_PAYLOAD_VERSION &&
		encoded.bytes.contentEquals(payload) && encoded.checksum == payloadChecksum &&
		plan.physicalConfigurationFingerprint() == expectedFingerprint
}

private fun SourceAuthorizationSnapshot.sameAuthorizationAs(
	other: SourceAuthorizationSnapshot,
): Boolean = authorizationRevision == other.authorizationRevision &&
	authorizationFingerprint == other.authorizationFingerprint &&
	purposeEligibilityMask == other.purposeEligibilityMask &&
	effectiveBootId == other.effectiveBootId &&
	effectiveElapsedRealtimeNanos == other.effectiveElapsedRealtimeNanos &&
	members.sortedBy { row -> row.memberId } == other.members.sortedBy { row -> row.memberId }

private const val PASSIVE_LOCATION_PLAN_PAYLOAD_VERSION = 4
private const val MAX_RADIO_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS = 64
private const val MAX_RADIO_CALLBACK_BARRIER_DEMANDS = 256
private const val MAX_STEPS_COMPLETENESS_ROWS = 64

private class StoredCompletenessUnverifiableException :
	IllegalStateException("Stored Steps completeness is unverifiable")


/** Pure identity checks shared only where the existing radio predicates are identical. */
// These pure predicates describe the one callback identity protocol; method complexity checks stay enabled.
@Suppress("TooManyFunctions")
private object SourceRegistrationCallbackAuthority {
	/** Verifies the callback identity and complete demand references before radio item qualification. */
	fun matchesRadioEvidence(
		registration: SourceRegistration,
		evidence: SourceEvidenceCandidate<*>,
		ids: List<String>,
	): Boolean {
		val snapshot = registration.authorization
		if (!hasExactDemandIds(ids, snapshot)) {
			return false
		}
		return snapshot.effectiveBootId == registration.state.clockDomainId &&
			sameObservedSource(registration, evidence) && sameObservedAuthorization(registration, evidence) &&
			evidence.physicalConfigurationFingerprint == registration.physicalConfigurationFingerprint
	}

	private fun hasExactDemandIds(ids: List<String>, snapshot: SourceAuthorizationSnapshot): Boolean =
		ids.isNotEmpty() && ids.size <= MAX_RADIO_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS &&
			ids.size == snapshot.authorizedMembers.size && ids.distinct().size == ids.size

	private fun sameObservedSource(registration: SourceRegistration, evidence: SourceEvidenceCandidate<*>): Boolean =
		evidence.source.stableCode == registration.state.sourceKind &&
			evidence.sourceInstanceId.value == registration.state.sourceInstanceId &&
			evidence.registrationGeneration == registration.state.registrationGeneration &&
			evidence.clockDomainId == registration.state.clockDomainId

	private fun sameObservedAuthorization(
		registration: SourceRegistration,
		evidence: SourceEvidenceCandidate<*>,
	): Boolean = evidence.capturedCollectedDataEpoch == registration.state.collectedDataEpoch &&
		evidence.authorizationRevision == registration.authorization.authorizationRevision &&
		evidence.registrationPurposeEligibilityMask == registration.authorization.purposeEligibilityMask &&
		evidence.registrationEligibilityFingerprint == registration.authorization.authorizationFingerprint

	/** Matches the caller's complete snapshot while session capture has been removed. */
	fun matchesNoncaptureAuthorization(
		expected: SourceRegistration,
		authorization: SourceAuthorizationSnapshot,
		boot: String,
	): Boolean = authorization.sameAuthorizationAs(expected.authorization) && authorization.effectiveBootId == boot &&
		authorization.authorizedMembers.none { member ->
			member.persistenceEligible && member.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}

	/** Checks current boot, data epoch and the exact process owner for a radio session boundary. */
	fun ownsLiveRadioCallback(
		state: SourceRegistrationStateEntity,
		physical: ProviderRegistrationGenerationEntity,
		expected: SourceRegistration,
		epoch: Long,
		boot: String,
		processId: String,
	): Boolean = sameLiveRadioState(state, expected.state, epoch, boot) &&
		sameLiveRadioProvider(physical, state, expected, epoch, boot, processId)

	private fun sameLiveRadioState(
		state: SourceRegistrationStateEntity,
		expected: SourceRegistrationStateEntity,
		epoch: Long,
		boot: String,
	): Boolean {
		if (state.registrationGeneration != expected.registrationGeneration ||
			state.sourceInstanceId != expected.sourceInstanceId || state.clockDomainId != expected.clockDomainId ||
			state.clockDomainId != boot) {
			return false
		}
		return state.collectedDataEpoch == epoch
	}

	private fun sameLiveRadioProvider(
		physical: ProviderRegistrationGenerationEntity,
		state: SourceRegistrationStateEntity,
		expected: SourceRegistration,
		epoch: Long,
		boot: String,
		processId: String,
	): Boolean {
		if (physical.collectedDataEpoch != epoch || physical.sourceInstanceId != state.sourceInstanceId ||
			physical.clockDomainId != boot) {
			return false
		}
		return sameOwnerConfiguration(physical, expected) && isActiveInProcess(physical, processId)
	}

	private fun sameOwnerConfiguration(
		physical: ProviderRegistrationGenerationEntity,
		expected: SourceRegistration,
	): Boolean = physical.ownerScope == expected.ownerScope &&
		physical.physicalConfigurationFingerprint == expected.physicalConfigurationFingerprint

	/** Checks the Location session boundary against its current boot, epoch and physical process owner. */
	fun ownsLiveLocationCallback(
		state: SourceRegistrationStateEntity,
		physical: ProviderRegistrationGenerationEntity,
		expected: SourceRegistration,
		epoch: Long,
		boot: String,
		processId: String,
	): Boolean = matchesExpectedState(state, expected.state, epoch) && state.clockDomainId == boot &&
		sameLiveLocationProvider(physical, state, expected, epoch) && isActiveInProcess(physical, processId)

	private fun sameLiveLocationProvider(
		physical: ProviderRegistrationGenerationEntity,
		state: SourceRegistrationStateEntity,
		expected: SourceRegistration,
		epoch: Long,
	): Boolean {
		if (physical.sourceInstanceId != state.sourceInstanceId || physical.ownerScope != expected.ownerScope ||
			physical.clockDomainId != state.clockDomainId || physical.collectedDataEpoch != epoch) {
			return false
		}
		return physical.physicalConfigurationFingerprint == expected.physicalConfigurationFingerprint
	}

	/** The receipt-time query already proves the original generation was physically live then. */
	fun ownsRecordedRadioCallback(state: SourceRegistrationStateEntity,
		physical: ProviderRegistrationGenerationEntity, expected: SourceRegistration, processId: String): Boolean =
		matchesExpectedState(state, expected.state, expected.state.collectedDataEpoch) &&
			matchesExpectedPhysical(physical, expected) && physical.collectedDataEpoch == state.collectedDataEpoch &&
			physical.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
			physical.providerProcessIncarnationId == processId &&
			physical.status in setOf(ProviderRegistrationGenerationEntity.STATUS_ACTIVE,
				ProviderRegistrationGenerationEntity.STATUS_RETIRING, ProviderRegistrationGenerationEntity.STATUS_RETIRED) &&
			physical.acceptedAtMs != null &&
			expected.providerAcceptedElapsedRealtimeNanos?.let { it == physical.acceptedElapsedRealtimeNanos } != false

	/** Requires both saved rows to identify the caller’s active process-bound radio generation. */
	fun ownsProcessBoundRadioCallback(
		state: SourceRegistrationStateEntity?,
		physical: ProviderRegistrationGenerationEntity?,
		expected: SourceRegistration,
		epoch: Long,
		processId: String,
	): Boolean {
		if (state == null || physical == null) {
			return false
		}
		return matchesExpectedState(state, expected.state, epoch) &&
			matchesExpectedPhysical(physical, expected) && physical.collectedDataEpoch == epoch &&
			isActiveInProcess(physical, processId)
	}

	private fun matchesExpectedState(
		state: SourceRegistrationStateEntity,
		expected: SourceRegistrationStateEntity,
		epoch: Long,
	): Boolean = state.sourceInstanceId == expected.sourceInstanceId &&
		state.registrationGeneration == expected.registrationGeneration &&
		state.clockDomainId == expected.clockDomainId && state.collectedDataEpoch == epoch

	private fun matchesExpectedPhysical(
		physical: ProviderRegistrationGenerationEntity,
		expected: SourceRegistration,
	): Boolean = physical.sourceInstanceId == expected.state.sourceInstanceId &&
		physical.ownerScope == expected.ownerScope && physical.clockDomainId == expected.state.clockDomainId &&
		physical.physicalConfigurationFingerprint == expected.physicalConfigurationFingerprint

	private fun isActiveInProcess(
		physical: ProviderRegistrationGenerationEntity,
		processId: String,
	): Boolean = physical.providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
		physical.providerProcessIncarnationId == processId &&
		physical.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE
}

private fun boundedCallbackAuthorization(
	rows: List<SourceAuthorizationEntity>,
): SourceAuthorizationSnapshot? = if (rows.size <= MAX_RADIO_CALLBACK_BARRIER_AUTHORIZATION_MEMBERS) {
	try {
		rows.toAuthorizationSnapshotOrNull()
	} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
		null
	}
} else {
	null
}

private fun reconstructedCallbackAuthorization(
	source: Int,
	generation: Long,
	authorization: SourceAuthorizationSnapshot,
	demands: List<SourceDemandEntity>,
	authorizationRevision: Long = authorization.authorizationRevision,
): List<SourceAuthorizationEntity>? = try {
	SourceBrokerAuthorization.rows(source, generation, authorizationRevision, demands,
		authorization.effectiveBootId, authorization.effectiveElapsedRealtimeNanos,
		authorization.members.first().effectiveWallTimeMs)
} catch (@Suppress("SwallowedException") _: IllegalArgumentException) {
	null
}

private fun qualifyRadioCallbackPayload(
	evidence: SourceEvidenceCandidate<*>,
	authority: RadioPurposeItemAuthority,
): RadioPurposeItemQualification.Qualified? {
	val bytes = DefaultSourcePayloadCodec().encode(evidence.payload, evidence.payloadVersion).bytes
	val qualification = when (evidence.source) {
		SourceKind.WIFI -> {
			val decoded = WifiWalPayloadCodec.decodeCanonical(evidence.payloadVersion, bytes)
				as? WifiWalPayloadDecodeResult.Decoded ?: return null
			RadioPurposeItemQualifier.wifi(decoded.payload, authority, evidence.payloadVersion)
		}
		SourceKind.CELL -> {
			val decoded = CellWalPayloadCodec.decodeCanonical(evidence.payloadVersion, bytes)
				as? CellWalPayloadDecodeResult.Decoded ?: return null
			RadioPurposeItemQualifier.cell(decoded.payload, authority, evidence.payloadVersion)
		}
		else -> return null
	}
	return qualification as? RadioPurposeItemQualification.Qualified
}

private fun radioProviderObservationTimes(evidence: SourceEvidenceCandidate<*>): List<Long?>? =
	when (val payload = evidence.payload) {
		is WifiResultSnapshotPayload -> payload.accessPoints.takeIf { it.size <= MAX_WIFI_WAL_CHILDREN }
			?.map { it.providerTimestampNanos }
		is CellSnapshotPayload -> payload.observations.takeIf { it.size <= MAX_CELL_WAL_CHILDREN }
			?.map { it.providerTimestampNanos }
		else -> null
	}

private fun requireStepsCompletenessRun(
	existing: SourceSessionCompletenessEntity?,
	candidate: SourceSessionCompletenessEntity,
): SourceSessionCompletenessEntity {
	val verified = existing ?: throw StoredCompletenessUnverifiableException()
	if (verified.logicalTrackingId != candidate.logicalTrackingId ||
		verified.serviceRunId != candidate.serviceRunId || verified.sourceKind != SourceKind.STEPS.stableCode) {
		throw StoredCompletenessUnverifiableException()
	}
	return verified
}

private const val MAX_PASSIVE_LOCATION_AUTHORIZATION_MEMBERS = 16
