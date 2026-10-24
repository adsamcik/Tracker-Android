// Keep the durable demand owner and its related value conversions in one source file.
@file:Suppress("TooManyFunctions")

package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.tracker.source.catalog.toRuntimeSourceKind
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.reconcileActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.database.rotateCurrentSourceAuthorizationInTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientLocationRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientStepsRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiRetentionAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.CollectedDataDeletionOperationEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerAuthorization
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.hasReachedRetentionPhase
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.base.time.Clock
import com.adsamcik.tracker.shared.base.time.SystemClock
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityReader
import com.adsamcik.tracker.shared.preferences.retention.UnavailableRetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.tracking.SourceBalanceSettings
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionManifestPurpose
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.TrackingPurpose
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

internal enum class SourceCallerAuthorityRetirementRetryReason {
	STORAGE_UNAVAILABLE,
	COMPARE_AND_SET_FAILED,
	CURRENT_AUTHORITY_CHANGED,
}

internal sealed interface SourceCallerAuthorityRetirementOutcome {
	data object Completed : SourceCallerAuthorityRetirementOutcome
	/** The old receipt remains active solely for its continuing physical Steps recording. */
	data object RetainedForSteps : SourceCallerAuthorityRetirementOutcome

	data class Retryable(
		val reason: SourceCallerAuthorityRetirementRetryReason,
	) : SourceCallerAuthorityRetirementOutcome

	data object TerminalMissing : SourceCallerAuthorityRetirementOutcome
	data object TerminalCorrupt : SourceCallerAuthorityRetirementOutcome
	data object TerminalInvariant : SourceCallerAuthorityRetirementOutcome
	data object TerminalAmbiguous : SourceCallerAuthorityRetirementOutcome
}

/**
 * Durable authority for source consumers and physical registration eligibility.
 *
 * Provider adapters still own Android-specific calls, but they may reserve an identity only from
 * this broker's active demand vector. The vector is persisted before registration and later used to
 * reject callbacks that attempt to cross a purpose, policy, consent, manifest, or generation fence.
 */
// These operations share one Room authority and mutation lease; keep method-level algorithm checks active.
@Suppress("LargeClass")
@Singleton
class SourceBroker @Inject internal constructor(
	private val database: AppDatabase,
	private val trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	private val ambientRadioMutationLeaseGuard: AmbientRadioMutationLeaseGuard,
	private val sourceCallerAuthorityRepository: SourceCallerAcceptedAuthorityRepository,
	private val retentionAuthorityReader: RetentionAuthorityReader,
	private val clock: Clock = SystemClock,
) {
	private val stepsRecordingContinuation = StepsRecordingContinuationReader(database, sourceCallerAuthorityRepository)

	private val callerAuthorityRetirement = CallerAuthorityRetirementOperations(sourceCallerAuthorityRepository)

	private val preparedSessionDemands = PreparedSessionDemandMutations(database) { sourceKind, boundary ->
		rotateCurrentAuthorizationInTransaction(sourceKind, boundary.bootId,
			boundary.elapsedRealtimeNanos, boundary.wallTimeMs)
	}

	constructor(database: AppDatabase) : this(
		database,
		RoomTrackingRolloutStateStore(database),
		RejectingAmbientRadioMutationLeaseGuard,
		RoomSourceCallerAcceptedAuthorityRepository(database),
		UnavailableRetentionAuthorityProducer,
	)

	constructor(
		database: AppDatabase,
		trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	) : this(
		database,
		trackingRolloutStateStore,
		RejectingAmbientRadioMutationLeaseGuard,
		RoomSourceCallerAcceptedAuthorityRepository(database),
		UnavailableRetentionAuthorityProducer,
	)

	internal constructor(
		database: AppDatabase,
		trackingRolloutStateStore: RoomTrackingRolloutStateStore,
		retentionAuthorityReader: RetentionAuthorityReader,
		clock: Clock = SystemClock,
	) : this(
		database,
		trackingRolloutStateStore,
		RejectingAmbientRadioMutationLeaseGuard,
		RoomSourceCallerAcceptedAuthorityRepository(database),
		retentionAuthorityReader,
		clock,
	)

	internal constructor(
		database: AppDatabase,
		trackingRolloutStateStore: RoomTrackingRolloutStateStore,
		ambientRadioMutationLeaseGuard: AmbientRadioMutationLeaseGuard,
	) : this(
		database,
		trackingRolloutStateStore,
		ambientRadioMutationLeaseGuard,
		RoomSourceCallerAcceptedAuthorityRepository(database),
		UnavailableRetentionAuthorityProducer,
	)

	internal constructor(
		database: AppDatabase,
		trackingRolloutStateStore: RoomTrackingRolloutStateStore,
		ambientRadioMutationLeaseGuard: AmbientRadioMutationLeaseGuard,
		retentionAuthorityReader: RetentionAuthorityReader,
	) : this(
		database,
		trackingRolloutStateStore,
		ambientRadioMutationLeaseGuard,
		RoomSourceCallerAcceptedAuthorityRepository(database),
		retentionAuthorityReader,
	)
	fun sessionConsumerId(logicalTrackingId: String): String = sourceSessionConsumerId(logicalTrackingId)

	/** Reuses the store's actual lane/catalog/owner checks for callers inside a Room transaction. */
	internal suspend fun isAmbientProductReady(source: SourceKind): Boolean =
		trackingRolloutStateStore.load().isAmbientProductReachable(source)

	/** Before publication: only the immediate predecessor's still-active physical lifetime can continue. */
	internal suspend fun continuingStepsOriginInTransaction(
		continuation: StepsRecordingContinuation,
		proposedManifest: SessionManifestVersionEntity,
		proposedBinding: SessionManifestSourceEntity,
		ownerToken: String,
	): Long? = stepsRecordingContinuation.continuingStepsOriginInTransaction(
		continuation, proposedManifest, proposedBinding, ownerToken,
	)

	/** Only for an already published current command; old authority cannot reserve a new provider. */
	internal suspend fun continuingStepsDemand(
		logicalTrackingId: String,
		serviceRunId: String,
		leaseGeneration: Long,
		continuation: StepsRecordingContinuation? = null,
	): SourceDemandEntity? = stepsRecordingContinuation.continuingStepsDemand(
		logicalTrackingId, serviceRunId, leaseGeneration, continuation,
	)

	internal suspend fun requiresContinuingStepsRecording(claim: SourceRuntimeClaim): Boolean =
		stepsRecordingContinuation.requiresContinuingStepsRecording(claim)

	/** Reserves sessionless collection only under the exact current purpose and retention lease. */
	internal suspend fun replaceAmbientSourceDemand(
		leaseIdentity: AmbientReconciliationIdentity,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		retentionSnapshot: LiveAmbientRetentionSnapshot,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance,
	): AmbientSourceDemandResult = database.withTransaction {
		require(elapsedRealtimeNanos >= 0L)
		require(wallTimeMs >= 0L)
		val source = leaseIdentity.source.canonicalSource.toRuntimeSourceKind()
		require(source in DIRECT_AMBIENT_SOURCES)
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			authority.currentPolicyRevision != leaseIdentity.policyRevision
		) {
			return@withTransaction AmbientSourceDemandResult.Inactive("${source.name}_POLICY_CHANGED")
		}
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, source.stableCode)
			?: return@withTransaction AmbientSourceDemandResult.Inactive("${source.name}_POLICY_MISSING")
		if (!policy.hasAmbientConsent(leaseIdentity)) {
			return@withTransaction AmbientSourceDemandResult.Inactive("${source.name}_BACKGROUND_CONSENT_CHANGED")
		}
		val rollout = trackingRolloutStateStore.load()
		if (rollout.revision != leaseIdentity.rolloutRevision ||
			!rollout.isAmbientProductReachable(source)
		) {
			return@withTransaction AmbientSourceDemandResult.Inactive("${source.name}_BACKGROUND_CONTAINED")
		}
		// Preparing caller/retention authority may suspend while a session publishes a newer grant.
		// Take the application boundary only after this transaction owns the current demand vector.
		val boundary = SourceDemandMutationBoundary(bootId, clock.elapsedRealtimeNanos(), clock.currentTimeMillis())
		val retention = ambientSourceRetentionInTransaction(retentionSnapshot, source, leaseIdentity, boundary)
			?: return@withTransaction AmbientSourceDemandResult.Inactive("${source.name}_RETENTION_CHANGED")
		val contract = ambientSourceContract(policy, source)
			?: return@withTransaction AmbientSourceDemandResult.Inactive("${source.name}_PROFILE_UNSUPPORTED")
		val consumerId = ambientSourceConsumerId(source)
		val dao = database.sourceBrokerDao()
		val reference = preparedCallerAcceptance.receipt.reference.value
		val current = dao.currentDemands(consumerId)
		current.singleOrNull()?.takeIf { demand ->
			demand.matchesAmbientSourceDemand(source, leaseIdentity, contract, retention, reference, bootId)
		}?.let { return@withTransaction AmbientSourceDemandResult.Active(it) }
		val priorReferences = currentAuthorityReferences(consumerId)
		dao.retireConsumer(consumerId, bootId, boundary.elapsedRealtimeNanos, boundary.wallTimeMs)
		callerAuthorityRetirement.retireReferences(priorReferences, "AMBIENT_${source.name}_REPLACED", boundary.wallTimeMs)
		persistPreparedCallerAuthorityInTransaction(preparedCallerAcceptance, reference,
			leaseIdentity.purposeLeaseIdentity)
		val demand = contract.ambientSourceDemand(source, leaseIdentity, retention, reference, boundary)
		dao.insertDemands(listOf(demand))
		rotateCurrentAuthorizationInTransaction(source.stableCode, bootId, boundary.elapsedRealtimeNanos, boundary.wallTimeMs)
		AmbientSourceDemandResult.Active(demand)
	}

	private suspend fun ambientSourceRetentionInTransaction(
		snapshot: LiveAmbientRetentionSnapshot,
		source: SourceKind,
		leaseIdentity: AmbientReconciliationIdentity,
		boundary: SourceDemandMutationBoundary,
	): LiveAmbientRetentionGrant? = snapshot.takeIf { it.matchesActiveSettlementInTransaction() }
		?.grants?.get(source)?.takeIf { grant ->
			grant.matchesAmbientLease(leaseIdentity) &&
				isLiveAmbientRetentionCurrentInTransaction(
					grant, boundary.bootId, boundary.elapsedRealtimeNanos, boundary.wallTimeMs,
				)
		}

	private fun ambientSourceContract(policy: SourcePolicyEntity, source: SourceKind): SourceDemandContract? {
		if (source != SourceKind.LOCATION) {
			return if (policy.balanceProfileVersion == 1 && policy.balanceLevel?.let { it in 1..5 } == true) {
				SourceDemandContractFactory.forProfile(source, 1, requireNotNull(policy.balanceLevel),
					DirectSourceDemandPurpose.AMBIENT_PRODUCT)
			} else {
				SourceDemandContractFactory.forQos(source, policy.qosCode, DirectSourceDemandPurpose.AMBIENT_PRODUCT)
			}
		}
		val adaptive = policy.locationBackgroundMode ==
			com.adsamcik.tracker.shared.preferences.tracking.LocationBackgroundMode.ADAPTIVE.stableCode
		return if (adaptive) {
			if (policy.balanceProfileVersion != 1 || policy.balanceLevel?.let { it in 1..5 } != true) {
				return null
			}
			SourceDemandContractFactory.forProfile(source, 1, requireNotNull(policy.balanceLevel),
				DirectSourceDemandPurpose.AMBIENT_PRODUCT)
		} else {
			SourceDemandContract(
			floor = com.adsamcik.tracker.tracker.source.model.LocationAcquisitionFloor(
				com.adsamcik.tracker.tracker.source.model.LocationFloorMode.PASSIVE),
			maximumProviderItemAgeMs = 120_000L,
			targetPlanningLatencyMs = 120_000L,
			requestedDeliveryLatencyMs = null,
			adaptiveReductionAllowed = false,
		)
		}
	}

	internal suspend fun retireAmbientSourceDemand(
		identity: AmbientReconciliationIdentity,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean = database.withTransaction {
		val source = identity.source.canonicalSource.toRuntimeSourceKind()
		require(source in DIRECT_AMBIENT_SOURCES)
		val demands = currentPurposeDemands(ambientSourceConsumerId(source))
		if (demands.isEmpty()) {
			return@withTransaction true
		}
		val demand = demands.singleOrNull() ?: return@withTransaction false
		val reference = demand.sourceCallerAuthorityReference?.let(::SourceCallerReplayReference)
			?: return@withTransaction false
		val accepted = sourceCallerAuthorityRepository.load(reference) as?
			StoredSourceCallerAuthorityLoadResult.Available ?: return@withTransaction false
		if (accepted.authority.permittedDemandIdentities != setOf(
			SourceCallerDemandIdentity(identity.purposeLeaseIdentity, manifestIdentity = null)) ||
			demand.liveAmbientRetentionPolicyId != identity.retentionPolicyId ||
			demand.liveAmbientRetentionApprovalRevision != identity.retentionApprovalRevision
		) {
			return@withTransaction false
		}
		retireAcceptedPurposeDemand(demand, bootId, elapsedRealtimeNanos, wallTimeMs)
	}

	internal suspend fun currentAmbientSourceIdentity(
		source: SourceKind = SourceKind.LOCATION,
	): AmbientReconciliationIdentity? = database.withTransaction {
		val demand = currentPurposeDemands(ambientSourceConsumerId(source)).singleOrNull()
			?: return@withTransaction null
		val reference = demand.sourceCallerAuthorityReference?.let(::SourceCallerReplayReference)
			?: return@withTransaction null
		val accepted = sourceCallerAuthorityRepository.load(reference) as?
			StoredSourceCallerAuthorityLoadResult.Available ?: return@withTransaction null
		val identity = accepted.authority.permittedDemandIdentities.singleOrNull()
			?: return@withTransaction null
		if (identity.manifestIdentity != null ||
            identity.purposeLeaseIdentity.source.stableCode != source.stableCode ||
			identity.purposeLeaseIdentity.purpose != TrackingPurpose.AMBIENT_PRODUCT
		) {
			return@withTransaction null
		}
		AmbientReconciliationIdentity.from(identity.purposeLeaseIdentity,
			demand.liveAmbientRetentionPolicyId, demand.liveAmbientRetentionApprovalRevision)
	}

	/** Current durable authority vector used to reconcile the one physical source owner. */
	internal suspend fun authorizationDemands(source: SourceKind): List<SourceDemandEntity> =
		database.sourceBrokerDao().authorizationDemands(source.stableCode)

	/**
	 * Acquires retention authority before Room. Lock order is retention/purpose operation, then
	 * Room. The returned identity is only a candidate until the transaction exact-CASes the
	 * append-only retention row, lifecycle floor/epoch, and active settlement journal.
	 */
	internal suspend fun captureLiveAmbientRetentionSnapshot(
		demands: Collection<SourceDemandEntity>,
		expectedCollectedDataEpoch: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
	): LiveAmbientRetentionSnapshot? {
		require(expectedCollectedDataEpoch >= 0L)
		require(currentBootId.isNotBlank())
		require(currentElapsedRealtimeNanos >= 0L)
		require(currentWallTimeMs >= 0L)
		val ambientDemands = demands.filter { demand -> demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		val retainedFromMs = if (ambientDemands.isEmpty()) {
			null
		} else {
			val evidence = database.sourceEvidenceStateDao().get() ?: return null
			if (evidence.collectedDataEpoch != expectedCollectedDataEpoch) {
				return null
			}
			evidence.retainedFromMs
		}
		val settlementOperationId = currentRetentionFloorSettlementOperationId()
		val grants = captureMatchingAmbientRetentionGrants(ambientDemands, expectedCollectedDataEpoch,
			currentBootId, currentElapsedRealtimeNanos, currentWallTimeMs, retainedFromMs, settlementOperationId)
			?: return null
		return LiveAmbientRetentionSnapshot(grants.toMap(), settlementOperationId)
	}

	private suspend fun captureMatchingAmbientRetentionGrants(
		ambientDemands: Collection<SourceDemandEntity>,
		expectedCollectedDataEpoch: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
		retainedFromMs: Long?,
		settlementOperationId: String?,
	): Map<SourceKind, LiveAmbientRetentionGrant>? {
		val grants = linkedMapOf<SourceKind, LiveAmbientRetentionGrant>()
		for (demand in ambientDemands) {
			val source = SourceKind.entries.singleOrNull { it.stableCode == demand.sourceKind } ?: return null
			val existing = grants[source]
			if (existing != null) {
				if (!existing.matchesDemand(demand)) {
					return null
				}
				continue
			}
			val captured = captureMatchingAmbientRetentionGrant(source, demand, expectedCollectedDataEpoch,
				currentBootId, currentElapsedRealtimeNanos, currentWallTimeMs, retainedFromMs, settlementOperationId)
				?: return null
			grants[source] = captured
		}
		return grants
	}

	private suspend fun captureMatchingAmbientRetentionGrant(
		source: SourceKind,
		demand: SourceDemandEntity,
		expectedCollectedDataEpoch: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
		retainedFromMs: Long?,
		settlementOperationId: String?,
	): LiveAmbientRetentionGrant? {
		if (demand.liveAmbientRetentionPolicyId.isNullOrBlank() || demand.liveAmbientRetentionApprovalRevision == null) {
			return null
		}
		return captureLiveAmbientRetentionGrant(
			source = source,
			sourcePolicyRevision = demand.sourcePolicyRevision,
			ambientConsentEpoch = demand.consentEpoch,
			collectedDataEpoch = expectedCollectedDataEpoch,
			currentBootId = currentBootId,
			currentElapsedRealtimeNanos = currentElapsedRealtimeNanos,
			currentWallTimeMs = currentWallTimeMs,
			expectedRetainedFromMs = retainedFromMs,
			settlementOperationId = settlementOperationId,
		)?.takeIf { it.matchesDemand(demand) }
	}

	internal suspend fun captureLiveAmbientRetentionSnapshot(
		source: SourceKind,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
		expectedRetainedFromMs: Long? = null,
	): LiveAmbientRetentionSnapshot? {
		val settlementOperationId = currentRetentionFloorSettlementOperationId()
		return captureLiveAmbientRetentionGrant(
			source,
			sourcePolicyRevision,
			ambientConsentEpoch,
			collectedDataEpoch,
			currentBootId,
			currentElapsedRealtimeNanos,
			currentWallTimeMs,
			expectedRetainedFromMs,
			settlementOperationId,
		)?.let { grant ->
			LiveAmbientRetentionSnapshot(mapOf(source to grant), settlementOperationId)
		}
	}

	internal suspend fun areLiveAmbientDemandsCurrentInTransaction(
		demands: Collection<SourceDemandEntity>,
		expectedCollectedDataEpoch: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
		retentionSnapshot: LiveAmbientRetentionSnapshot,
	): Boolean {
		if (database.sourceEvidenceStateDao().get()?.collectedDataEpoch != expectedCollectedDataEpoch) {
			return false
		}
		val ambientDemands = demands.filter { demand -> demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT }
		val expectedSources = ambientRetentionSources(ambientDemands) ?: return false
		return retentionSnapshot.grants.keys == expectedSources &&
			(ambientDemands.isEmpty() || retentionSnapshot.matchesActiveSettlementInTransaction()) &&
			ambientDemands.all { demand ->
				val source = SourceKind.entries.single { it.stableCode == demand.sourceKind }
				val grant = retentionSnapshot.grants[source] ?: return@all false
				grant.matchesDemand(demand) && isLiveAmbientRetentionCurrentInTransaction(
					grant, currentBootId, currentElapsedRealtimeNanos, currentWallTimeMs)
			}
	}

	private fun ambientRetentionSources(demands: Collection<SourceDemandEntity>): Set<SourceKind>? =
		demands.mapTo(linkedSetOf()) { demand ->
			SourceKind.entries.singleOrNull { it.stableCode == demand.sourceKind } ?: return null
		}

	internal fun buildSessionDemands(
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevision: Long,
		lifecycleLeaseGeneration: Long,
		policyRevision: Long,
		bindings: Collection<SessionManifestSourceEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		sourceCallerAuthorityReference: String? = null,
		/** Exact immutable rows read by the manifest transaction; null is legacy test compatibility. */
		policies: Map<Int, SourcePolicyEntity>? = null,
	): List<SourceDemandEntity> {
		val consumerId = sessionConsumerId(logicalTrackingId)
		return bindings.sortedWith(
			compareBy<SessionManifestSourceEntity>(SessionManifestSourceEntity::purpose)
				.thenBy(SessionManifestSourceEntity::sourceKind),
		).map { binding ->
			val purpose = when (binding.purpose) {
				SourceBrokerPurpose.SESSION_CAPTURE -> SourceBrokerPurpose.SESSION_CAPTURE
				SessionManifestPurpose.CONTROL.name -> SourceBrokerPurpose.CONTROL_CONTINUATION
				else -> error("Unsupported session-manifest purpose ${binding.purpose}")
			}
			val source = SourceKind.entries.single { source -> source.stableCode == binding.sourceKind }
			val policy = policies?.let { requireNotNull(it[binding.sourceKind]) }
			val contract = sessionDemandContract(source, policy, binding, policyRevision, purpose)
			SourceDemandEntity(
				demandId = sessionDemandId(
					consumerId = consumerId,
					sourceKind = binding.sourceKind,
					purpose = purpose,
					serviceRunId = serviceRunId,
					policyRevision = policyRevision,
					consentEpoch = binding.consentEpoch,
					manifestRevision = manifestRevision,
					lifecycleLeaseGeneration = lifecycleLeaseGeneration,
					activationBootId = bootId,
					activationElapsedRealtimeNanos = elapsedRealtimeNanos,
				),
				consumerId = consumerId,
				sourceKind = binding.sourceKind,
				purpose = purpose,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				manifestRevision = manifestRevision,
				lifecycleLeaseGeneration = lifecycleLeaseGeneration,
				sourcePolicyRevision = policyRevision,
				consentEpoch = binding.consentEpoch,
				persistenceEligible = binding.persistenceEligible,
				qosCode = binding.qosCode,
				minimumAcquisitionSpec = contract.encodeFloor(),
				adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
				maximumAgeMs = contract.maximumProviderItemAgeMs,
				desiredLatencyMs = contract.targetPlanningLatencyMs,
				requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
				requestedBootId = bootId,
				requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
				requestedAtMs = wallTimeMs,
				status = SourceDemandEntity.STATUS_ACTIVE,
				retireBootId = null,
				retireElapsedRealtimeNanos = null,
				retiredAtMs = null,
				sourceCallerAuthorityReference = sourceCallerAuthorityReference,
			)
		}
	}

	private fun sessionDemandContract(
		source: SourceKind,
		policy: SourcePolicyEntity?,
		binding: SessionManifestSourceEntity,
		policyRevision: Long,
		purpose: String,
	): SourceDemandContract {
		if (policy != null) {
			require(policy.policyRevision == policyRevision && policy.sourceKind == binding.sourceKind)
			if (purpose == SourceBrokerPurpose.SESSION_CAPTURE) {
				require(policy.qosCode == binding.qosCode)
				require(policy.captureConsentEpoch == binding.consentEpoch &&
				policy.capturePersistenceEligible == binding.persistenceEligible)
			}
		}
		return if (purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
			policy?.balanceProfileVersion != null
		) {
			SourceDemandContractFactory.forProfile(source, requireNotNull(policy.balanceProfileVersion),
				maxOf(requireNotNull(policy.balanceLevel), SourceBalanceSettings.LIVE_LEVEL), purpose.toDirectDemandPurpose())
		} else {
			SourceDemandContractFactory.forQos(
				source,
				binding.qosCode,
				purpose.toDirectDemandPurpose(),
			)
		}
	}

	private suspend fun sessionCallerAuthorityReferences(
		logicalTrackingId: String,
		consumerId: String,
	): Set<SourceCallerReplayReference> =
		(
			currentAuthorityReferences(consumerId) +
				database.sourceSessionDao().lifecycleIntents(logicalTrackingId)
					.mapNotNull { intent -> intent.sourceCallerAuthorityReference }
					.mapNotNull(String::toCallerReferenceOrNull)
		).toSet()

	private suspend fun retainedStepsDemandInTransaction(
		logicalTrackingId: String,
		requestedSteps: SourceDemandEntity?,
		stepsContinuation: StepsRecordingContinuation?,
	): SourceDemandEntity? {
		val currentStepsBinding = requestedSteps?.manifestRevision?.let { revision ->
			database.sourceSessionDao().manifestSources(logicalTrackingId, revision).singleOrNull {
				it.sourceKind == SourceKind.STEPS.stableCode && it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		}
		val continuing = currentStepsBinding?.stepsRecordingManifestRevision?.let { origin ->
			origin < currentStepsBinding.manifestRevision
		} == true
		val retainedSteps = if (continuing && stepsContinuation != null && requestedSteps != null) {
			val freshReference = requestedSteps.sourceCallerAuthorityReference?.let(::SourceCallerReplayReference)
			val recording = freshReference?.let {
				stepsRecordingContinuation.liveStepsRecordingInTransaction(
					logicalTrackingId, requireNotNull(requestedSteps.serviceRunId), it,
				)
			}
			recording?.takeIf { stepsRecordingContinuation.hasExactLiveStepsRegistration(it, stepsContinuation) }?.demand
		} else {
			null
		}
		check(!continuing || retainedSteps != null) { "Continuing Steps recording changed before publication" }
		return retainedSteps
	}

	/** Must be called from the manifest/intent transaction. */
	internal suspend fun replaceSessionDemandsInTransaction(
		logicalTrackingId: String,
		demands: List<SourceDemandEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		retireSupersededAuthority: Boolean = true,
		stepsContinuation: StepsRecordingContinuation? = null,
	) {
		val consumerId = sessionConsumerId(logicalTrackingId)
		val dao = database.sourceBrokerDao()
		val affectedSources = (dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind) +
			demands.map(SourceDemandEntity::sourceKind)).toSet()
		val retiredReferences = sessionCallerAuthorityReferences(logicalTrackingId, consumerId)
		val requestedSteps = demands.singleOrNull { it.sourceKind == SourceKind.STEPS.stableCode &&
			it.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		val priorSteps = dao.currentDemands(consumerId).singleOrNull { demand ->
			demand.sourceKind == SourceKind.STEPS.stableCode && demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE
		}
		val priorStepsOrigin = priorSteps?.manifestRevision?.let { revision ->
			database.sourceSessionDao().manifestSources(logicalTrackingId, revision).singleOrNull { binding ->
				binding.sourceKind == SourceKind.STEPS.stableCode &&
                    binding.purpose == SourceBrokerPurpose.SESSION_CAPTURE
			}?.stepsRecordingManifestRevision
		}
		val retainedSteps = retainedStepsDemandInTransaction(logicalTrackingId, requestedSteps, stepsContinuation)
		if (retainedSteps == null) {
			dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		} else {
			dao.retireConsumerExceptDemand(consumerId, retainedSteps.demandId, bootId, elapsedRealtimeNanos, wallTimeMs)
		}
		if (retireSupersededAuthority) {
			callerAuthorityRetirement.retireReferences(retiredReferences.filterNot {
				it.value == retainedSteps?.sourceCallerAuthorityReference }.toSet(), "SESSION_DEMAND_REPLACED",
                    wallTimeMs)
		}
		// A real recording boundary also releases its older origin receipt. The descriptor's
		// ordinary retirement still handles the immediate predecessor's fresh command receipt.
		retireReplacedStepsOrigin(retainedSteps, priorSteps, priorStepsOrigin, demands, wallTimeMs)
		val replacements = if (retainedSteps == null) {
			demands
		} else {
			demands.filterNot { it === requestedSteps }
		}
		if (replacements.isNotEmpty()) {
			dao.insertDemands(replacements)
		}
		affectedSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(
				sourceKind,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}
	}

	private suspend fun retireReplacedStepsOrigin(
		retainedSteps: SourceDemandEntity?,
		priorSteps: SourceDemandEntity?,
		priorStepsOrigin: Long?,
		demands: List<SourceDemandEntity>,
		wallTimeMs: Long,
	) {
		if (retainedSteps == null && priorSteps != null && priorStepsOrigin == priorSteps.manifestRevision) {
			priorSteps.sourceCallerAuthorityReference?.toCallerReferenceOrNull()?.takeIf { old ->
				demands.none { it.sourceCallerAuthorityReference == old.value }
			}?.let { old -> callerAuthorityRetirement.retireReferences(setOf(old), "STEPS_RECORDING_REPLACED", wallTimeMs) }
		}
	}

	/**
	 * Persists one exact session demand vector without making it registration or callback authority.
	 * Existing authority is retired immediately, but the new vector remains [SourceDemandEntity.STATUS_BLOCKED]
	 * until the owning Android service has successfully entered the foreground.
	 */
	internal suspend fun stageSessionDemandsInTransaction(
		logicalTrackingId: String,
		demands: List<SourceDemandEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		val consumerId = sessionConsumerId(logicalTrackingId)
		val dao = database.sourceBrokerDao()
		val previouslyActiveSources = dao.currentDemands(consumerId)
			.map(SourceDemandEntity::sourceKind)
			.toSet()
		val retiredReferences = sessionCallerAuthorityReferences(logicalTrackingId, consumerId)
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		callerAuthorityRetirement.retireReferences(retiredReferences, "SESSION_DEMAND_STAGED", wallTimeMs)
		if (demands.isNotEmpty()) {
			dao.insertDemands(demands.map { demand ->
				demand.copy(status = SourceDemandEntity.STATUS_BLOCKED)
			})
		}
		previouslyActiveSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(
				sourceKind,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}
	}

	/**
	 * Reissues a prepared session vector for a new coordinator lease without rewriting any prior
	 * demand or observed-time authorization identity. The caller owns the surrounding Room
	 * transaction and updates the prepared lifecycle references in the same commit.
	 */
	internal suspend fun rebindPreparedSessionDemandsInTransaction(
		expectedDemands: List<SourceDemandEntity>,
		expectedCallerAuthorityReference: String,
		newCallerAuthorityReference: String,
		newLeaseGeneration: Long,
		foregroundAccepted: Boolean,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): List<SourceDemandEntity>? {
		return preparedSessionDemands.rebind(expectedDemands, expectedCallerAuthorityReference,
			newCallerAuthorityReference, newLeaseGeneration, foregroundAccepted,
			SourceDemandMutationBoundary(bootId, elapsedRealtimeNanos, wallTimeMs))
	}

	/**
	 * Opens the foreground-accepted capture subset and its control vector, retiring excluded
	 * capture demands in the same transaction before any observer can see them as active.
	 */
	internal suspend fun activatePreparedSessionDemandsInTransaction(
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevision: Long,
		leaseGeneration: Long,
		sourceCallerAuthorityReference: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		currentAuthority: SourceCallerCurrentAuthorityPredicate,
		acceptedSourceKinds: Set<Int>? = null,
	): Boolean {
		val owner = PreparedSessionActivationOwner(logicalTrackingId, sessionConsumerId(logicalTrackingId),
			serviceRunId, manifestRevision, leaseGeneration, sourceCallerAuthorityReference)
		return preparedSessionDemands.activate(owner, SourceDemandMutationBoundary(bootId, elapsedRealtimeNanos,
			wallTimeMs), currentAuthority, acceptedSourceKinds)
	}

	internal suspend fun retirePreparedSessionCaptureDemandsInTransaction(
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevision: Long,
		leaseGeneration: Long,
		sourceKinds: Set<Int>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean {
		if (sourceKinds.isEmpty()) {
			return true
		}
		val consumerId = sessionConsumerId(logicalTrackingId)
		val dao = database.sourceBrokerDao()
		val retiring = dao.demandHistory(consumerId).filter { demand ->
			demand.serviceRunId == serviceRunId &&
				demand.manifestRevision == manifestRevision &&
				demand.lifecycleLeaseGeneration == leaseGeneration &&
				demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
				demand.sourceKind in sourceKinds &&
				demand.status in setOf(
					SourceDemandEntity.STATUS_ACTIVE,
					SourceDemandEntity.STATUS_BLOCKED,
				)
		}
		if (retiring.isEmpty()) {
			return true
		}
		if (dao.retirePreparedSessionDemandsByIds(
				consumerId,
				serviceRunId,
				manifestRevision,
				leaseGeneration,
				retiring.map(SourceDemandEntity::demandId),
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			) != retiring.size
		) {
			return false
		}
		retiring.map(SourceDemandEntity::sourceKind).toSet().forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(
				sourceKind,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}
		return true
	}

	suspend fun markSessionDemandsRetiring(
		logicalTrackingId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val consumerId = sessionConsumerId(logicalTrackingId)
		val affectedSources = dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind).toSet()
		val updated = dao.markConsumerRetiring(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		affectedSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(sourceKind, bootId, elapsedRealtimeNanos, wallTimeMs)
		}
		updated
	}

	suspend fun retireSessionDemands(
		logicalTrackingId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		retireCallerAuthority: Boolean = true,
	) = database.withTransaction {
		retireSessionDemandsInTransaction(
			logicalTrackingId,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
			retireCallerAuthority,
		)
	}

	/** Retires session demands and, unless deferred for restart handoff, their caller authority. */
	suspend fun retireSessionDemandsInTransaction(
		logicalTrackingId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		retireCallerAuthority: Boolean = true,
	): Int {
		val dao = database.sourceBrokerDao()
		val consumerId = sessionConsumerId(logicalTrackingId)
		val affectedSources = dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind).toSet()
		val retiredReferences = sessionCallerAuthorityReferences(logicalTrackingId, consumerId)
		val updated = dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		if (retireCallerAuthority) {
			callerAuthorityRetirement.retireReferences(retiredReferences, "SESSION_DEMAND_RETIRED", wallTimeMs)
		}
		affectedSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(sourceKind, bootId, elapsedRealtimeNanos, wallTimeMs)
		}
		return updated
	}

	private suspend fun currentAuthorityReferences(consumerId: String): Set<SourceCallerReplayReference> =
		database.sourceBrokerDao().callerAuthorityReferences(consumerId).asSequence()
			.mapNotNull(String::toCallerReferenceOrNull)
			.toSet()

	private suspend fun persistPreparedCallerAuthorityInTransaction(
		prepared: PreparedSourceCallerAcceptance?,
		expectedReference: String?,
		expectedPurposeIdentity: TrackingPurposeLeaseIdentity? = null,
	): String? {
		if (prepared == null) {
			return expectedReference
		}
		val reference = prepared.receipt.reference.value
		require(expectedReference == null || expectedReference == reference) {
			"Prepared caller authority does not match the demand reference"
		}
		if (expectedPurposeIdentity != null) {
			require(
				prepared.authority.origin == StoredSourceCallerOrigin.PURPOSE_OWNER &&
					prepared.authority.purpose == expectedPurposeIdentity.purpose &&
					prepared.authority.permittedDemandIdentities == setOf(
						SourceCallerDemandIdentity(
							expectedPurposeIdentity,
							manifestIdentity = null,
						),
					),
			) {
				"Prepared caller authority does not match the exact purpose lease"
			}
		}
		check(sourceCallerAuthorityRepository.insertIfAbsent(
			prepared.receipt.reference,
			prepared.authority,
			prepared.createdAtMs,
		)) {
			"Unable to persist prepared source-caller authority"
		}
		return reference
	}

	internal suspend fun currentPurposeDemands(
		consumerId: String,
	): List<SourceDemandEntity> = database.withTransaction {
		database.sourceBrokerDao().demandHistory(consumerId)
			.filter { demand ->
				demand.status == SourceDemandEntity.STATUS_ACTIVE ||
					demand.status == SourceDemandEntity.STATUS_RETIRING ||
					demand.status == SourceDemandEntity.STATUS_BLOCKED
			}
	}

	internal suspend fun retireAcceptedPurposeDemand(
		expected: SourceDemandEntity,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val current = dao.demandHistory(expected.consumerId)
			.filter { demand ->
				demand.status == SourceDemandEntity.STATUS_ACTIVE ||
					demand.status == SourceDemandEntity.STATUS_RETIRING ||
					demand.status == SourceDemandEntity.STATUS_BLOCKED
			}
			.singleOrNull()
			?: return@withTransaction false
		if (current != expected || current.sourceCallerAuthorityReference == null) {
			return@withTransaction false
		}
		check(dao.retireConsumer(
			expected.consumerId,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		) == 1) {
			"Exact purpose-owner demand retirement lost ownership"
		}
		check(retireDemandAuthority(current, "PURPOSE_OWNER_DEMAND_RETIRED", wallTimeMs)) {
			"Unable to retire exact purpose-owner caller authority"
		}
		rotateCurrentAuthorizationInTransaction(
			current.sourceKind,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		true
	}

	/**
	 * Retires the recorded predecessor after proving every other historical reference is terminal.
	 */
	internal suspend fun retireSupersededSessionAuthoritiesInTransaction(
		logicalTrackingId: String,
		currentReference: SourceCallerReplayReference,
		expectedSupersededReference: SourceCallerReplayReference,
		wallTimeMs: Long,
	): SourceCallerAuthorityRetirementOutcome {
		if (currentReference == expectedSupersededReference || wallTimeMs < 0L) {
			return SourceCallerAuthorityRetirementOutcome.TerminalInvariant
		}
		val persistedReferenceValues =
			database.sourceBrokerDao().callerAuthorityReferences(
				sessionConsumerId(logicalTrackingId),
			) + database.sourceSessionDao().lifecycleIntents(logicalTrackingId)
				.mapNotNull { intent -> intent.sourceCallerAuthorityReference }
		val references = persistedReferenceValues
			.mapNotNull(String::toCallerReferenceOrNull)
			.toSet()
		if (references.size != persistedReferenceValues.toSet().size) {
			return SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
		}
		val superseded = references
			.filterTo(linkedSetOf()) { reference -> reference != currentReference }
		val retainedStepsReference = database.sourceSessionDao().session(logicalTrackingId)?.currentServiceRunId
			?.let { runId ->
				stepsRecordingContinuation.liveStepsRecordingInTransaction(logicalTrackingId, runId, currentReference)
			}
			?.demand?.sourceCallerAuthorityReference?.let(::SourceCallerReplayReference)
		return if (expectedSupersededReference !in superseded) {
			callerAuthorityRetirement.unreferencedSupersededOutcome(expectedSupersededReference)
		} else {
			callerAuthorityRetirement.historicalAuthorityConflict(superseded, expectedSupersededReference,
				retainedStepsReference) ?: if (expectedSupersededReference == retainedStepsReference) {
				SourceCallerAuthorityRetirementOutcome.RetainedForSteps
			} else {
				callerAuthorityRetirement.retireExact(expectedSupersededReference, "SESSION_AUTHORITY_SUPERSEDED", wallTimeMs)
			}
		}
	}

	private suspend fun retireDemandAuthority(
		demand: SourceDemandEntity,
		reason: String,
		wallTimeMs: Long,
	): Boolean = demand.sourceCallerAuthorityReference?.toCallerReferenceOrNull()?.let { reference ->
		callerAuthorityRetirement.retireReferences(
			setOf(reference),
			reason,
			wallTimeMs,
		)
	} ?: true

	/**
	 * Replaces one application-scoped control demand before its provider is reconciled.
	 * Fails closed if the authoritative policy has no current CONTROL epoch.
	 */
	internal suspend fun replaceAutomaticControlDemand(
		consumerId: String,
		source: SourceKind,
		enabled: Boolean,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		maximumAgeMs: Long,
		desiredLatencyMs: Long,
		sourceCallerAuthorityReference: String? = null,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance? = null,
		purposeLeaseIdentity: TrackingPurposeLeaseIdentity? = null,
	): SourceDemandEntity? = database.withTransaction {
		val boundary = SourceDemandMutationBoundary(bootId, elapsedRealtimeNanos, wallTimeMs)
		val dao = database.sourceBrokerDao()
		val callerAuthorityReference = preparedCallerAcceptance?.receipt?.reference?.value
			?: sourceCallerAuthorityReference
		val affectedSourceKinds = (
			dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind) + source.stableCode
		).toSet()
		val priorReferences = currentAuthorityReferences(consumerId)
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		callerAuthorityRetirement.retireReferences(priorReferences, "AUTOMATIC_CONTROL_REPLACED", wallTimeMs)
		suspend fun inactive(reason: String): SourceDemandEntity? {
			completeAutomaticControlMutation(source, false, reason, affectedSourceKinds, boundary)
			return null
		}
		if (!enabled) {
			return@withTransaction inactive("AUTOMATIC_CONTROL_DISABLED")
		}
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			return@withTransaction inactive("AUTOMATIC_CONTROL_AUTHORITY_INACTIVE")
		}
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, source.stableCode)
		if (policy == null) {
			return@withTransaction inactive("AUTOMATIC_CONTROL_POLICY_MISSING")
		}
		val consentEpoch = policy.controlConsentEpoch
		if (consentEpoch == null) {
			return@withTransaction inactive("AUTOMATIC_CONTROL_REVOKED")
		}
		if (purposeLeaseIdentity != null &&
			!matchesCurrentAutomaticControlLease(purposeLeaseIdentity, source, authority.currentPolicyRevision, consentEpoch)
		) {
			return@withTransaction null
		}
		if (!automaticControlAcquisitionEligibleInTransaction(source)) {
			return@withTransaction inactive("AUTOMATIC_CONTROL_ROLLOUT_CONTAINED")
		}
		val contract = automaticControlContract(source, policy.qosCode, maximumAgeMs, desiredLatencyMs)
		val demand = policy.automaticControlDemand(consumerId, source, authority.currentPolicyRevision,
			contract, boundary, callerAuthorityReference)
		persistPreparedCallerAuthorityInTransaction(
			preparedCallerAcceptance,
			callerAuthorityReference,
			purposeLeaseIdentity,
		)
		dao.insertDemands(listOf(demand))
		completeAutomaticControlMutation(source, true, "AUTOMATIC_CONTROL_ENABLED",
			affectedSourceKinds, boundary)
		demand
	}

	private suspend fun matchesCurrentAutomaticControlLease(
		identity: TrackingPurposeLeaseIdentity,
		source: SourceKind,
		policyRevision: Long,
		consentEpoch: Long,
	): Boolean {
		val evidence = database.sourceEvidenceStateDao().get() ?: return false
		val rollout = trackingRolloutStateStore.load()
		return identity.matchesAutomaticControlAuthority(
			source, policyRevision, consentEpoch, evidence.collectedDataEpoch, rollout.revision,
		)
	}

	/** Completes one CONTROL replacement only after its demand mutation, preserving reason and read order. */
	private suspend fun completeAutomaticControlMutation(
		source: SourceKind,
		enabled: Boolean,
		reason: String,
		affectedSourceKinds: Set<Int>,
		boundary: SourceDemandMutationBoundary,
	) {
		rotateCurrentAuthorizationsInTransaction(affectedSourceKinds, boundary.bootId,
			boundary.elapsedRealtimeNanos, boundary.wallTimeMs)
		reconcileAutomaticControlEpochInTransaction(source, enabled, reason, boundary.bootId,
			boundary.elapsedRealtimeNanos, boundary.wallTimeMs)
	}

	/**
	 * Fences every nonterminal row owned by one purpose consumer before historical caller
	 * metadata is inspected. A malformed or unavailable old grant can reduce authority but can
	 * never keep provider demand alive.
	 */
	internal suspend fun retirePurposeDemand(
		consumerId: String,
		expectedSourceKind: Int,
		expectedPurpose: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean = database.withTransaction {
		require(consumerId.isNotBlank())
		require(bootId.isNotBlank())
		require(elapsedRealtimeNanos >= 0L)
		require(wallTimeMs >= 0L)
		val dao = database.sourceBrokerDao()
		val history = dao.demandHistory(consumerId)
		val current = history.filter { demand ->
			demand.status in setOf(
				SourceDemandEntity.STATUS_ACTIVE,
				SourceDemandEntity.STATUS_RETIRING,
				SourceDemandEntity.STATUS_BLOCKED,
			)
		}
		if (current.isEmpty()) {
			return@withTransaction retireHistoricalPurposeDemand(
				history, expectedSourceKind, expectedPurpose, bootId, elapsedRealtimeNanos, wallTimeMs,
			)
		}
		val exactOwner = current.all { demand ->
			demand.sourceKind == expectedSourceKind && demand.purpose == expectedPurpose
		}
		val references = current.mapNotNull(SourceDemandEntity::sourceCallerAuthorityReference)
			.mapNotNullTo(linkedSetOf(), String::toCallerReferenceOrNull)
		val affectedSources = current.mapTo(linkedSetOf(), SourceDemandEntity::sourceKind)
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		val callerAuthorityRetired = callerAuthorityRetirement.retireReferences(
			references,
			"PURPOSE_OWNER_DEMAND_RETIRED",
			wallTimeMs,
		)
		rotateCurrentAuthorizationsInTransaction(
			affectedSources,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		reconcileRetiredPurposeControlEpoch(expectedSourceKind, expectedPurpose, bootId,
			elapsedRealtimeNanos, wallTimeMs)
		exactOwner && callerAuthorityRetired
	}

	private suspend fun retireHistoricalPurposeDemand(
		history: List<SourceDemandEntity>,
		expectedSourceKind: Int,
		expectedPurpose: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean {
		val historicalReferences = history.asSequence()
			.filter { demand ->
				demand.sourceKind == expectedSourceKind &&
					demand.purpose == expectedPurpose
			}
			.mapNotNull(SourceDemandEntity::sourceCallerAuthorityReference)
			.mapNotNull(String::toCallerReferenceOrNull)
			.toSet()
		rotateCurrentAuthorizationInTransaction(
			expectedSourceKind,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		reconcileRetiredPurposeControlEpoch(expectedSourceKind, expectedPurpose, bootId,
			elapsedRealtimeNanos, wallTimeMs)
		return callerAuthorityRetirement.retireReferences(
			historicalReferences,
			"PURPOSE_OWNER_DEMAND_RETIRED",
			wallTimeMs,
		)
	}

	private suspend fun reconcileRetiredPurposeControlEpoch(
		expectedSourceKind: Int,
		expectedPurpose: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		if (expectedSourceKind == SourceKind.ACTIVITY.stableCode &&
			expectedPurpose == SourceBrokerPurpose.CONTROL_AUTOSTART
		) {
			reconcileAutomaticControlEpochInTransaction(
				SourceKind.ACTIVITY,
				false,
				"AUTOMATIC_CONTROL_RETIRED",
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}
	}

	/**
	 * App-scoped control is useful only when its control-operational lane and at least one currently
	 * enabled capture source's product lane are both reachable. Control reachability deliberately
	 * does not make the control source captured or materializable.
	 */
	private suspend fun automaticControlAcquisitionEligibleInTransaction(source: SourceKind): Boolean {
		val rollout = trackingRolloutStateStore.load()
		if (!rollout.isControlAcquisitionReachable(source)) {
			return false
		}
		val authority = database.sourcePolicyDao().authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			return false
		}
		val policies = database.sourcePolicyDao().policiesAtRevision(authority.currentPolicyRevision)
		if (policies.map { it.sourceKind }.toSet() != SourceKind.entries.map { it.stableCode }.toSet()) {
			return false
		}
		return policies.any { policy ->
			policy.enabled &&
				policy.captureConsentEpoch != null &&
				policy.capturePersistenceEligible &&
				SourceKind.entries.singleOrNull { it.stableCode == policy.sourceKind }
					?.let { captureSource ->
						rollout.isCaptureReachable(
							captureSource,
							CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE,
						)
					} == true
		}
	}

	private suspend fun reconcileAutomaticControlEpochInTransaction(
		source: SourceKind,
		enabled: Boolean,
		reason: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		if (source != SourceKind.ACTIVITY) {
			return
		}
		database.reconcileActivityAutomationEpochInTransaction(
			automaticControlEnabled = enabled,
			bootClockDomainId = bootId,
			effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
			reason = reason,
			updatedAtMs = wallTimeMs,
		)
	}

	/**
	 * Replaces the one app-scoped Ambient Steps demand after capability and permission selection.
	 * Preference alone is insufficient: Room policy, persistent ambient consent, and the source's
	 * ambient rollout lane must all agree in this transaction.
	 */
	internal suspend fun replaceAmbientStepsDemand(
		consumerId: String,
		mechanism: AmbientStepsAcquisitionMechanism?,
		leaseIdentity: AmbientReconciliationIdentity,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		sourceCallerAuthorityReference: String? = null,
		retentionSnapshot: LiveAmbientRetentionSnapshot? = null,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance? = null,
	): AmbientStepsDemandResult = database.withTransaction {
		require(consumerId.isNotBlank())
		require(leaseIdentity.source == AmbientTrackingSource.STEPS)
		val source = SourceKind.STEPS
		val callerAuthorityReference = preparedCallerAcceptance?.receipt?.reference?.value
			?: sourceCallerAuthorityReference
		val dao = database.sourceBrokerDao()
		val currentDemands = dao.currentDemands(consumerId)
		val affectedSourceKinds = (
			currentDemands.map(SourceDemandEntity::sourceKind) + source.stableCode
		).toSet()

		suspend fun inactive(
			reason: AmbientStepsDemandInactiveReason,
			retention: AmbientStepsRetentionAuthorityEntity? = null,
		): AmbientStepsDemandResult = retireAmbientStepsInTransaction(
			consumerId, currentDemands, leaseIdentity,
			SourceDemandMutationBoundary(bootId, elapsedRealtimeNanos, wallTimeMs), dao, reason, retention,
		)

		if (mechanism == null) {
			return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.REQUEST_DISABLED,
				database.ambientStepsFactRevisionDao().latestRetentionAuthority(
					AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				),
			)
		}
		ambientStepsPolicyInactiveReasonInTransaction(leaseIdentity)?.let { reason ->
			return@withTransaction inactive(reason)
		}
		val evidence = database.sourceEvidenceStateDao().get()
			?: return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.RETENTION_APPROVAL_MISSING,
			)
		val retention = ambientStepsRetentionInTransaction(retentionSnapshot, leaseIdentity,
			evidence.collectedDataEpoch, SourceDemandMutationBoundary(bootId, elapsedRealtimeNanos, wallTimeMs))
			?: return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.RETENTION_AUTHORITY_UNAVAILABLE,
			)

		val contract = SourceDemandContractFactory.forAmbientSteps(mechanism)
		currentDemands.singleOrNull()?.takeIf { demand ->
			demand.matchesAmbientStepsDemand(leaseIdentity, contract, retention, callerAuthorityReference, bootId)
		}?.let { unchanged -> return@withTransaction AmbientStepsDemandResult.Active(unchanged) }
		val priorReferences = currentAuthorityReferences(consumerId)
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		callerAuthorityRetirement.retireReferences(priorReferences, "AMBIENT_STEPS_REPLACED", wallTimeMs)
		persistPreparedCallerAuthorityInTransaction(
			preparedCallerAcceptance,
			callerAuthorityReference,
			leaseIdentity.purposeLeaseIdentity,
		)
		val demand = contract.ambientStepsDemand(consumerId, leaseIdentity, retention, callerAuthorityReference,
			SourceDemandMutationBoundary(bootId, elapsedRealtimeNanos, wallTimeMs))
		dao.insertDemands(listOf(demand))
		rotateCurrentAuthorizationsInTransaction(
			affectedSourceKinds,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		AmbientStepsDemandResult.Active(demand)
	}

	private suspend fun ambientStepsRetentionInTransaction(
		snapshot: LiveAmbientRetentionSnapshot?,
		leaseIdentity: AmbientReconciliationIdentity,
		evidenceEpoch: Long,
		boundary: SourceDemandMutationBoundary,
	): LiveAmbientRetentionGrant? = snapshot
		?.takeIf { it.matchesActiveSettlementInTransaction() }
		?.grants
		?.get(SourceKind.STEPS)
		?.takeIf { grant ->
			grant.matchesAmbientLease(leaseIdentity) &&
				grant.collectedDataEpoch == evidenceEpoch &&
				isLiveAmbientRetentionCurrentInTransaction(
					grant, boundary.bootId, boundary.elapsedRealtimeNanos, boundary.wallTimeMs,
				)
		}

	private suspend fun ambientStepsPolicyInactiveReasonInTransaction(
		leaseIdentity: AmbientReconciliationIdentity,
	): AmbientStepsDemandInactiveReason? {
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		if (
			authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			authority.currentPolicyRevision != leaseIdentity.policyRevision
		) {
			return AmbientStepsDemandInactiveReason.AUTHORITY_INACTIVE
		}
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, SourceKind.STEPS.stableCode)
		policy.ambientStepsInactiveReason(leaseIdentity)?.let { reason ->
			return reason
		}
		val rollout = trackingRolloutStateStore.load()
		return if (
			rollout.revision != leaseIdentity.rolloutRevision ||
			!rollout.isAmbientProductReachable(SourceKind.STEPS)
		) {
			AmbientStepsDemandInactiveReason.ROLLOUT_CONTAINED
		} else {
			null
		}
	}

	// The original inactive path retires only the exact current Steps purpose and keeps sibling authority.
	@Suppress("LongParameterList")
	private suspend fun retireAmbientStepsInTransaction(
		consumerId: String,
		currentDemands: List<SourceDemandEntity>,
		leaseIdentity: AmbientReconciliationIdentity,
		boundary: SourceDemandMutationBoundary,
		dao: com.adsamcik.tracker.shared.base.database.dao.SourceBrokerDao,
		reason: AmbientStepsDemandInactiveReason,
		retention: AmbientStepsRetentionAuthorityEntity?,
	): AmbientStepsDemandResult {
		val affectedSourceKinds = (currentDemands.map(SourceDemandEntity::sourceKind) + SourceKind.STEPS.stableCode).toSet()
		val priorReferences = currentAuthorityReferences(consumerId)
		val exact = currentDemands.singleOrNull()?.takeIf { demand ->
			retention != null &&
				AmbientStepsDemandIdentity.matches(demand, retention, leaseIdentity)
		}
		if (currentDemands.isNotEmpty() && exact == null) {
			return AmbientStepsDemandResult.Inactive(
				AmbientStepsDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
		}
		dao.retireConsumer(consumerId, boundary.bootId, boundary.elapsedRealtimeNanos, boundary.wallTimeMs)
		callerAuthorityRetirement.retireReferences(priorReferences, "AMBIENT_STEPS_RETIRED", boundary.wallTimeMs)
		rotateCurrentAuthorizationsInTransaction(
			affectedSourceKinds,
			boundary.bootId,
			boundary.elapsedRealtimeNanos,
			boundary.wallTimeMs,
		)
		return AmbientStepsDemandResult.Inactive(reason)
	}

	internal suspend fun retireExactAmbientStepsDemand(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean = database.withTransaction {
		require(consumerId.isNotBlank())
		require(leaseIdentity.source == AmbientTrackingSource.STEPS)
		val dao = database.sourceBrokerDao()
		val demands = dao.currentDemands(consumerId)
		if (demands.isEmpty()) {
			return@withTransaction true
		}
		val retentionPolicyId = leaseIdentity.retentionPolicyId ?: return@withTransaction false
		val retentionApprovalRevision =
			leaseIdentity.retentionApprovalRevision ?: return@withTransaction false
		val retention = database.ambientStepsFactRevisionDao().retentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			retentionPolicyId,
			retentionApprovalRevision,
		) ?: return@withTransaction false
		val demand = demands.singleOrNull()
		if (
			demand?.let { exactDemand ->
				AmbientStepsDemandIdentity.matches(exactDemand, retention, leaseIdentity)
			} != true
		) {
			return@withTransaction false
		}
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		check(retireDemandAuthority(demand, "AMBIENT_STEPS_RETIRED", wallTimeMs)) {
			"Unable to retire exact Ambient Steps caller authority"
		}
		rotateCurrentAuthorizationsInTransaction(
			setOf(SourceKind.STEPS.stableCode),
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		true
	}

	internal suspend fun ambientStepsRetirementPlan(
		consumerId: String,
	): AmbientStepsRetirementPlan = database.withTransaction {
		require(consumerId.isNotBlank())
		val demands = database.sourceBrokerDao().currentDemands(consumerId)
		if (demands.isEmpty()) {
			return@withTransaction AmbientStepsRetirementPlan.AlreadyRetired
		}
		val demand = demands.singleOrNull()?.takeIf {
			it.sourceKind == SourceKind.STEPS.stableCode &&
				it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		} ?: return@withTransaction AmbientStepsRetirementPlan.Unverifiable
		val binding = AmbientStepsDemandIdentity.parseLeaseBinding(demand.demandId)
			?: return@withTransaction AmbientStepsRetirementPlan.Unverifiable
		val retention = database.ambientStepsFactRevisionDao().retentionAuthorities(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		).singleOrNull { candidate ->
			AmbientStepsDemandIdentity.matchesRetention(demand, candidate)
		} ?: return@withTransaction AmbientStepsRetirementPlan.Unverifiable
		val identity = AmbientReconciliationIdentity(
			source = AmbientTrackingSource.STEPS,
			policyRevision = demand.sourcePolicyRevision,
			consentEpoch = demand.consentEpoch,
			collectedDataEpoch = retention.collectedDataEpoch,
			rolloutRevision = binding.rolloutRevision,
			ownerCasToken = binding.ownerCasToken,
			executionRevision = binding.executionRevision,
			retainedFromMs = retention.retainedFromMs,
			retentionPolicyId = retention.opaquePolicyId,
			retentionApprovalRevision = retention.approvalRevision,
		)
		if (!AmbientStepsDemandIdentity.matches(demand, retention, identity)) {
			return@withTransaction AmbientStepsRetirementPlan.Unverifiable
		}
		AmbientStepsRetirementPlan.Required(
			AmbientReconciliationLease(identity),
		)
	}

	/**
	 * Replaces the default-off Ambient Wi-Fi demand and its source-specific retention authority in
	 * one transaction. No provider or capability API is touched here.
	 */
	internal suspend fun replaceAmbientWifiDemand(
		consumerId: String,
		requested: Boolean,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		sourceCallerAuthorityReference: String? = null,
		retentionSnapshot: LiveAmbientRetentionSnapshot? = null,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance? = null,
	): AmbientRadioDemandResult {
		val operation: suspend () -> AmbientRadioDemandResult = {
			replaceAmbientWifiDemandUnderHeldLease(
				consumerId,
				requested,
				leaseIdentity,
				reconciliationAttempt,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
				sourceCallerAuthorityReference,
				retentionSnapshot,
				preparedCallerAcceptance,
			)
		}
		val guarded = if (requested) {
			withAmbientRadioMutationLease(leaseIdentity, operation)
		} else {
			withAmbientRadioReductionLease(leaseIdentity, operation)
		}
		return guarded.toDemandResult()
	}

	private suspend fun insertAmbientWifiAuthority(authority: AmbientRadioAuthority) {
		database.ambientWifiFactDao().insertAuthority(
			AmbientWifiAuthorityIntegrity.create(
				authority.authorityRevision,
				authority.state,
				authority.sourcePolicyRevision,
				authority.ambientConsentEpoch,
				authority.retentionPolicyId,
				authority.retentionApprovalRevision,
				authority.collectedDataEpoch,
				authority.scopeDeletionGeneration,
				authority.effectiveBootId,
				authority.effectiveElapsedRealtimeNanos,
				authority.effectiveWallTimeMs,
				authority.rolloutRevision,
				authority.ownerCasToken,
				authority.reconciliationAttempt,
				authority.demandId,
			),
		)
	}

	private suspend fun insertAmbientCellAuthority(authority: AmbientRadioAuthority) {
		database.ambientCellFactDao().insertAuthority(
			AmbientCellAuthorityIntegrity.create(
				authority.authorityRevision,
				authority.state,
				authority.sourcePolicyRevision,
				authority.ambientConsentEpoch,
				authority.retentionPolicyId,
				authority.retentionApprovalRevision,
				authority.collectedDataEpoch,
				authority.scopeDeletionGeneration,
				authority.effectiveBootId,
				authority.effectiveElapsedRealtimeNanos,
				authority.effectiveWallTimeMs,
				authority.rolloutRevision,
				authority.ownerCasToken,
				authority.reconciliationAttempt,
				authority.demandId,
			),
		)
	}

	internal suspend fun replaceAmbientWifiDemandUnderHeldLease(
		consumerId: String,
		requested: Boolean,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		sourceCallerAuthorityReference: String? = null,
		retentionSnapshot: LiveAmbientRetentionSnapshot? = null,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance? = null,
	): AmbientRadioDemandResult = database.withTransaction {
		replaceAmbientRadioDemandInTransaction(
					consumerId = consumerId,
					source = SourceKind.WIFI,
					requested = requested,
					leaseIdentity = leaseIdentity,
					reconciliationAttempt = reconciliationAttempt,
					bootId = bootId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
					sourceCallerAuthorityReference = sourceCallerAuthorityReference,
					latestAuthority = {
						database.ambientWifiFactDao().latestAuthority()
							?.takeIf(AmbientWifiAuthorityIntegrity::isAuthentic)
							?.toRadioAuthority()
					},
					maximumAuthorityRevision = {
						database.ambientWifiFactDao().maximumAuthorityRevision()
					},
					insertAuthority = ::insertAmbientWifiAuthority,
					retireExactDemand = { demand ->
						database.ambientWifiFactDao().retireExactAmbientDemand(
							demand.demandId,
							demand.consumerId,
							demand.sourceKind,
							demand.sourcePolicyRevision,
							demand.consentEpoch,
							bootId,
							elapsedRealtimeNanos,
							wallTimeMs,
						)
					},
					currentDeletionGeneration = { epoch ->
						val marker = database.ambientWifiFactDao().latestDeletionMarker(epoch)
						if (marker == null) {
							0L
						} else {
							marker.takeIf(AmbientWifiFactIntegrity::isAuthentic)
								?.deletionGeneration
						}
					},
					retentionSnapshot = retentionSnapshot,
					preparedCallerAcceptance = preparedCallerAcceptance,
				)
	}

	/**
	 * Replaces the default-off Ambient Cell demand and its source-specific retention authority in
	 * one transaction. The demand floor is callbacks only and can never request a refresh.
	 */
	internal suspend fun replaceAmbientCellDemand(
		consumerId: String,
		requested: Boolean,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		sourceCallerAuthorityReference: String? = null,
		retentionSnapshot: LiveAmbientRetentionSnapshot? = null,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance? = null,
	): AmbientRadioDemandResult {
		val operation: suspend () -> AmbientRadioDemandResult = {
			replaceAmbientCellDemandUnderHeldLease(
				consumerId,
				requested,
				leaseIdentity,
				reconciliationAttempt,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
				sourceCallerAuthorityReference,
				retentionSnapshot,
				preparedCallerAcceptance,
			)
		}
		val guarded = if (requested) {
			withAmbientRadioMutationLease(leaseIdentity, operation)
		} else {
			withAmbientRadioReductionLease(leaseIdentity, operation)
		}
		return guarded.toDemandResult()
	}

	internal suspend fun replaceAmbientCellDemandUnderHeldLease(
		consumerId: String,
		requested: Boolean,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		sourceCallerAuthorityReference: String? = null,
		retentionSnapshot: LiveAmbientRetentionSnapshot? = null,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance? = null,
	): AmbientRadioDemandResult = database.withTransaction {
		replaceAmbientRadioDemandInTransaction(
					consumerId = consumerId,
					source = SourceKind.CELL,
					requested = requested,
					leaseIdentity = leaseIdentity,
					reconciliationAttempt = reconciliationAttempt,
					bootId = bootId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
					sourceCallerAuthorityReference = sourceCallerAuthorityReference,
					latestAuthority = {
						database.ambientCellFactDao().latestAuthority()
							?.takeIf(AmbientCellAuthorityIntegrity::isAuthentic)
							?.toRadioAuthority()
					},
					maximumAuthorityRevision = {
						database.ambientCellFactDao().maximumAuthorityRevision()
					},
					insertAuthority = ::insertAmbientCellAuthority,
					retireExactDemand = { demand ->
						database.ambientCellFactDao().retireExactAmbientDemand(
							demand.demandId,
							demand.consumerId,
							demand.sourceKind,
							demand.sourcePolicyRevision,
							demand.consentEpoch,
							bootId,
							elapsedRealtimeNanos,
							wallTimeMs,
						)
					},
					currentDeletionGeneration = { epoch ->
						val marker = database.ambientCellFactDao().latestDeletionMarker(epoch)
						if (marker == null) {
							0L
						} else {
							marker.takeIf(AmbientCellFactIntegrity::isAuthentic)
								?.deletionGeneration
						}
					},
					retentionSnapshot = retentionSnapshot,
					preparedCallerAcceptance = preparedCallerAcceptance,
				)
	}

	internal suspend fun <T> withAmbientRadioMutationLease(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> =
		ambientRadioMutationLeaseGuard.mutateIfCurrent(identity, mutation)

	internal suspend fun <T> withAmbientRadioReductionLease(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> =
		ambientRadioMutationLeaseGuard.mutateReductionIfRetained(identity, mutation)

	internal suspend fun reduceAmbientWifiAfterRejectedCallerUnderHeldLease(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): AmbientRadioDemandResult = database.withTransaction {
		reduceAmbientRadioAfterRejectedCallerInTransaction(
			consumerId = consumerId,
			source = SourceKind.WIFI,
			leaseIdentity = leaseIdentity,
			reconciliationAttempt = reconciliationAttempt,
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
			latestAuthority = {
				database.ambientWifiFactDao().latestAuthority()
					?.takeIf(AmbientWifiAuthorityIntegrity::isAuthentic)
					?.toRadioAuthority()
			},
			maximumAuthorityRevision = {
				database.ambientWifiFactDao().maximumAuthorityRevision()
			},
			insertAuthority = ::insertAmbientWifiAuthority,
			retireExactDemand = { demand ->
				database.ambientWifiFactDao().retireExactAmbientDemand(
					demand.demandId,
					demand.consumerId,
					demand.sourceKind,
					demand.sourcePolicyRevision,
					demand.consentEpoch,
					bootId,
					elapsedRealtimeNanos,
					wallTimeMs,
				)
			},
		)
	}

	internal suspend fun reduceAmbientCellAfterRejectedCallerUnderHeldLease(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): AmbientRadioDemandResult = database.withTransaction {
		reduceAmbientRadioAfterRejectedCallerInTransaction(
			consumerId = consumerId,
			source = SourceKind.CELL,
			leaseIdentity = leaseIdentity,
			reconciliationAttempt = reconciliationAttempt,
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
			latestAuthority = {
				database.ambientCellFactDao().latestAuthority()
					?.takeIf(AmbientCellAuthorityIntegrity::isAuthentic)
					?.toRadioAuthority()
			},
			maximumAuthorityRevision = {
				database.ambientCellFactDao().maximumAuthorityRevision()
			},
			insertAuthority = ::insertAmbientCellAuthority,
			retireExactDemand = { demand ->
				database.ambientCellFactDao().retireExactAmbientDemand(
					demand.demandId,
					demand.consumerId,
					demand.sourceKind,
					demand.sourcePolicyRevision,
					demand.consentEpoch,
					bootId,
					elapsedRealtimeNanos,
					wallTimeMs,
				)
			},
		)
	}

	internal suspend fun reduceAmbientWifiDemandForRecovery(
		consumerId: String,
		plan: AmbientRadioRetirementPlan.Required,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): AmbientRadioDemandResult {
		require(plan.lease.identity.source == AmbientTrackingSource.WIFI)
		val inMemory = withAmbientRadioReductionLease(plan.lease.identity) {
			replaceAmbientWifiDemandUnderHeldLease(
				consumerId = consumerId,
				requested = false,
				leaseIdentity = plan.lease.identity,
				reconciliationAttempt = reconciliationAttempt,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			)
		}
		if (inMemory is AmbientRadioLeaseMutation.Applied) {
			return inMemory.value
		}
		return reduceAmbientRadioDemandFromDurablePlan(
			source = SourceKind.WIFI,
			consumerId = consumerId,
			plan = plan,
		) {
			replaceAmbientWifiDemandUnderHeldLease(
				consumerId = consumerId,
				requested = false,
				leaseIdentity = plan.lease.identity,
				reconciliationAttempt = reconciliationAttempt,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			)
		}
	}

	internal suspend fun reduceAmbientCellDemandForRecovery(
		consumerId: String,
		plan: AmbientRadioRetirementPlan.Required,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): AmbientRadioDemandResult {
		require(plan.lease.identity.source == AmbientTrackingSource.CELL)
		val inMemory = withAmbientRadioReductionLease(plan.lease.identity) {
			replaceAmbientCellDemandUnderHeldLease(
				consumerId = consumerId,
				requested = false,
				leaseIdentity = plan.lease.identity,
				reconciliationAttempt = reconciliationAttempt,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			)
		}
		if (inMemory is AmbientRadioLeaseMutation.Applied) {
			return inMemory.value
		}
		return reduceAmbientRadioDemandFromDurablePlan(
			source = SourceKind.CELL,
			consumerId = consumerId,
			plan = plan,
		) {
			replaceAmbientCellDemandUnderHeldLease(
				consumerId = consumerId,
				requested = false,
				leaseIdentity = plan.lease.identity,
				reconciliationAttempt = reconciliationAttempt,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			)
		}
	}

	private suspend fun reduceAmbientRadioDemandFromDurablePlan(
		source: SourceKind,
		consumerId: String,
		plan: AmbientRadioRetirementPlan.Required,
		reduce: suspend () -> AmbientRadioDemandResult,
	): AmbientRadioDemandResult = database.withTransaction {
		if (!isExactDurableAmbientRadioReductionPlan(source, consumerId, plan)) {
			return@withTransaction AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
		}
		reduce()
	}

	private suspend fun isExactDurableAmbientRadioReductionPlan(
		source: SourceKind,
		consumerId: String,
		plan: AmbientRadioRetirementPlan.Required,
	): Boolean {
		val identity = plan.lease.identity
		if (source !in setOf(SourceKind.WIFI, SourceKind.CELL) ||
			identity.source != source.toAmbientTrackingSource()) {
			return false
		}
		if (database.sourceBrokerDao().demandHistory(consumerId).filter { demand ->
			demand.status in setOf(SourceDemandEntity.STATUS_ACTIVE, SourceDemandEntity.STATUS_RETIRING,
				SourceDemandEntity.STATUS_BLOCKED)
		}.singleOrNull()?.takeIf { demand -> demand.matchesRadioReductionDemand(source, consumerId, plan) } == null) {
			return false
		}
		if (database.authenticatedAmbientRadioAuthority(source)
			?.takeIf { authority -> authority.matchesRadioReductionPlan(plan) } == null) {
			return false
		}
		val accepted = authenticateStoredSourceCallerAuthority(
			database.sourceCallerAuthorityDao().rows(plan.sourceCallerAuthorityReference.value),
			plan.sourceCallerAuthorityReference,
		)
		return accepted.authorizesAmbientRadioLease(identity)
	}

	internal suspend fun compensateAmbientWifiDemandUnderHeldLease(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		expectedDemandId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		expectedCallerAuthorityReference: SourceCallerReplayReference? = null,
	): AmbientRadioReconciliationAuthority? = database.withTransaction {
		compensateAmbientRadioDemandInTransaction(
			consumerId,
			SourceKind.WIFI,
			leaseIdentity,
			reconciliationAttempt,
			expectedDemandId,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
			expectedCallerAuthorityReference,
			latestAuthority = {
				database.ambientWifiFactDao().latestAuthority()
					?.takeIf(AmbientWifiAuthorityIntegrity::isAuthentic)
					?.toRadioAuthority()
			},
			maximumAuthorityRevision = {
				database.ambientWifiFactDao().maximumAuthorityRevision()
			},
			insertAuthority = ::insertAmbientWifiAuthority,
			retireExactDemand = { demand ->
				database.ambientWifiFactDao().retireExactAmbientDemand(
					demand.demandId,
					demand.consumerId,
					demand.sourceKind,
					demand.sourcePolicyRevision,
					demand.consentEpoch,
					bootId,
					elapsedRealtimeNanos,
					wallTimeMs,
				)
			},
		)
	}

	internal suspend fun compensateAmbientCellDemandUnderHeldLease(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		expectedDemandId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		expectedCallerAuthorityReference: SourceCallerReplayReference? = null,
	): AmbientRadioReconciliationAuthority? = database.withTransaction {
		compensateAmbientRadioDemandInTransaction(
			consumerId,
			SourceKind.CELL,
			leaseIdentity,
			reconciliationAttempt,
			expectedDemandId,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
			expectedCallerAuthorityReference,
			latestAuthority = {
				database.ambientCellFactDao().latestAuthority()
					?.takeIf(AmbientCellAuthorityIntegrity::isAuthentic)
					?.toRadioAuthority()
			},
			maximumAuthorityRevision = {
				database.ambientCellFactDao().maximumAuthorityRevision()
			},
			insertAuthority = ::insertAmbientCellAuthority,
			retireExactDemand = { demand ->
				database.ambientCellFactDao().retireExactAmbientDemand(
					demand.demandId,
					demand.consumerId,
					demand.sourceKind,
					demand.sourcePolicyRevision,
					demand.consentEpoch,
					bootId,
					elapsedRealtimeNanos,
					wallTimeMs,
				)
			},
		)
	}

	internal suspend fun ambientRadioReconciliationAuthority(
		source: SourceKind,
	): AmbientRadioReconciliationAuthority = database.withTransaction {
		require(source == SourceKind.WIFI || source == SourceKind.CELL)
		val policyAuthority = database.sourcePolicyDao().authority()
		val policyRevision = policyAuthority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE
		}?.currentPolicyRevision
		val consentEpoch = database.sourcePolicyDao().latestConsentEpoch(
			source.stableCode,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)?.epoch
		val evidence = database.sourceEvidenceStateDao().get()
		val rollout = trackingRolloutStateStore.load()
		val sourceAuthority = database.authenticatedAmbientRadioAuthority(source)?.takeIf { authority ->
			authority.sourcePolicyRevision == policyRevision && authority.ambientConsentEpoch == consentEpoch &&
				authority.collectedDataEpoch == evidence?.collectedDataEpoch && authority.rolloutRevision == rollout.revision
		}
		AmbientRadioReconciliationAuthority(
			source = source,
			policyRevision = policyRevision,
			ambientConsentEpoch = consentEpoch,
			collectedDataEpoch = evidence?.collectedDataEpoch,
			retainedFromMs = evidence?.retainedFromMs,
			rolloutRevision = rollout.revision,
			executionGeneration = sourceAuthority?.executionGeneration,
			authorityRevision = sourceAuthority?.authorityRevision,
			ownerCasToken = sourceAuthority?.ownerCasToken,
			reconciliationAttempt = sourceAuthority?.reconciliationAttempt,
		)
	}

	internal suspend fun ambientRadioRetirementPlan(
		source: SourceKind,
		consumerId: String,
	): AmbientRadioRetirementPlan = database.withTransaction {
		require(source == SourceKind.WIFI || source == SourceKind.CELL)
		require(consumerId.isNotBlank())
		val demands = database.sourceBrokerDao().currentDemands(consumerId)
		if (demands.hasInvalidAmbientRadioRetirementShape(source)) {
			return@withTransaction AmbientRadioRetirementPlan.Unverifiable
		}
		val demand = demands.singleOrNull()
		val authority = when (source) {
			SourceKind.WIFI -> database.ambientWifiFactDao().latestAuthority()?.let { stored ->
				if (!AmbientWifiAuthorityIntegrity.isAuthentic(stored)) {
					return@withTransaction AmbientRadioRetirementPlan.Unverifiable
				}
				stored.toRadioAuthority()
			}
			SourceKind.CELL -> database.ambientCellFactDao().latestAuthority()?.let { stored ->
				if (!AmbientCellAuthorityIntegrity.isAuthentic(stored)) {
					return@withTransaction AmbientRadioRetirementPlan.Unverifiable
				}
				stored.toRadioAuthority()
			}
			else -> error("Unsupported ambient radio source $source")
		}
		retainedAmbientRadioRetirementPlan(source, demand, authority)
	}

	private suspend fun retainedAmbientRadioRetirementPlan(
		source: SourceKind,
		demand: SourceDemandEntity?,
		authority: AmbientRadioAuthority?,
	): AmbientRadioRetirementPlan = if (
		authority == null || authority.state != AmbientWifiAuthorityEntity.STATE_ACTIVE
	) {
		if (demand == null) {
			AmbientRadioRetirementPlan.AlreadyRetired
		} else {
			AmbientRadioRetirementPlan.Unverifiable
		}
	} else {
		activeAmbientRadioRetirementPlan(source, demand, authority)
	}

	// Exact demand, current evidence and stored caller must all establish this retirement lease.
	@Suppress("ReturnCount")
	private suspend fun activeAmbientRadioRetirementPlan(
		source: SourceKind,
		demand: SourceDemandEntity?,
		authority: AmbientRadioAuthority,
	): AmbientRadioRetirementPlan {
		if (
			demand == null || !demand.matchesActiveRadioRetirement(authority)
		) {
			return AmbientRadioRetirementPlan.Unverifiable
		}
		val evidence = database.sourceEvidenceStateDao().get()
			?: return AmbientRadioRetirementPlan.Unverifiable
		if (evidence.collectedDataEpoch != authority.collectedDataEpoch) {
			return AmbientRadioRetirementPlan.Unverifiable
		}
		val lease = AmbientReconciliationLease(
			AmbientReconciliationIdentity(
				source = source.toAmbientTrackingSource(),
				policyRevision = authority.sourcePolicyRevision,
				consentEpoch = authority.ambientConsentEpoch,
				collectedDataEpoch = authority.collectedDataEpoch,
				rolloutRevision = authority.rolloutRevision,
				ownerCasToken = authority.ownerCasToken,
				executionRevision = authority.executionGeneration,
				retainedFromMs = evidence.retainedFromMs,
				retentionPolicyId = authority.retentionPolicyId,
				retentionApprovalRevision = authority.retentionApprovalRevision,
			),
		)
		val callerReference = demand.sourceCallerAuthorityReference
			?.takeIf(String::isNotBlank)
			?.let(::SourceCallerReplayReference)
			?: return AmbientRadioRetirementPlan.Unverifiable
		val accepted = authenticateStoredSourceCallerAuthority(
			database.sourceCallerAuthorityDao().rows(callerReference.value),
			callerReference,
		)
		if (!accepted.authorizesAmbientRadioLease(lease.identity)) {
			return AmbientRadioRetirementPlan.Unverifiable
		}
		return AmbientRadioRetirementPlan.Required(
			lease = lease,
			previousReconciliationAttempt = authority.reconciliationAttempt,
			expectedDemandId = demand.demandId,
			expectedAuthorityRevision = authority.authorityRevision,
			sourceCallerAuthorityReference = callerReference,
		)
	}

	@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun reduceAmbientRadioAfterRejectedCallerInTransaction(
		consumerId: String,
		source: SourceKind,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		latestAuthority: suspend () -> AmbientRadioAuthority?,
		maximumAuthorityRevision: suspend () -> Long,
		insertAuthority: suspend (AmbientRadioAuthority) -> Unit,
		retireExactDemand: suspend (SourceDemandEntity) -> Int,
	): AmbientRadioDemandResult {
		require(source == SourceKind.WIFI || source == SourceKind.CELL)
		require(consumerId.isNotBlank())
		require(reconciliationAttempt > 0L)
		require(bootId.isNotBlank())
		require(elapsedRealtimeNanos >= 0L && wallTimeMs >= 0L)
		if (leaseIdentity.source != source.toAmbientTrackingSource()) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
		}

		val demandHistory = database.sourceBrokerDao().demandHistory(consumerId)
		val nonterminalDemands = demandHistory.filter { demand ->
			demand.status == SourceDemandEntity.STATUS_ACTIVE ||
				demand.status == SourceDemandEntity.STATUS_RETIRING ||
				demand.status == SourceDemandEntity.STATUS_BLOCKED
		}
		if (nonterminalDemands.size > 1 || nonterminalDemands.any { demand ->
				demand.sourceKind != source.stableCode ||
					demand.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT
			}
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
			)
		}

		val authority = latestAuthority() ?: return AmbientRadioDemandResult.Inactive(
			if (nonterminalDemands.isEmpty()) {
				AmbientRadioDemandInactiveReason.AUTHORITY_INACTIVE
			} else {
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT
			},
		)
		// Fixed stored authority and reconciliation-lease correspondence; no live-policy or clock evaluation.
		@Suppress("ComplexCondition")
		if (
			authority.sourcePolicyRevision != leaseIdentity.policyRevision ||
			authority.ambientConsentEpoch != leaseIdentity.consentEpoch ||
			authority.retentionPolicyId != leaseIdentity.retentionPolicyId ||
			authority.retentionApprovalRevision != leaseIdentity.retentionApprovalRevision ||
			authority.collectedDataEpoch != leaseIdentity.collectedDataEpoch ||
			authority.rolloutRevision != leaseIdentity.rolloutRevision ||
			authority.executionGeneration != leaseIdentity.executionRevision ||
			authority.ownerCasToken != leaseIdentity.ownerCasToken
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
		}
		if (authority.reconciliationAttempt > reconciliationAttempt) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_ATTEMPT,
			)
		}
		if (authority.state != AmbientWifiAuthorityEntity.STATE_ACTIVE &&
			authority.state != AmbientWifiAuthorityEntity.STATE_REVOKED
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
			)
		}

		val demandId = authority.demandId ?: return AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
		)
		val demand = demandHistory.singleOrNull { candidate ->
			candidate.demandId == demandId &&
				candidate.consumerId == consumerId &&
				candidate.sourceKind == source.stableCode &&
				candidate.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				candidate.sourcePolicyRevision == leaseIdentity.policyRevision &&
				candidate.consentEpoch == leaseIdentity.consentEpoch &&
				candidate.liveAmbientRetentionPolicyId == leaseIdentity.retentionPolicyId &&
				candidate.liveAmbientRetentionApprovalRevision ==
				leaseIdentity.retentionApprovalRevision &&
				!candidate.sourceCallerAuthorityReference.isNullOrBlank()
		} ?: return AmbientRadioDemandResult.Inactive(
			AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
		)
		if (nonterminalDemands.any { it.demandId != demandId }) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
			)
		}

		val callerReference = requireNotNull(demand.sourceCallerAuthorityReference)
			.toCallerReferenceOrNull()
			?: return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
			)
		val expectedCallerIdentity = SourceCallerDemandIdentity(
			leaseIdentity.purposeLeaseIdentity,
			manifestIdentity = null,
		)
		val callerAuthority = authenticateStoredSourceCallerAuthority(
			database.sourceCallerAuthorityDao().rows(callerReference.value),
			callerReference,
		)
		val callerAuthorityOwnsDemand =
			callerAuthority is StoredSourceCallerAuthorityLoadResult.Available &&
				callerAuthority.authority.origin == StoredSourceCallerOrigin.PURPOSE_OWNER &&
				callerAuthority.authority.purpose == TrackingPurpose.AMBIENT_PRODUCT &&
				callerAuthority.authority.permittedDemandIdentities ==
				setOf(expectedCallerIdentity)
		val preserveUnrelatedCallerAuthority =
			callerAuthority is StoredSourceCallerAuthorityLoadResult.Available &&
				!callerAuthorityOwnsDemand

		val authorityWasActive = authority.state == AmbientWifiAuthorityEntity.STATE_ACTIVE
		val revokedAuthority = if (authorityWasActive) {
			val maximumRevision = maximumAuthorityRevision()
			if (maximumRevision == Long.MAX_VALUE) {
				return AmbientRadioDemandResult.Inactive(
					AmbientRadioDemandInactiveReason.AUTHORITY_REVISION_EXHAUSTED,
				)
			}
			authority.copy(
				authorityRevision = maximumRevision + 1L,
				state = AmbientWifiAuthorityEntity.STATE_REVOKED,
				effectiveBootId = bootId,
				effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
				effectiveWallTimeMs = wallTimeMs,
				reconciliationAttempt = reconciliationAttempt,
			)
		} else {
			authority
		}
		if (demand.status != SourceDemandEntity.STATUS_RETIRED &&
			retireExactDemand(demand) != 1
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
			)
		}
		if (!preserveUnrelatedCallerAuthority) {
			check(retireDemandAuthority(
				demand,
				"AMBIENT_RADIO_CALLER_REJECTED",
				wallTimeMs,
			)) {
				"Unable to retire rejected Ambient radio caller authority"
			}
		}
		if (authorityWasActive) {
			insertAuthority(revokedAuthority)
		}
		rotateCurrentAuthorizationInTransaction(
			source.stableCode,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		return AmbientRadioDemandResult.Inactive(
			reason = AmbientRadioDemandInactiveReason.AUTHORITY_INACTIVE,
			reconciliationAuthority = AmbientRadioReconciliationAuthority(
				source = source,
				policyRevision = leaseIdentity.policyRevision,
				ambientConsentEpoch = leaseIdentity.consentEpoch,
				collectedDataEpoch = leaseIdentity.collectedDataEpoch,
				retainedFromMs = leaseIdentity.retainedFromMs,
				rolloutRevision = leaseIdentity.rolloutRevision,
				executionGeneration = revokedAuthority.executionGeneration,
				authorityRevision = revokedAuthority.authorityRevision,
				ownerCasToken = leaseIdentity.ownerCasToken,
				reconciliationAttempt = revokedAuthority.reconciliationAttempt,
			),
			retiredDemandId = demandId,
		)
	}

	// Keep missing exact demand, historical sibling, changed authority, failed retirement and exhausted revision exits explicit.
	@Suppress("LongParameterList", "ReturnCount")
	private suspend fun compensateAmbientRadioDemandInTransaction(
		consumerId: String,
		source: SourceKind,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		expectedDemandId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		expectedCallerAuthorityReference: SourceCallerReplayReference?,
		latestAuthority: suspend () -> AmbientRadioAuthority?,
		maximumAuthorityRevision: suspend () -> Long,
		insertAuthority: suspend (AmbientRadioAuthority) -> Unit,
		retireExactDemand: suspend (SourceDemandEntity) -> Int,
	): AmbientRadioReconciliationAuthority? {
		val boundary = SourceDemandMutationBoundary(bootId, elapsedRealtimeNanos, wallTimeMs)
		requireRadioCompensationInput(source, leaseIdentity, reconciliationAttempt, expectedDemandId, consumerId, boundary)
		val demandHistory = database.sourceBrokerDao().demandHistory(consumerId)
		val demand = demandHistory.singleOrNull {
			it.matchesRadioCompensationDemand(source, leaseIdentity, expectedDemandId, expectedCallerAuthorityReference)
		} ?: return null
		if (demandHistory.hasOtherNonterminalRadioDemand(expectedDemandId)) {
			return compensateHistoricalAmbientRadioDemand(
				source, leaseIdentity, reconciliationAttempt, demand, wallTimeMs,
			)
		}
		val authority = latestAuthority()?.takeIf {
			it.matchesRadioCompensationAuthority(leaseIdentity, reconciliationAttempt, expectedDemandId)
		}
		if (!retireCompensatedRadioDemand(demand, authority, retireExactDemand)) {
			return null
		}
		val revokedAuthority = when (authority?.state) {
			AmbientWifiAuthorityEntity.STATE_ACTIVE -> {
				val maximumRevision = maximumAuthorityRevision()
				.takeUnless { it == Long.MAX_VALUE } ?: return null
				authority.copy(
					authorityRevision = maximumRevision + 1L,
					state = AmbientWifiAuthorityEntity.STATE_REVOKED,
					effectiveBootId = bootId,
					effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
					effectiveWallTimeMs = wallTimeMs,
				).also { insertAuthority(it) }
			}
			AmbientWifiAuthorityEntity.STATE_REVOKED -> authority
			else -> null
		}
		return finishAmbientRadioCompensation(
			source, leaseIdentity, reconciliationAttempt, demand, revokedAuthority, boundary,
		)
	}

	private suspend fun retireCompensatedRadioDemand(
		demand: SourceDemandEntity,
		authority: AmbientRadioAuthority?,
		retireExactDemand: suspend (SourceDemandEntity) -> Int,
	): Boolean {
		if (authority == null && demand.status != SourceDemandEntity.STATUS_RETIRED) {
			return false
		}
		val retiredDemand = demand.status != SourceDemandEntity.STATUS_RETIRED
		if (retiredDemand && retireExactDemand(demand) != 1) {
			return false
		}
		return true
	}

	private suspend fun finishAmbientRadioCompensation(
		source: SourceKind,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		demand: SourceDemandEntity,
		revokedAuthority: AmbientRadioAuthority?,
		boundary: SourceDemandMutationBoundary,
	): AmbientRadioReconciliationAuthority {
		check(retireDemandAuthority(demand, "AMBIENT_RADIO_COMPENSATED", boundary.wallTimeMs)) {
			"Unable to retire exact Ambient radio caller authority"
		}
		rotateCurrentAuthorizationInTransaction(
			source.stableCode,
			boundary.bootId,
			boundary.elapsedRealtimeNanos,
			boundary.wallTimeMs,
		)
		return AmbientRadioReconciliationAuthority(
			source = source,
			policyRevision = leaseIdentity.policyRevision,
			ambientConsentEpoch = leaseIdentity.consentEpoch,
			collectedDataEpoch = leaseIdentity.collectedDataEpoch,
			retainedFromMs = leaseIdentity.retainedFromMs,
			rolloutRevision = leaseIdentity.rolloutRevision,
			executionGeneration = revokedAuthority?.executionGeneration
				?: leaseIdentity.executionRevision.takeIf { it > 0L },
			authorityRevision = revokedAuthority?.authorityRevision,
			ownerCasToken = leaseIdentity.ownerCasToken,
			reconciliationAttempt = reconciliationAttempt,
		)
	}

	private suspend fun compensateHistoricalAmbientRadioDemand(
		source: SourceKind,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		demand: SourceDemandEntity,
		wallTimeMs: Long,
	): AmbientRadioReconciliationAuthority? {
		if (demand.status != SourceDemandEntity.STATUS_RETIRED) {
			return null
		}
		check(retireDemandAuthority(demand, "AMBIENT_RADIO_COMPENSATED", wallTimeMs)) {
			"Unable to retire exact historical Ambient radio caller authority"
		}
		return AmbientRadioReconciliationAuthority(
			source = source,
			policyRevision = leaseIdentity.policyRevision,
			ambientConsentEpoch = leaseIdentity.consentEpoch,
			collectedDataEpoch = leaseIdentity.collectedDataEpoch,
			retainedFromMs = leaseIdentity.retainedFromMs,
			rolloutRevision = leaseIdentity.rolloutRevision,
			executionGeneration = leaseIdentity.executionRevision.takeIf { it > 0L },
			authorityRevision = null,
			ownerCasToken = leaseIdentity.ownerCasToken,
			reconciliationAttempt = reconciliationAttempt,
		)
	}

	@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun replaceAmbientRadioDemandInTransaction(
		consumerId: String,
		source: SourceKind,
		requested: Boolean,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		latestAuthority: suspend () -> AmbientRadioAuthority?,
		maximumAuthorityRevision: suspend () -> Long,
		insertAuthority: suspend (AmbientRadioAuthority) -> Unit,
		retireExactDemand: suspend (SourceDemandEntity) -> Int,
		currentDeletionGeneration: suspend (Long) -> Long?,
		sourceCallerAuthorityReference: String?,
		retentionSnapshot: LiveAmbientRetentionSnapshot?,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance?,
	): AmbientRadioDemandResult {
		require(source == SourceKind.WIFI || source == SourceKind.CELL)
		require(consumerId.isNotBlank())
		require(reconciliationAttempt > 0L)
		require(bootId.isNotBlank())
		require(elapsedRealtimeNanos >= 0L && wallTimeMs >= 0L)
		val callerAuthorityReference = preparedCallerAcceptance?.receipt?.reference?.value
			?: sourceCallerAuthorityReference
		if (leaseIdentity.source != source.toAmbientTrackingSource()) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
		}
		val dao = database.sourceBrokerDao()
		val currentDemands = dao.currentDemands(consumerId)
		if (currentDemands.size > 1 || currentDemands.any {
				it.sourceKind != source.stableCode ||
					it.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT
			}
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
			)
		}
		val currentDemand = currentDemands.singleOrNull()
		val priorAuthority = latestAuthority()
		val rollout = trackingRolloutStateStore.load()
		val policyAuthority = database.sourcePolicyDao().authority()
		val policy = policyAuthority?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				it.currentPolicyRevision == leaseIdentity.policyRevision
		}?.let {
			database.sourcePolicyDao().policyAtRevision(
				leaseIdentity.policyRevision,
				source.stableCode,
			)
		}
		val consent = database.sourcePolicyDao().latestConsentEpoch(
			source.stableCode,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		)
		val evidence = database.sourceEvidenceStateDao().get()
		if (!requested) {
			if (currentDemand == null) {
				return if (priorAuthority?.state == AmbientWifiAuthorityEntity.STATE_ACTIVE) {
					AmbientRadioDemandResult.Inactive(
						AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
					)
				} else {
					AmbientRadioDemandResult.Inactive(
						AmbientRadioDemandInactiveReason.REQUEST_DISABLED,
					)
				}
			}
			val activeAuthority = priorAuthority?.takeIf {
				it.state == AmbientWifiAuthorityEntity.STATE_ACTIVE
			} ?: return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
			// Fixed active owner, saved demand and lease tuple; keep each identity dimension visible.
			@Suppress("ComplexCondition")
			if (
				activeAuthority.sourcePolicyRevision != leaseIdentity.policyRevision ||
				activeAuthority.ambientConsentEpoch != leaseIdentity.consentEpoch ||
				activeAuthority.collectedDataEpoch != leaseIdentity.collectedDataEpoch ||
				activeAuthority.rolloutRevision != leaseIdentity.rolloutRevision ||
				activeAuthority.executionGeneration != leaseIdentity.executionRevision ||
				activeAuthority.retentionPolicyId != leaseIdentity.retentionPolicyId ||
				activeAuthority.retentionApprovalRevision !=
				leaseIdentity.retentionApprovalRevision ||
				activeAuthority.ownerCasToken != leaseIdentity.ownerCasToken ||
				activeAuthority.demandId != currentDemand.demandId ||
				currentDemand.liveAmbientRetentionPolicyId !=
				leaseIdentity.retentionPolicyId ||
				currentDemand.liveAmbientRetentionApprovalRevision !=
				leaseIdentity.retentionApprovalRevision
			) {
				return AmbientRadioDemandResult.Inactive(
					AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
				)
			}
		}
		if (priorAuthority?.ownerCasToken == leaseIdentity.ownerCasToken &&
			priorAuthority.reconciliationAttempt > reconciliationAttempt
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_ATTEMPT,
			)
		}
		if (priorAuthority?.ownerCasToken == leaseIdentity.ownerCasToken &&
			priorAuthority.reconciliationAttempt == reconciliationAttempt &&
			(!requested || priorAuthority.state != AmbientWifiAuthorityEntity.STATE_ACTIVE)
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_ATTEMPT,
			)
		}
		if (currentDemand != null &&
			(priorAuthority?.state != AmbientWifiAuthorityEntity.STATE_ACTIVE ||
				priorAuthority.demandId != currentDemand.demandId)
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
			)
		}

		suspend fun nextAuthorityRevision(): Long? {
			val current = maximumAuthorityRevision()
			return if (current == Long.MAX_VALUE) {
				null
			} else {
				current + 1L
			}
		}

		suspend fun retireCurrentDemand(): Boolean {
			val demand = currentDemand ?: return true
			val retired = retireExactDemand(demand) == 1
			if (retired) {
				check(retireDemandAuthority(demand, "AMBIENT_RADIO_RETIRED", wallTimeMs)) {
					"Unable to retire exact Ambient radio caller authority"
				}
			}
			return retired
		}

		fun ownerReconciliationAuthority(
			executionAuthority: AmbientRadioAuthority?,
		): AmbientRadioReconciliationAuthority = AmbientRadioReconciliationAuthority(
			source = source,
			policyRevision = leaseIdentity.policyRevision,
			ambientConsentEpoch = leaseIdentity.consentEpoch,
			collectedDataEpoch = leaseIdentity.collectedDataEpoch,
			retainedFromMs = leaseIdentity.retainedFromMs,
			rolloutRevision = leaseIdentity.rolloutRevision,
			executionGeneration = executionAuthority?.executionGeneration,
			authorityRevision = executionAuthority?.authorityRevision,
			ownerCasToken = leaseIdentity.ownerCasToken,
			reconciliationAttempt = reconciliationAttempt,
		)

		suspend fun revoke(reason: AmbientRadioDemandInactiveReason): AmbientRadioDemandResult {
			val revision = if (priorAuthority?.state == AmbientWifiAuthorityEntity.STATE_ACTIVE) {
				nextAuthorityRevision() ?: return AmbientRadioDemandResult.Inactive(
					AmbientRadioDemandInactiveReason.AUTHORITY_REVISION_EXHAUSTED,
				)
			} else {
				null
			}
			if (!retireCurrentDemand()) {
				return AmbientRadioDemandResult.Inactive(
					AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
				)
			}
			val revokedAuthority = if (
				priorAuthority?.state == AmbientWifiAuthorityEntity.STATE_ACTIVE
			) {
				priorAuthority.copy(
					authorityRevision = requireNotNull(revision),
					state = AmbientWifiAuthorityEntity.STATE_REVOKED,
					sourcePolicyRevision = leaseIdentity.policyRevision,
					ambientConsentEpoch = leaseIdentity.consentEpoch,
					collectedDataEpoch = leaseIdentity.collectedDataEpoch,
					effectiveBootId = bootId,
					effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
					effectiveWallTimeMs = wallTimeMs,
					rolloutRevision = leaseIdentity.rolloutRevision,
					ownerCasToken = leaseIdentity.ownerCasToken,
					reconciliationAttempt = reconciliationAttempt,
				).also { insertAuthority(it) }
			} else {
				null
			}
			if (currentDemand != null) {
				rotateCurrentAuthorizationInTransaction(
					source.stableCode,
					bootId,
					elapsedRealtimeNanos,
					wallTimeMs,
				)
			}
			return AmbientRadioDemandResult.Inactive(
				reason,
				ownerReconciliationAuthority(revokedAuthority),
				currentDemand?.demandId,
			)
		}

		if (!requested) {
			return revoke(AmbientRadioDemandInactiveReason.REQUEST_DISABLED)
		}
		// Fixed policy, consent, evidence and rollout snapshot correspondence.
		@Suppress("ComplexCondition")
		if (policy == null ||
			consent == null ||
			consent.epoch != leaseIdentity.consentEpoch ||
			consent.policyRevision != leaseIdentity.policyRevision ||
			evidence?.collectedDataEpoch != leaseIdentity.collectedDataEpoch ||
			evidence?.retainedFromMs != leaseIdentity.retainedFromMs ||
			rollout.revision != leaseIdentity.rolloutRevision
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
		}
		val consentEpoch = policy.ambientConsentEpoch
			?: return revoke(AmbientRadioDemandInactiveReason.CONSENT_REVOKED)
		if (consentEpoch != leaseIdentity.consentEpoch || !consent.eligible) {
			return revoke(AmbientRadioDemandInactiveReason.CONSENT_REVOKED)
		}
		if (!policy.ambientPersistenceEligible || !consent.persistenceEligible) {
			return revoke(AmbientRadioDemandInactiveReason.PERSISTENCE_INELIGIBLE)
		}
		val exactRetentionSnapshot = retentionSnapshot
			?: return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING)
		if (!exactRetentionSnapshot.matchesActiveSettlementInTransaction()) {
			return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISMATCH)
		}
		val approval = exactRetentionSnapshot.grants[source]
			?: return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING)
		if (!approval.matchesAmbientLease(leaseIdentity) ||
			approval.retainedFromMs != requireNotNull(evidence).retainedFromMs ||
			!isLiveAmbientRetentionCurrentInTransaction(
				approval,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		) {
			return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISMATCH)
		}
		if (!rollout.isAmbientProductReachable(source)) {
			return revoke(AmbientRadioDemandInactiveReason.ROLLOUT_CONTAINED)
		}
		val deletionGeneration = currentDeletionGeneration(leaseIdentity.collectedDataEpoch)
			?: return revoke(AmbientRadioDemandInactiveReason.DELETION_AUTHORITY_MISMATCH)
		val contract = if (policy.balanceProfileVersion != null) {
            val baseline = policy.balanceLevel
            // Complete persisted five-level profile correspondence.
            @Suppress("ComplexCondition")
            if (policy.balanceProfileVersion != 1 || baseline == null || baseline !in 1..5 ||
                policy.overallBalanceLevel?.let { it in 1..5 } != true ||
                baseline != (policy.sourceBalanceOverride ?: policy.overallBalanceLevel)) {
                return revoke(AmbientRadioDemandInactiveReason.AUTHORITY_INACTIVE)
            }
            SourceDemandContractFactory.forProfile(source, 1, baseline, DirectSourceDemandPurpose.AMBIENT_PRODUCT)
        } else {
			SourceDemandContractFactory.forQos(
            source, 0, DirectSourceDemandPurpose.AMBIENT_PRODUCT,
        )
		}
		val unchangedDemand = currentDemands.singleOrNull()?.takeIf { demand ->
			demand.status == SourceDemandEntity.STATUS_ACTIVE &&
				demand.sourceKind == source.stableCode &&
				demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				demand.sourcePolicyRevision == policy.policyRevision &&
				demand.consentEpoch == consentEpoch &&
				demand.persistenceEligible &&
				demand.qosCode == 0 &&
				demand.minimumAcquisitionSpec == contract.encodeFloor() &&
				demand.maximumAgeMs == contract.maximumProviderItemAgeMs &&
				demand.desiredLatencyMs == contract.targetPlanningLatencyMs &&
				demand.requestedDeliveryLatencyMs == null &&
				demand.requestedBootId == bootId &&
				demand.liveAmbientRetentionPolicyId == approval.opaquePolicyId &&
				demand.liveAmbientRetentionApprovalRevision == approval.approvalRevision &&
				demand.sourceCallerAuthorityReference == callerAuthorityReference
		}
		val unchangedAuthority = priorAuthority?.takeIf { authority ->
			authority.state == AmbientWifiAuthorityEntity.STATE_ACTIVE &&
				authority.sourcePolicyRevision == policy.policyRevision &&
				authority.ambientConsentEpoch == consentEpoch &&
				authority.retentionPolicyId == approval.opaquePolicyId &&
				authority.retentionApprovalRevision == approval.approvalRevision &&
				authority.collectedDataEpoch == leaseIdentity.collectedDataEpoch &&
				authority.scopeDeletionGeneration == deletionGeneration &&
				authority.effectiveBootId == bootId
		}
		if (unchangedDemand != null && unchangedAuthority != null) {
			val exactRetry = unchangedAuthority.ownerCasToken == leaseIdentity.ownerCasToken &&
				unchangedAuthority.reconciliationAttempt == reconciliationAttempt &&
				unchangedAuthority.rolloutRevision == rollout.revision
			val effectiveAuthority = if (exactRetry) {
				unchangedAuthority
			} else {
				val revision = nextAuthorityRevision()
					?: return revoke(
						AmbientRadioDemandInactiveReason.AUTHORITY_REVISION_EXHAUSTED,
					)
				unchangedAuthority.copy(
					authorityRevision = revision,
					rolloutRevision = rollout.revision,
					ownerCasToken = leaseIdentity.ownerCasToken,
					reconciliationAttempt = reconciliationAttempt,
				).also { insertAuthority(it) }
			}
			return AmbientRadioDemandResult.Active(
				unchangedDemand,
				effectiveAuthority.authorityRevision,
				AmbientRadioReconciliationAuthority(
					source = source,
					policyRevision = policy.policyRevision,
					ambientConsentEpoch = consentEpoch,
					collectedDataEpoch = leaseIdentity.collectedDataEpoch,
					retainedFromMs = leaseIdentity.retainedFromMs,
					rolloutRevision = rollout.revision,
					executionGeneration = effectiveAuthority.executionGeneration,
					authorityRevision = effectiveAuthority.authorityRevision,
					ownerCasToken = effectiveAuthority.ownerCasToken,
					reconciliationAttempt = effectiveAuthority.reconciliationAttempt,
				),
			)
		}
		val authorityRevision = nextAuthorityRevision()
			?: return revoke(AmbientRadioDemandInactiveReason.AUTHORITY_REVISION_EXHAUSTED)
		if (!retireCurrentDemand()) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.OWNERSHIP_CONFLICT,
			)
		}
		persistPreparedCallerAuthorityInTransaction(
			preparedCallerAcceptance,
			callerAuthorityReference,
			leaseIdentity.purposeLeaseIdentity,
		)
		val demand = SourceDemandEntity(
			demandId = demandId(
				consumerId,
				source.stableCode,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
				policy.policyRevision,
				consentEpoch,
				null,
				bootId,
				elapsedRealtimeNanos,
				contract.encodeFloor() +
					"|retention=${approval.opaquePolicyId}:${approval.approvalRevision}",
			),
			consumerId = consumerId,
			sourceKind = source.stableCode,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = policy.policyRevision,
			consentEpoch = consentEpoch,
			persistenceEligible = true,
			qosCode = 0,
			minimumAcquisitionSpec = contract.encodeFloor(),
			adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
			maximumAgeMs = contract.maximumProviderItemAgeMs,
			desiredLatencyMs = contract.targetPlanningLatencyMs,
			requestedDeliveryLatencyMs = null,
			requestedBootId = bootId,
			requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
			requestedAtMs = wallTimeMs,
			status = SourceDemandEntity.STATUS_ACTIVE,
			retireBootId = null,
			retireElapsedRealtimeNanos = null,
			retiredAtMs = null,
			sourceCallerAuthorityReference = callerAuthorityReference,
			liveAmbientRetentionPolicyId = approval.opaquePolicyId,
			liveAmbientRetentionApprovalRevision = approval.approvalRevision,
		)
		dao.insertDemands(listOf(demand))
		insertAuthority(
			AmbientRadioAuthority(
				authorityRevision = authorityRevision,
				state = AmbientWifiAuthorityEntity.STATE_ACTIVE,
				sourcePolicyRevision = policy.policyRevision,
				ambientConsentEpoch = consentEpoch,
				retentionPolicyId = approval.opaquePolicyId,
				retentionApprovalRevision = approval.approvalRevision,
				collectedDataEpoch = leaseIdentity.collectedDataEpoch,
				scopeDeletionGeneration = deletionGeneration,
				effectiveBootId = bootId,
				effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
				effectiveWallTimeMs = wallTimeMs,
				rolloutRevision = rollout.revision,
				ownerCasToken = leaseIdentity.ownerCasToken,
				reconciliationAttempt = reconciliationAttempt,
				demandId = demand.demandId,
				executionGeneration = when (source) {
					SourceKind.WIFI -> AmbientWifiAuthorityEntity.FIRST_WRITER_OWNER_GENERATION
					SourceKind.CELL -> AmbientCellAuthorityEntity.FIRST_WRITER_OWNER_GENERATION
					else -> error("Unsupported ambient radio source $source")
				},
			),
		)
		rotateCurrentAuthorizationInTransaction(
			source.stableCode,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		return AmbientRadioDemandResult.Active(
			demand,
			authorityRevision,
			AmbientRadioReconciliationAuthority(
				source = source,
				policyRevision = policy.policyRevision,
				ambientConsentEpoch = consentEpoch,
				collectedDataEpoch = leaseIdentity.collectedDataEpoch,
				retainedFromMs = leaseIdentity.retainedFromMs,
				rolloutRevision = rollout.revision,
				executionGeneration = when (source) {
					SourceKind.WIFI -> AmbientWifiAuthorityEntity.FIRST_WRITER_OWNER_GENERATION
					SourceKind.CELL -> AmbientCellAuthorityEntity.FIRST_WRITER_OWNER_GENERATION
					else -> error("Unsupported ambient radio source $source")
				},
				authorityRevision = authorityRevision,
				ownerCasToken = leaseIdentity.ownerCasToken,
				reconciliationAttempt = reconciliationAttempt,
			),
		)
	}

	suspend fun registrationAuthorization(
		source: SourceKind,
		sourceInstanceId: String,
		registrationGeneration: Long,
		bootId: String,
		physicalConfigurationFingerprint: String,
		observedElapsedRealtimeNanos: Long,
	): ProviderRegistrationAuthorization? = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val generation = dao.registrationAtObservedTime(
			source.stableCode,
			registrationGeneration,
			sourceInstanceId,
			bootId,
			physicalConfigurationFingerprint,
			observedElapsedRealtimeNanos,
		)
			?: return@withTransaction null
		val authorization = dao.authorizationAt(
			source.stableCode,
			registrationGeneration,
			bootId,
			observedElapsedRealtimeNanos,
		).toAuthorizationSnapshotOrNull() ?: return@withTransaction null
		if (authorization.isDenied) {
			return@withTransaction null
		}
		ProviderRegistrationAuthorization(
			generation,
			authorization,
		)
	}

	private suspend fun rotateCurrentAuthorizationsInTransaction(
		sourceKinds: Collection<Int>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		sourceKinds.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(
				sourceKind,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}
	}

	private suspend fun rotateCurrentAuthorizationInTransaction(
		sourceKind: Int,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		database.rotateCurrentSourceAuthorizationInTransaction(
			sourceKind = sourceKind,
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
		)
	}

	private suspend fun captureLiveAmbientRetentionGrant(
		source: SourceKind,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
		expectedRetainedFromMs: Long?,
		settlementOperationId: String?,
	): LiveAmbientRetentionGrant? {
		if (sourcePolicyRevision <= 0L || ambientConsentEpoch <= 0L || collectedDataEpoch < 0L) {
			return null
		}
		val approved = readApprovedAmbientRetention(source, sourcePolicyRevision, ambientConsentEpoch,
			collectedDataEpoch, expectedRetainedFromMs, settlementOperationId) ?: return null
		return if (approved.collectedDataEpoch == collectedDataEpoch &&
			approved.retainedFromMs == expectedRetainedFromMs &&
			isApprovedAmbientRetentionCurrent(source, sourcePolicyRevision, ambientConsentEpoch,
				collectedDataEpoch, approved, currentBootId, currentElapsedRealtimeNanos,
				currentWallTimeMs, settlementOperationId)
		) {
			approved.toLiveAmbientRetentionGrant(source, sourcePolicyRevision, ambientConsentEpoch)
		} else {
			null
		}
	}

	private suspend fun readApprovedAmbientRetention(
		source: SourceKind,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		expectedRetainedFromMs: Long?,
		settlementOperationId: String?,
	): CurrentRetentionAuthority.Approved? = try {
		val current = if (settlementOperationId == null) {
			retentionAuthorityReader.currentLiveAmbient(
				source = source.toTrackingSourceComponent(),
				expectedSourcePolicyRevision = sourcePolicyRevision,
				expectedAmbientConsentEpoch = ambientConsentEpoch,
				expectedCollectedDataEpoch = collectedDataEpoch,
				expectedRetainedFromMs = expectedRetainedFromMs,
			)
		} else {
			retentionAuthorityReader.currentLiveAmbientForSettlement(
				source = source.toTrackingSourceComponent(),
				expectedSourcePolicyRevision = sourcePolicyRevision,
				expectedAmbientConsentEpoch = ambientConsentEpoch,
				expectedCollectedDataEpoch = collectedDataEpoch,
				expectedRetainedFromMs = expectedRetainedFromMs,
				settlementOperationId = settlementOperationId,
			)
		}
		current as? CurrentRetentionAuthority.Approved
	} catch (cancelled: kotlinx.coroutines.CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		null
	}

	private suspend fun isApprovedAmbientRetentionCurrent(
		source: SourceKind,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		approved: CurrentRetentionAuthority.Approved,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
		settlementOperationId: String?,
	): Boolean = try {
		if (settlementOperationId == null) {
			retentionAuthorityReader.isCurrentLiveAmbientAt(
				source = source.toTrackingSourceComponent(),
				expectedSourcePolicyRevision = sourcePolicyRevision,
				expectedAmbientConsentEpoch = ambientConsentEpoch,
				expectedCollectedDataEpoch = collectedDataEpoch,
				expectedRetainedFromMs = approved.retainedFromMs,
				expectedOpaquePolicyId = approved.opaquePolicyId,
				expectedApprovalRevision = approved.approvalRevision,
				currentBootId = currentBootId,
				currentElapsedRealtimeNanos = currentElapsedRealtimeNanos,
				currentWallTimeMs = currentWallTimeMs,
			)
		} else {
			retentionAuthorityReader.isCurrentLiveAmbientForSettlementAt(
				source = source.toTrackingSourceComponent(),
				expectedSourcePolicyRevision = sourcePolicyRevision,
				expectedAmbientConsentEpoch = ambientConsentEpoch,
				expectedCollectedDataEpoch = collectedDataEpoch,
				expectedRetainedFromMs = approved.retainedFromMs,
				expectedOpaquePolicyId = approved.opaquePolicyId,
				expectedApprovalRevision = approved.approvalRevision,
				currentBootId = currentBootId,
				currentElapsedRealtimeNanos = currentElapsedRealtimeNanos,
				currentWallTimeMs = currentWallTimeMs,
				settlementOperationId = settlementOperationId,
			)
		}
	} catch (cancelled: kotlinx.coroutines.CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		false
	}

	private fun CurrentRetentionAuthority.Approved.toLiveAmbientRetentionGrant(
		source: SourceKind,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
	): LiveAmbientRetentionGrant = LiveAmbientRetentionGrant(
		source = source,
		sourcePolicyRevision = sourcePolicyRevision,
		ambientConsentEpoch = ambientConsentEpoch,
		collectedDataEpoch = collectedDataEpoch,
		retainedFromMs = retainedFromMs,
		opaquePolicyId = opaquePolicyId,
		approvalRevision = approvalRevision,
		effectiveBootId = effectiveBootId,
		effectiveElapsedRealtimeNanos = effectiveElapsedRealtimeNanos,
		effectiveWallTimeMs = effectiveWallTimeMs,
	)

	private suspend fun isLiveAmbientRetentionCurrentInTransaction(
		grant: LiveAmbientRetentionGrant,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
	): Boolean = grant.isEffectiveAt(currentBootId, currentElapsedRealtimeNanos, currentWallTimeMs) &&
		hasCurrentAmbientRetentionEvidence(grant) &&
		hasCurrentAmbientRetentionPolicyAndConsent(grant, currentBootId, currentElapsedRealtimeNanos) &&
		(if (grant.source == SourceKind.ACTIVITY || grant.source == SourceKind.PRESSURE) {
			// Global retention was read before Room. Its durable floor/epoch and exact source
			// consent are compared above; never acquire the DataStore operation lease in Room.
			true
		} else {
			readPersistedLiveAmbientRetention(grant.source) == grant
		})

	private suspend fun hasCurrentAmbientRetentionEvidence(grant: LiveAmbientRetentionGrant): Boolean {
		val evidence = database.sourceEvidenceStateDao().get() ?: return false
		return evidence.collectedDataEpoch == grant.collectedDataEpoch && evidence.retainedFromMs == grant.retainedFromMs
	}

	private suspend fun hasCurrentAmbientRetentionPolicyAndConsent(
		grant: LiveAmbientRetentionGrant,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
	): Boolean {
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()?.takeIf { current ->
			current.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				current.currentPolicyRevision == grant.sourcePolicyRevision
		} ?: return false
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, grant.source.stableCode) ?: return false
		val consent = policyDao.latestConsentEpoch(grant.source.stableCode, SourceBrokerPurpose.AMBIENT_PRODUCT)
			?: return false
		return hasEffectiveAmbientRetentionPolicy(policy, grant, currentBootId, currentElapsedRealtimeNanos) &&
			hasEligibleAmbientRetentionConsent(consent, grant) &&
			(consent.effectiveBootId != currentBootId || consent.effectiveElapsedRealtimeNanos <= currentElapsedRealtimeNanos)
	}

	private fun hasEffectiveAmbientRetentionPolicy(
		policy: SourcePolicyEntity,
		grant: LiveAmbientRetentionGrant,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
	): Boolean = policy.ambientPersistenceEligible && policy.ambientConsentEpoch == grant.ambientConsentEpoch &&
		(policy.effectiveBootId != currentBootId || policy.effectiveElapsedRealtimeNanos <= currentElapsedRealtimeNanos)

	private fun hasEligibleAmbientRetentionConsent(
		consent: SourceConsentEpochEntity,
		grant: LiveAmbientRetentionGrant,
	): Boolean = consent.epoch == grant.ambientConsentEpoch && consent.policyRevision <= grant.sourcePolicyRevision &&
		consent.eligible && consent.persistenceEligible

	private suspend fun readPersistedLiveAmbientRetention(source: SourceKind): LiveAmbientRetentionGrant? = try {
		when (source) {
			SourceKind.LOCATION -> readPersistedLocationRetention()
			SourceKind.STEPS -> readPersistedStepsRetention()
			SourceKind.WIFI -> readPersistedWifiRetention()
			SourceKind.CELL -> readPersistedCellRetention()
			else -> null
		}
	} catch (cancelled: kotlinx.coroutines.CancellationException) {
		throw cancelled
	} catch (_: RuntimeException) {
		null
	}

	private suspend fun readPersistedLocationRetention(): LiveAmbientRetentionGrant? =
		database.locationPurposeDao().latestRetentionAuthority(
			AmbientLocationRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
		?.takeIf(AmbientLocationRetentionAuthorityIntegrity::isAuthentic)
		?.takeIf { it.isActive }
		?.let { authority ->
			LiveAmbientRetentionGrant(SourceKind.LOCATION, requireNotNull(authority.sourcePolicyRevision),
				requireNotNull(authority.ambientConsentEpoch), authority.collectedDataEpoch,
				authority.retainedFromMs, authority.opaquePolicyId, authority.approvalRevision,
				authority.effectiveBootId, authority.effectiveElapsedRealtimeNanos,
				authority.effectiveWallTimeMs)
		}

	private suspend fun readPersistedStepsRetention(): LiveAmbientRetentionGrant? = database.ambientStepsFactRevisionDao()
		.latestRetentionAuthority(AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
		?.takeIf(AmbientStepsRetentionAuthorityIntegrity::isAuthentic)
		?.takeIf { authority -> authority.isActive }
		?.let { authority ->
			LiveAmbientRetentionGrant(
				SourceKind.STEPS,
				requireNotNull(authority.sourcePolicyRevision),
				requireNotNull(authority.ambientConsentEpoch),
				authority.collectedDataEpoch,
				authority.retainedFromMs,
				authority.opaquePolicyId,
				authority.approvalRevision,
				authority.effectiveBootId,
				authority.effectiveElapsedRealtimeNanos,
				authority.effectiveWallTimeMs,
			)
		}

	private suspend fun readPersistedWifiRetention(): LiveAmbientRetentionGrant? = database.ambientWifiFactDao()
		.latestRetentionAuthority(AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
		?.takeIf(AmbientWifiRetentionAuthorityIntegrity::isAuthentic)
		?.takeIf { authority -> authority.isActive }
		?.let { authority ->
			LiveAmbientRetentionGrant(
				SourceKind.WIFI,
				requireNotNull(authority.sourcePolicyRevision),
				requireNotNull(authority.ambientConsentEpoch),
				authority.collectedDataEpoch,
				authority.retainedFromMs,
				authority.opaquePolicyId,
				authority.approvalRevision,
				authority.effectiveBootId,
				authority.effectiveElapsedRealtimeNanos,
				authority.effectiveWallTimeMs,
			)
		}

	private suspend fun readPersistedCellRetention(): LiveAmbientRetentionGrant? = database.ambientCellFactDao()
		.latestRetentionAuthority(AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
		?.takeIf(AmbientCellRetentionAuthorityIntegrity::isAuthentic)
		?.takeIf { authority -> authority.isActive }
		?.let { authority ->
			LiveAmbientRetentionGrant(
				SourceKind.CELL,
				requireNotNull(authority.sourcePolicyRevision),
				requireNotNull(authority.ambientConsentEpoch),
				authority.collectedDataEpoch,
				authority.retainedFromMs,
				authority.opaquePolicyId,
				authority.approvalRevision,
				authority.effectiveBootId,
				authority.effectiveElapsedRealtimeNanos,
				authority.effectiveWallTimeMs,
			)
		}

	private suspend fun LiveAmbientRetentionSnapshot.matchesActiveSettlementInTransaction(): Boolean {
		val active = database.collectedDataDeletionOperationDao().activeRetentionFloorSettlement()
		val expectedOperationId = settlementOperationId
		if (expectedOperationId == null) {
			return active == null
		}
		if (
			active?.operationId != expectedOperationId ||
			!active.hasReachedRetentionPhase(
				CollectedDataDeletionOperationEntity.PHASE_RETENTION_ROOM_GUARD_COMMITTED,
			)
		) {
			return false
		}
		val epochs = grants.values.map(LiveAmbientRetentionGrant::collectedDataEpoch).toSet()
		val floors = grants.values.map(LiveAmbientRetentionGrant::retainedFromMs).toSet()
		return epochs == setOf(active.targetCollectedDataEpoch) &&
			floors == setOf(active.settledRetainedFromMs)
	}


}

internal fun sourceSessionConsumerId(logicalTrackingId: String): String = "session:$logicalTrackingId"

/** Existing repository teardown and confirmation operations; no collection or runtime authority is created. */
private class CallerAuthorityRetirementOperations(
	private val repository: SourceCallerAcceptedAuthorityRepository,
) {
	suspend fun retireExact(
		reference: SourceCallerReplayReference,
		reason: String,
		wallTimeMs: Long,
	): SourceCallerAuthorityRetirementOutcome {
		load(reference).storedRetirementOutcome()?.let { return it }
		val retired = try {
			repository.retireForTeardown(reference, reason, wallTimeMs)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// Exact authority retirement keeps storage retry distinct from a terminal invariant failure.
			return failure.callerAuthorityRetirementFailure()
		}
		return if (!retired) {
			SourceCallerAuthorityRetirementOutcome.Retryable(
				SourceCallerAuthorityRetirementRetryReason.COMPARE_AND_SET_FAILED)
		} else {
			load(reference).storedRetirementOutcome() ?: SourceCallerAuthorityRetirementOutcome.Retryable(
				SourceCallerAuthorityRetirementRetryReason.COMPARE_AND_SET_FAILED)
		}
	}

	suspend fun load(
		reference: SourceCallerReplayReference,
	): CallerAuthorityRetirementLoad = try {
		when (val loaded = repository.load(reference)) {
			is StoredSourceCallerAuthorityLoadResult.Available ->
				CallerAuthorityRetirementLoad.Available
			StoredSourceCallerAuthorityLoadResult.Missing ->
				CallerAuthorityRetirementLoad.Missing
			StoredSourceCallerAuthorityLoadResult.Corrupt ->
				CallerAuthorityRetirementLoad.Corrupt
			StoredSourceCallerAuthorityLoadResult.Retired ->
				CallerAuthorityRetirementLoad.Retired
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
		// One saved authority load preserves storage/invariant outcomes without implying retirement.
		CallerAuthorityRetirementLoad.Failed(
			failure.callerAuthorityRetirementFailure(),
		)
	}

	suspend fun retireReferences(
		references: Set<SourceCallerReplayReference>,
		reason: String,
		wallTimeMs: Long,
	): Boolean {
		var complete = true
		references.forEach { reference ->
			try {
				if (!repository.retireForTeardown(
						reference,
						reason,
						wallTimeMs,
					)
				) {
					complete = false
				}
			} catch (cancelled: kotlinx.coroutines.CancellationException) {
				throw cancelled
			} catch (_: Exception) {
				complete = false
			}
		}
		return complete
	}

	suspend fun unreferencedSupersededOutcome(
		expectedReference: SourceCallerReplayReference,
	): SourceCallerAuthorityRetirementOutcome = when (val expected = load(expectedReference)) {
		CallerAuthorityRetirementLoad.Available -> SourceCallerAuthorityRetirementOutcome.TerminalInvariant
		CallerAuthorityRetirementLoad.Corrupt -> SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
		CallerAuthorityRetirementLoad.Missing -> SourceCallerAuthorityRetirementOutcome.TerminalMissing
		CallerAuthorityRetirementLoad.Retired -> SourceCallerAuthorityRetirementOutcome.Completed
		is CallerAuthorityRetirementLoad.Failed -> expected.outcome
	}

	suspend fun historicalAuthorityConflict(
		references: Set<SourceCallerReplayReference>,
		expectedReference: SourceCallerReplayReference,
		retainedStepsReference: SourceCallerReplayReference?,
	): SourceCallerAuthorityRetirementOutcome? {
		for (reference in references) {
			if (reference == expectedReference || reference == retainedStepsReference) {
				continue
			}
			when (val historical = load(reference)) {
				CallerAuthorityRetirementLoad.Available ->
					return SourceCallerAuthorityRetirementOutcome.TerminalAmbiguous
				CallerAuthorityRetirementLoad.Corrupt ->
					return SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
				CallerAuthorityRetirementLoad.Missing, CallerAuthorityRetirementLoad.Retired -> Unit
				is CallerAuthorityRetirementLoad.Failed -> return historical.outcome
			}
		}
		return null
	}

	private fun CallerAuthorityRetirementLoad.storedRetirementOutcome(): SourceCallerAuthorityRetirementOutcome? =
		when (this) {
			CallerAuthorityRetirementLoad.Missing, CallerAuthorityRetirementLoad.Retired ->
				SourceCallerAuthorityRetirementOutcome.Completed
			CallerAuthorityRetirementLoad.Corrupt -> SourceCallerAuthorityRetirementOutcome.TerminalCorrupt
			CallerAuthorityRetirementLoad.Available -> null
			is CallerAuthorityRetirementLoad.Failed -> outcome
		}

	private fun Exception.callerAuthorityRetirementFailure(): SourceCallerAuthorityRetirementOutcome =
		if (isTrackingOperationalFailure()) {
			SourceCallerAuthorityRetirementOutcome.Retryable(SourceCallerAuthorityRetirementRetryReason.STORAGE_UNAVAILABLE)
		} else {
			SourceCallerAuthorityRetirementOutcome.TerminalInvariant
		}
}

private sealed interface CallerAuthorityRetirementLoad {
	data object Available : CallerAuthorityRetirementLoad
	data object Missing : CallerAuthorityRetirementLoad
	data object Corrupt : CallerAuthorityRetirementLoad
	data object Retired : CallerAuthorityRetirementLoad

	data class Failed(
		val outcome: SourceCallerAuthorityRetirementOutcome,
	) : CallerAuthorityRetirementLoad
}

private data class PreparedSessionActivationOwner(
	val logicalTrackingId: String,
	val consumerId: String,
	val serviceRunId: String,
	val manifestRevision: Long,
	val leaseGeneration: Long,
	val sourceCallerAuthorityReference: String,
)

/**
 * Owns prepared-demand lease handoff and foreground activation within the broker caller's Room transaction.
 * It retains no runtime state; provider authorization rotation remains owned by [SourceBroker].
 */
private class PreparedSessionDemandMutations(
	private val database: AppDatabase,
	private val rotateAuthorization: suspend (Int, SourceDemandMutationBoundary) -> Unit,
) {
	private data class RegistrationAuthorizationBoundary(
		val registration: ProviderRegistrationGenerationEntity,
		val authorizationRevision: Long,
	)

	suspend fun rebind(
		expectedDemands: List<SourceDemandEntity>,
		expectedCallerAuthorityReference: String,
		newCallerAuthorityReference: String,
		newLeaseGeneration: Long,
		foregroundAccepted: Boolean,
		boundary: SourceDemandMutationBoundary,
	): List<SourceDemandEntity>? {
		val predecessor = readPreparedSessionRebindVector(expectedDemands, expectedCallerAuthorityReference,
			newCallerAuthorityReference, newLeaseGeneration, foregroundAccepted, boundary) ?: return null
		val authorizationBoundaries = capturePreparedRebindAuthorizations(predecessor, boundary) ?: return null
		val rebound = buildReboundSessionDemandVector(predecessor, newCallerAuthorityReference, boundary) ?: return null
		retirePreparedRebindPredecessor(predecessor, boundary)
		check(database.sourceBrokerDao().insertDemands(rebound.demands).all { rowId -> rowId != -1L }) {
			"Prepared demand successor identity already exists"
		}
		if (foregroundAccepted) {
			rotatePreparedRebindAuthorizations(predecessor, authorizationBoundaries, rebound.oldDemandIds, boundary)
		}
		return rebound.demands
	}

	private data class PreparedSessionRebindIdentity(
		val consumerId: String,
		val serviceRunId: String,
		val manifestRevision: Long,
		val oldLeaseGeneration: Long,
		val newLeaseGeneration: Long,
	)

	private data class PreparedSessionRebindVector(
		val identity: PreparedSessionRebindIdentity,
		val stored: List<SourceDemandEntity>,
		val activeOldDemands: List<SourceDemandEntity>,
	)

	private data class ReboundSessionDemandVector(val demands: List<SourceDemandEntity>, val oldDemandIds: Set<String>)

	/** No persisted row is read until the proposed successor references and clock are valid. */
	private suspend fun readPreparedSessionRebindVector(
		expectedDemands: List<SourceDemandEntity>,
		expectedCallerAuthorityReference: String,
		newCallerAuthorityReference: String,
		newLeaseGeneration: Long,
		foregroundAccepted: Boolean,
		boundary: SourceDemandMutationBoundary,
	): PreparedSessionRebindVector? {
		if (expectedDemands.isEmpty() ||
			!hasDistinctPreparedCallerReferences(expectedCallerAuthorityReference, newCallerAuthorityReference) ||
			!hasValidPreparedRebindBoundary(newLeaseGeneration, boundary)) {
			return null
		}
		val identity = preparedSessionRebindIdentity(expectedDemands, newLeaseGeneration) ?: return null
		if (newLeaseGeneration <= identity.oldLeaseGeneration || expectedDemands.any { demand ->
				demand.sourceCallerAuthorityReference != expectedCallerAuthorityReference ||
					demand.status !in setOf(SourceDemandEntity.STATUS_ACTIVE, SourceDemandEntity.STATUS_BLOCKED,
						SourceDemandEntity.STATUS_RETIRED)
			}) {
			return null
		}
		return readStoredPreparedSessionRebindVector(identity, expectedDemands, foregroundAccepted)
	}

	private fun hasDistinctPreparedCallerReferences(expected: String, successor: String): Boolean =
		expected.isNotBlank() && successor.isNotBlank() && expected != successor

	private fun hasValidPreparedRebindBoundary(leaseGeneration: Long, boundary: SourceDemandMutationBoundary): Boolean =
		leaseGeneration > 0L && boundary.bootId.isNotBlank() &&
			boundary.elapsedRealtimeNanos >= 0L && boundary.wallTimeMs >= 0L

	private fun preparedSessionRebindIdentity(
		expectedDemands: List<SourceDemandEntity>,
		newLeaseGeneration: Long,
	): PreparedSessionRebindIdentity? {
		val consumerId = expectedDemands.map(SourceDemandEntity::consumerId).distinct().singleOrNull() ?: return null
		val serviceRunId = expectedDemands.mapNotNull(SourceDemandEntity::serviceRunId).distinct().singleOrNull()
			?: return null
		val manifestRevision = expectedDemands.mapNotNull(SourceDemandEntity::manifestRevision).distinct().singleOrNull()
			?: return null
		return expectedDemands.mapNotNull(SourceDemandEntity::lifecycleLeaseGeneration).distinct().singleOrNull()
			?.let { oldLease -> PreparedSessionRebindIdentity(consumerId, serviceRunId, manifestRevision,
				oldLease, newLeaseGeneration) }
	}

	private suspend fun readStoredPreparedSessionRebindVector(
		identity: PreparedSessionRebindIdentity,
		expectedDemands: List<SourceDemandEntity>,
		foregroundAccepted: Boolean,
	): PreparedSessionRebindVector? {
		val dao = database.sourceBrokerDao()
		val stored = dao.demandHistory(identity.consumerId).filter { demand ->
			demand.serviceRunId == identity.serviceRunId && demand.manifestRevision == identity.manifestRevision &&
				demand.lifecycleLeaseGeneration == identity.oldLeaseGeneration
		}.sortedBy(SourceDemandEntity::demandId)
		if (stored != expectedDemands.sortedBy(SourceDemandEntity::demandId) ||
			dao.demandHistory(identity.consumerId).any { demand ->
				demand.serviceRunId == identity.serviceRunId && demand.manifestRevision == identity.manifestRevision &&
					demand.lifecycleLeaseGeneration == identity.newLeaseGeneration
			}) {
			return null
		}
		val activeOldDemands = stored.filter { demand -> demand.status == SourceDemandEntity.STATUS_ACTIVE }
		if (!foregroundAccepted && activeOldDemands.isNotEmpty()) {
			return null
		}
		return PreparedSessionRebindVector(identity, stored, activeOldDemands)
	}

	/** Captures each physical owner's current observed-time authorization before retiring any demand. */
	private suspend fun capturePreparedRebindAuthorizations(
		predecessor: PreparedSessionRebindVector,
		boundary: SourceDemandMutationBoundary,
	): List<RegistrationAuthorizationBoundary>? {
		val dao = database.sourceBrokerDao()
		val authorizationBoundaries = mutableListOf<RegistrationAuthorizationBoundary>()
		for (sourceKind in predecessor.activeOldDemands.map(SourceDemandEntity::sourceKind).toSet()) {
			val currentSourceDemands = dao.authorizationDemands(sourceKind)
			val oldSourceDemands = predecessor.activeOldDemands.filter { demand -> demand.sourceKind == sourceKind }
			for (registration in dao.currentPhysicalRegistrations(sourceKind)
				.filter { registration -> registration.clockDomainId == boundary.bootId }) {
				val ownedOldDemands = SourceProviderPurposeScope.selectDemands(sourceKind, registration.ownerScope,
					oldSourceDemands)
				if (ownedOldDemands.isEmpty()) {
					continue
				}
				val ownedCurrentDemands = SourceProviderPurposeScope.selectDemands(sourceKind, registration.ownerScope,
					currentSourceDemands)
				val latest = dao.latestAuthorization(sourceKind, registration.registrationGeneration)
					.toAuthorizationSnapshotOrNull() ?: return null
				if (!hasExactPreparedRebindAuthorization(latest, predecessor.identity, boundary,
					ownedCurrentDemands, ownedOldDemands)) {
					return null
				}
				authorizationBoundaries += RegistrationAuthorizationBoundary(registration, latest.authorizationRevision)
			}
		}
		return authorizationBoundaries
	}

	private fun hasExactPreparedRebindAuthorization(
		latest: SourceAuthorizationSnapshot,
		identity: PreparedSessionRebindIdentity,
		boundary: SourceDemandMutationBoundary,
		ownedCurrentDemands: List<SourceDemandEntity>,
		ownedOldDemands: List<SourceDemandEntity>,
	): Boolean {
		if (latest.effectiveBootId != boundary.bootId ||
			latest.effectiveElapsedRealtimeNanos > boundary.elapsedRealtimeNanos) {
			return false
		}
		if (!latest.hasExactDemandMembership(ownedCurrentDemands)) {
			return false
		}
		return ownedOldDemands.none { demand ->
			latest.authorizedMembers.none { row ->
				row.demandId == demand.demandId && row.lifecycleLeaseGeneration == identity.oldLeaseGeneration
			}
		}
	}

	private fun buildReboundSessionDemandVector(
		predecessor: PreparedSessionRebindVector,
		newCallerAuthorityReference: String,
		boundary: SourceDemandMutationBoundary,
	): ReboundSessionDemandVector? {
		val identity = predecessor.identity
		val rebound = predecessor.stored.map { demand ->
			val alreadyRetired = demand.status == SourceDemandEntity.STATUS_RETIRED
			demand.copy(
				demandId = sessionDemandId(consumerId = demand.consumerId, sourceKind = demand.sourceKind,
					purpose = demand.purpose, serviceRunId = identity.serviceRunId, policyRevision = demand.sourcePolicyRevision,
					consentEpoch = demand.consentEpoch, manifestRevision = identity.manifestRevision,
					lifecycleLeaseGeneration = identity.newLeaseGeneration, activationBootId = boundary.bootId,
					activationElapsedRealtimeNanos = boundary.elapsedRealtimeNanos),
				lifecycleLeaseGeneration = identity.newLeaseGeneration,
				requestedBootId = boundary.bootId,
				requestedElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
				requestedAtMs = boundary.wallTimeMs,
				retireBootId = boundary.bootId.takeIf { alreadyRetired },
				retireElapsedRealtimeNanos = boundary.elapsedRealtimeNanos.takeIf { alreadyRetired },
				retiredAtMs = boundary.wallTimeMs.takeIf { alreadyRetired },
				sourceCallerAuthorityReference = newCallerAuthorityReference,
			)
		}
		val oldDemandIds = predecessor.stored.mapTo(linkedSetOf(), SourceDemandEntity::demandId)
		val reboundDemandIds = rebound.mapTo(linkedSetOf(), SourceDemandEntity::demandId)
		if (reboundDemandIds.size != rebound.size || reboundDemandIds.intersect(oldDemandIds).isNotEmpty()) {
			return null
		}
		return ReboundSessionDemandVector(rebound, oldDemandIds)
	}

	private suspend fun retirePreparedRebindPredecessor(
		predecessor: PreparedSessionRebindVector,
		boundary: SourceDemandMutationBoundary,
	) {
		val identity = predecessor.identity
		val nonterminalOldDemands = predecessor.stored.filter { demand ->
			demand.status == SourceDemandEntity.STATUS_ACTIVE || demand.status == SourceDemandEntity.STATUS_BLOCKED
		}
		if (nonterminalOldDemands.isNotEmpty()) {
			check(database.sourceBrokerDao().retirePreparedSessionDemandsByIds(
				consumerId = identity.consumerId, serviceRunId = identity.serviceRunId,
				manifestRevision = identity.manifestRevision, leaseGeneration = identity.oldLeaseGeneration,
				demandIds = nonterminalOldDemands.map(SourceDemandEntity::demandId), bootId = boundary.bootId,
				elapsedRealtimeNanos = boundary.elapsedRealtimeNanos, wallTimeMs = boundary.wallTimeMs,
			) == nonterminalOldDemands.size) {
				"Prepared demand ownership changed during immutable lease rebind"
			}
		}
	}

	private suspend fun rotatePreparedRebindAuthorizations(
		predecessor: PreparedSessionRebindVector,
		authorizationBoundaries: List<RegistrationAuthorizationBoundary>,
		oldDemandIds: Set<String>,
		boundary: SourceDemandMutationBoundary,
	) {
		val dao = database.sourceBrokerDao()
		predecessor.activeOldDemands.map(SourceDemandEntity::sourceKind).toSet().forEach { sourceKind ->
			rotateAuthorization(sourceKind, boundary)
		}
		authorizationBoundaries.forEach { captured ->
			val latest = requireNotNull(dao.latestAuthorization(captured.registration.sourceKind,
				captured.registration.registrationGeneration).toAuthorizationSnapshotOrNull())
			val currentOwnedDemands = SourceProviderPurposeScope.selectDemands(captured.registration.sourceKind,
				captured.registration.ownerScope, dao.authorizationDemands(captured.registration.sourceKind))
			check(latest.authorizationRevision > captured.authorizationRevision &&
				latest.hasExactDemandMembership(currentOwnedDemands) &&
				latest.authorizedMembers.none { row -> row.demandId in oldDemandIds }) {
				"Provider authorization did not rotate to the rebound demand generation"
			}
		}
	}

	/** Opens only the foreground-accepted vector, then rotates its physical authorization once. */
	suspend fun activate(
		owner: PreparedSessionActivationOwner,
		boundary: SourceDemandMutationBoundary,
		currentAuthority: SourceCallerCurrentAuthorityPredicate,
		acceptedSourceKinds: Set<Int>?,
	): Boolean {
		val exact = readPreparedActivationVector(owner) ?: return false
		if (!currentAuthority.permitsActivation(SourceCallerReplayReference(owner.sourceCallerAuthorityReference),
			SourceCallerManifestIdentity(owner.logicalTrackingId, owner.manifestRevision), exact)) {
			return false
		}
		val blocked = exact.filter { it.status == SourceDemandEntity.STATUS_BLOCKED }
		if (blocked.isEmpty()) {
			return true
		}
		val activated = if (acceptedSourceKinds == null) {
			database.sourceBrokerDao().activatePreparedSessionDemands(owner.consumerId, owner.serviceRunId,
				owner.manifestRevision, owner.leaseGeneration) == blocked.size
		} else {
			activateSelectedSessionDemands(owner, exact, blocked, acceptedSourceKinds, boundary)
		}
		if (activated) {
			blocked.map(SourceDemandEntity::sourceKind).toSet().forEach { sourceKind ->
				rotateAuthorization(sourceKind, boundary)
			}
		}
		return activated
	}

	private suspend fun readPreparedActivationVector(owner: PreparedSessionActivationOwner): List<SourceDemandEntity>? {
		val exact = database.sourceBrokerDao().demandHistory(owner.consumerId).filter { demand ->
			demand.serviceRunId == owner.serviceRunId && demand.manifestRevision == owner.manifestRevision &&
				demand.lifecycleLeaseGeneration == owner.leaseGeneration
		}
		return exact.takeUnless { demands ->
			demands.isEmpty() || demands.any { demand ->
				demand.status !in setOf(SourceDemandEntity.STATUS_BLOCKED, SourceDemandEntity.STATUS_ACTIVE) ||
					demand.sourceCallerAuthorityReference != owner.sourceCallerAuthorityReference
			}
		}
	}

	/** Retires excluded rows only after the accepted subset was successfully activated. */
	private suspend fun activateSelectedSessionDemands(
		owner: PreparedSessionActivationOwner,
		exact: List<SourceDemandEntity>,
		blocked: List<SourceDemandEntity>,
		acceptedSourceKinds: Set<Int>,
		boundary: SourceDemandMutationBoundary,
	): Boolean {
		val captureDemands = exact.filter { demand -> demand.purpose == SourceBrokerPurpose.SESSION_CAPTURE }
		if (acceptedSourceKinds.any { sourceKind -> captureDemands.none { demand -> demand.sourceKind == sourceKind } }) {
			return false
		}
		val toActivate = blocked.filter { demand ->
			acceptedSourceKinds.isNotEmpty() &&
				(demand.purpose != SourceBrokerPurpose.SESSION_CAPTURE || demand.sourceKind in acceptedSourceKinds)
		}
		val toRetire = blocked - toActivate.toSet()
		val dao = database.sourceBrokerDao()
		val activatedCount = if (toActivate.isEmpty()) {
			0
		} else {
			dao.activatePreparedSessionDemandsByIds(owner.consumerId, owner.serviceRunId, owner.manifestRevision,
				owner.leaseGeneration, toActivate.map(SourceDemandEntity::demandId))
		}
		if (activatedCount != toActivate.size) {
			return false
		}
		return if (toRetire.isEmpty()) {
			true
		} else {
			dao.retirePreparedSessionDemandsByIds(owner.consumerId, owner.serviceRunId, owner.manifestRevision,
				owner.leaseGeneration, toRetire.map(SourceDemandEntity::demandId), boundary.bootId,
				boundary.elapsedRealtimeNanos, boundary.wallTimeMs) == toRetire.size
		}
	}

	/** Matches both the ordered grant digest and its exact set of physical-owner demand IDs. */
	private fun SourceAuthorizationSnapshot.hasExactDemandMembership(demands: List<SourceDemandEntity>): Boolean =
		authorizationFingerprint == SourceBrokerAuthorization.fingerprint(demands) &&
			authorizedMembers.mapNotNull { row -> row.demandId }.toSet() ==
				demands.mapTo(linkedSetOf(), SourceDemandEntity::demandId)
}

private data class SourceDemandMutationBoundary(
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
)

// Build the complete saved Location demand from its accepted purpose and acquisition contract.
private fun SourceDemandContract.ambientSourceDemand(
	source: SourceKind,
	leaseIdentity: AmbientReconciliationIdentity,
	retention: LiveAmbientRetentionGrant,
	reference: String,
	boundary: SourceDemandMutationBoundary,
): SourceDemandEntity = SourceDemandEntity(
	demandId = demandId(ambientSourceConsumerId(source), source.stableCode, SourceBrokerPurpose.AMBIENT_PRODUCT,
		leaseIdentity.policyRevision, leaseIdentity.consentEpoch, null, boundary.bootId,
		boundary.elapsedRealtimeNanos, reference),
	consumerId = ambientSourceConsumerId(source), sourceKind = source.stableCode,
	purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
	logicalTrackingId = null, serviceRunId = null, manifestRevision = null,
	lifecycleLeaseGeneration = null, sourcePolicyRevision = leaseIdentity.policyRevision,
	consentEpoch = leaseIdentity.consentEpoch, persistenceEligible = true,
	qosCode = 0, minimumAcquisitionSpec = encodeFloor(),
	adaptiveReductionAllowed = adaptiveReductionAllowed,
	maximumAgeMs = maximumProviderItemAgeMs,
	desiredLatencyMs = targetPlanningLatencyMs,
	requestedDeliveryLatencyMs = requestedDeliveryLatencyMs,
	requestedBootId = boundary.bootId, requestedElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
	requestedAtMs = boundary.wallTimeMs, status = SourceDemandEntity.STATUS_ACTIVE,
	retireBootId = null, retireElapsedRealtimeNanos = null, retiredAtMs = null,
	sourceCallerAuthorityReference = reference,
	liveAmbientRetentionPolicyId = retention.opaquePolicyId,
	liveAmbientRetentionApprovalRevision = retention.approvalRevision,
)

// Build the complete saved Steps demand from its accepted purpose and acquisition contract.
private fun SourceDemandContract.ambientStepsDemand(
	consumerId: String,
	leaseIdentity: AmbientReconciliationIdentity,
	retention: LiveAmbientRetentionGrant,
	callerAuthorityReference: String?,
	boundary: SourceDemandMutationBoundary,
): SourceDemandEntity = SourceDemandEntity(
	demandId = AmbientStepsDemandIdentity.create(
		consumerId = consumerId,
		sourcePolicyRevision = leaseIdentity.policyRevision,
		consentEpoch = leaseIdentity.consentEpoch,
		requestedBootId = boundary.bootId,
		requestedElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
		minimumAcquisitionSpec = encodeFloor(),
		retention = retention,
		leaseIdentity = leaseIdentity,
	),
	consumerId = consumerId,
	sourceKind = SourceKind.STEPS.stableCode,
	purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
	logicalTrackingId = null,
	serviceRunId = null,
	manifestRevision = null,
	lifecycleLeaseGeneration = null,
	sourcePolicyRevision = leaseIdentity.policyRevision,
	consentEpoch = leaseIdentity.consentEpoch,
	persistenceEligible = true,
	qosCode = 0,
	minimumAcquisitionSpec = encodeFloor(),
	adaptiveReductionAllowed = adaptiveReductionAllowed,
	maximumAgeMs = maximumProviderItemAgeMs,
	desiredLatencyMs = targetPlanningLatencyMs,
	requestedDeliveryLatencyMs = requestedDeliveryLatencyMs,
	requestedBootId = boundary.bootId,
	requestedElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
	requestedAtMs = boundary.wallTimeMs,
	status = SourceDemandEntity.STATUS_ACTIVE,
	retireBootId = null,
	retireElapsedRealtimeNanos = null,
	retiredAtMs = null,
	sourceCallerAuthorityReference = callerAuthorityReference,
	liveAmbientRetentionPolicyId = retention.opaquePolicyId,
	liveAmbientRetentionApprovalRevision = retention.approvalRevision,
)

// Exact saved all-day Steps demand, chosen acquisition contract, retained identity and caller tuple.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private fun SourceDemandEntity.matchesAmbientStepsDemand(
	leaseIdentity: AmbientReconciliationIdentity,
	contract: SourceDemandContract,
	retention: LiveAmbientRetentionGrant,
	callerAuthorityReference: String?,
	bootId: String,
): Boolean = status == SourceDemandEntity.STATUS_ACTIVE &&
	sourceKind == SourceKind.STEPS.stableCode &&
	purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
	sourcePolicyRevision == leaseIdentity.policyRevision &&
	consentEpoch == leaseIdentity.consentEpoch &&
	persistenceEligible &&
	qosCode == 0 &&
	minimumAcquisitionSpec == contract.encodeFloor() &&
	adaptiveReductionAllowed == contract.adaptiveReductionAllowed &&
	maximumAgeMs == contract.maximumProviderItemAgeMs &&
	desiredLatencyMs == contract.targetPlanningLatencyMs &&
	requestedDeliveryLatencyMs == contract.requestedDeliveryLatencyMs &&
	AmbientStepsDemandIdentity.matches(this, retention, leaseIdentity) &&
	requestedBootId == bootId &&
	liveAmbientRetentionPolicyId == retention.opaquePolicyId &&
	liveAmbientRetentionApprovalRevision == retention.approvalRevision &&
	sourceCallerAuthorityReference == callerAuthorityReference

// Exact immutable all-day Location demand, acquisition contract, retention and caller correspondence.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private fun SourceDemandEntity.matchesAmbientSourceDemand(
	source: SourceKind,
	leaseIdentity: AmbientReconciliationIdentity,
	contract: SourceDemandContract,
	retention: LiveAmbientRetentionGrant,
	reference: String,
	bootId: String,
): Boolean = status == SourceDemandEntity.STATUS_ACTIVE &&
	sourceKind == source.stableCode && purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
	logicalTrackingId == null && serviceRunId == null &&
	sourcePolicyRevision == leaseIdentity.policyRevision &&
	consentEpoch == leaseIdentity.consentEpoch && persistenceEligible &&
	requestedBootId == bootId && minimumAcquisitionSpec == contract.encodeFloor() &&
	maximumAgeMs == contract.maximumProviderItemAgeMs &&
	desiredLatencyMs == contract.targetPlanningLatencyMs &&
	requestedDeliveryLatencyMs == contract.requestedDeliveryLatencyMs &&
	adaptiveReductionAllowed == contract.adaptiveReductionAllowed &&
	liveAmbientRetentionPolicyId == retention.opaquePolicyId &&
	liveAmbientRetentionApprovalRevision == retention.approvalRevision &&
	sourceCallerAuthorityReference == reference

// Fixed live-retention grant and purpose lease identity; current retention checks remain at the call site.
@Suppress("ComplexCondition")
private fun LiveAmbientRetentionGrant.matchesAmbientLease(identity: AmbientReconciliationIdentity): Boolean =
	sourcePolicyRevision == identity.policyRevision && ambientConsentEpoch == identity.consentEpoch &&
	collectedDataEpoch == identity.collectedDataEpoch && retainedFromMs == identity.retainedFromMs &&
	opaquePolicyId == identity.retentionPolicyId && approvalRevision == identity.retentionApprovalRevision

private fun requireRadioCompensationInput(
	source: SourceKind,
	leaseIdentity: AmbientReconciliationIdentity,
	reconciliationAttempt: Long,
	expectedDemandId: String,
	consumerId: String,
	boundary: SourceDemandMutationBoundary,
) {
	require(source == SourceKind.WIFI || source == SourceKind.CELL)
	require(leaseIdentity.source == source.toAmbientTrackingSource())
	require(reconciliationAttempt > 0L)
	require(expectedDemandId.isNotBlank() && consumerId.isNotBlank() && boundary.bootId.isNotBlank())
	require(boundary.elapsedRealtimeNanos >= 0L && boundary.wallTimeMs >= 0L)
}

// Complete saved active/revoked execution and exact compensation attempt/demand correspondence.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private fun AmbientRadioAuthority.matchesRadioCompensationAuthority(
	identity: AmbientReconciliationIdentity,
	reconciliationAttempt: Long,
	expectedDemandId: String,
): Boolean = (state == AmbientWifiAuthorityEntity.STATE_ACTIVE ||
	state == AmbientWifiAuthorityEntity.STATE_REVOKED) &&
	matchesRadioReductionGrant(identity) &&
	collectedDataEpoch == identity.collectedDataEpoch &&
	rolloutRevision == identity.rolloutRevision &&
	executionGeneration == identity.executionRevision &&
	ownerCasToken == identity.ownerCasToken &&
	this.reconciliationAttempt == reconciliationAttempt &&
	demandId == expectedDemandId

// Exact immutable compensation demand, purpose/lease/retention and optional saved caller reference.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private fun SourceDemandEntity.matchesRadioCompensationDemand(
	source: SourceKind,
	identity: AmbientReconciliationIdentity,
	expectedDemandId: String,
	expectedCallerAuthorityReference: SourceCallerReplayReference?,
): Boolean = demandId == expectedDemandId &&
	sourceKind == source.stableCode &&
	purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
	sourcePolicyRevision == identity.policyRevision &&
	consentEpoch == identity.consentEpoch &&
	liveAmbientRetentionPolicyId == identity.retentionPolicyId &&
	liveAmbientRetentionApprovalRevision ==
	identity.retentionApprovalRevision &&
	(
	expectedCallerAuthorityReference == null ||
	sourceCallerAuthorityReference ==
	expectedCallerAuthorityReference.value
	)

private fun List<SourceDemandEntity>.hasOtherNonterminalRadioDemand(expectedDemandId: String): Boolean {
	val nonterminalDemands = filter { candidate ->
		candidate.status == SourceDemandEntity.STATUS_ACTIVE ||
			candidate.status == SourceDemandEntity.STATUS_RETIRING ||
			candidate.status == SourceDemandEntity.STATUS_BLOCKED
	}
	return nonterminalDemands.any { it.demandId != expectedDemandId }
}

private fun List<SourceDemandEntity>.hasInvalidAmbientRadioRetirementShape(source: SourceKind): Boolean =
	size > 1 || any {
		it.sourceKind != source.stableCode || it.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT
	}

// Complete saved demand and active-authority tuple before constructing its retirement lease.
@Suppress("ComplexCondition")
private fun SourceDemandEntity.matchesActiveRadioRetirement(authority: AmbientRadioAuthority): Boolean =
	authority.demandId == demandId && sourcePolicyRevision == authority.sourcePolicyRevision &&
	consentEpoch == authority.ambientConsentEpoch &&
	liveAmbientRetentionPolicyId == authority.retentionPolicyId &&
	liveAmbientRetentionApprovalRevision == authority.retentionApprovalRevision

// Preserve missing policy, revoked consent, changed lease and persistence ineligibility precedence.
@Suppress("ReturnCount")
private fun SourcePolicyEntity?.ambientStepsInactiveReason(
	identity: AmbientReconciliationIdentity,
): AmbientStepsDemandInactiveReason? {
	if (this == null) {
		return AmbientStepsDemandInactiveReason.POLICY_MISSING
	}
	val consent = ambientConsentEpoch ?: return AmbientStepsDemandInactiveReason.CONSENT_REVOKED
	if (consent != identity.consentEpoch) {
		return AmbientStepsDemandInactiveReason.STALE_RECONCILIATION_LEASE
	}
	if (!ambientPersistenceEligible) {
		return AmbientStepsDemandInactiveReason.PERSISTENCE_INELIGIBLE
	}
	return null
}

private fun SourcePolicyEntity.hasAmbientConsent(identity: AmbientReconciliationIdentity): Boolean =
	ambientPersistenceEligible && ambientConsentEpoch == identity.consentEpoch

private fun demandId(
	consumerId: String,
	sourceKind: Int,
	purpose: String,
	policyRevision: Long,
	consentEpoch: Long,
	manifestRevision: Long?,
	activationBootId: String,
	activationElapsedRealtimeNanos: Long,
	discriminator: String? = null,
): String {
	val components = mutableListOf<Any>(
		consumerId,
		sourceKind,
		purpose,
		policyRevision,
		consentEpoch,
		manifestRevision ?: 0L,
		activationBootId,
		activationElapsedRealtimeNanos,
	)
	if (discriminator != null) {
		components += discriminator
	}
	val value = components.joinToString("\u001f")
	return MessageDigest.getInstance("SHA-256")
		.digest(value.toByteArray(Charsets.UTF_8))
		.joinToString("") { byte -> "%02x".format(byte) }
}

private fun sessionDemandId(
	consumerId: String,
	sourceKind: Int,
	purpose: String,
	serviceRunId: String,
	policyRevision: Long,
	consentEpoch: Long,
	manifestRevision: Long,
	lifecycleLeaseGeneration: Long,
	activationBootId: String,
	activationElapsedRealtimeNanos: Long,
): String = demandId(
	consumerId = consumerId,
	sourceKind = sourceKind,
	purpose = purpose,
	policyRevision = policyRevision,
	consentEpoch = consentEpoch,
	manifestRevision = manifestRevision,
	activationBootId = activationBootId,
	activationElapsedRealtimeNanos = activationElapsedRealtimeNanos,
	discriminator = "session:$serviceRunId:lease:$lifecycleLeaseGeneration",
)

private fun automaticControlContract(
	source: SourceKind,
	qosCode: Int,
	maximumAgeMs: Long,
	desiredLatencyMs: Long,
): SourceDemandContract = SourceDemandContractFactory.forQos(
	source, qosCode, DirectSourceDemandPurpose.CONTROL_AUTOSTART,
).copy(maximumProviderItemAgeMs = maximumAgeMs, targetPlanningLatencyMs = desiredLatencyMs)

private fun SourcePolicyEntity.automaticControlDemand(
	consumerId: String,
	source: SourceKind,
	policyRevision: Long,
	contract: SourceDemandContract,
	boundary: SourceDemandMutationBoundary,
	callerAuthorityReference: String?,
): SourceDemandEntity {
	val consentEpoch = requireNotNull(controlConsentEpoch)
	return SourceDemandEntity(
		demandId = demandId(
			consumerId,
			source.stableCode,
			SourceBrokerPurpose.CONTROL_AUTOSTART,
			policyRevision,
			consentEpoch,
			null,
			boundary.bootId,
			boundary.elapsedRealtimeNanos,
		),
		consumerId = consumerId,
		sourceKind = source.stableCode,
		purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
		logicalTrackingId = null,
		serviceRunId = null,
		manifestRevision = null,
		lifecycleLeaseGeneration = null,
		sourcePolicyRevision = policyRevision,
		consentEpoch = consentEpoch,
		persistenceEligible = controlPersistenceEligible,
		qosCode = qosCode,
		minimumAcquisitionSpec = contract.encodeFloor(),
		adaptiveReductionAllowed = contract.adaptiveReductionAllowed,
		maximumAgeMs = contract.maximumProviderItemAgeMs,
		desiredLatencyMs = contract.targetPlanningLatencyMs,
		requestedDeliveryLatencyMs = contract.requestedDeliveryLatencyMs,
		requestedBootId = boundary.bootId,
		requestedElapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
		requestedAtMs = boundary.wallTimeMs,
		status = SourceDemandEntity.STATUS_ACTIVE,
		retireBootId = null,
		retireElapsedRealtimeNanos = null,
		retiredAtMs = null,
		sourceCallerAuthorityReference = callerAuthorityReference,
	)
}

private fun TrackingPurposeLeaseIdentity.matchesAutomaticControlAuthority(
	source: SourceKind,
	policyRevision: Long,
	consentEpoch: Long,
	collectedDataEpoch: Long,
	rolloutRevision: Long,
): Boolean {
	if (this.source.stableCode != source.stableCode || purpose != TrackingPurpose.CONTROL ||
		this.policyRevision != policyRevision) {
		return false
	}
	return this.consentEpoch == consentEpoch && this.collectedDataEpoch == collectedDataEpoch &&
		this.rolloutRevision == rolloutRevision
}

internal sealed interface AmbientStepsDemandResult {
	data class Active(val demand: SourceDemandEntity) : AmbientStepsDemandResult
	data class Inactive(val reason: AmbientStepsDemandInactiveReason) : AmbientStepsDemandResult
}

internal sealed interface AmbientStepsRetirementPlan {
	data object AlreadyRetired : AmbientStepsRetirementPlan
	data class Required(val lease: AmbientReconciliationLease) : AmbientStepsRetirementPlan
	data object Unverifiable : AmbientStepsRetirementPlan
}

internal enum class AmbientStepsDemandInactiveReason {
	REQUEST_DISABLED,
	STALE_RECONCILIATION_LEASE,
	AUTHORITY_INACTIVE,
	POLICY_MISSING,
	CONSENT_REVOKED,
	PERSISTENCE_INELIGIBLE,
	RETENTION_APPROVAL_MISSING,
	RETENTION_APPROVAL_MISMATCH,
	ROLLOUT_CONTAINED,
	RETENTION_AUTHORITY_UNAVAILABLE,
}

internal fun SourceDemandEntity.hasExactAmbientStepsRetentionBinding(
	retention: AmbientStepsRetentionAuthorityEntity,
): Boolean = AmbientStepsDemandIdentity.matchesRetention(this, retention)

private object AmbientStepsDemandIdentity {
	fun create(
		consumerId: String,
		sourcePolicyRevision: Long,
		consentEpoch: Long,
		requestedBootId: String,
		requestedElapsedRealtimeNanos: Long,
		minimumAcquisitionSpec: String,
		retention: AmbientStepsRetentionAuthorityEntity,
		leaseIdentity: AmbientReconciliationIdentity,
	): String = buildString {
		append(
			retentionDigest(
				consumerId,
				sourcePolicyRevision,
				consentEpoch,
				requestedBootId,
				requestedElapsedRealtimeNanos,
				minimumAcquisitionSpec,
				retention,
			),
		)
		append('.')
		append(leaseIdentity.rolloutRevision)
		append('.')
		append(leaseIdentity.executionRevision)
		append('.')
		append(
			Base64.getUrlEncoder().withoutPadding().encodeToString(
				leaseIdentity.ownerCasToken.toByteArray(Charsets.UTF_8),
			),
		)
	}

	fun matches(
		demand: SourceDemandEntity,
		retention: AmbientStepsRetentionAuthorityEntity,
		leaseIdentity: AmbientReconciliationIdentity,
	): Boolean =
		leaseIdentity.source == AmbientTrackingSource.STEPS &&
		leaseIdentity.policyRevision == demand.sourcePolicyRevision &&
		leaseIdentity.consentEpoch == demand.consentEpoch &&
		leaseIdentity.collectedDataEpoch == retention.collectedDataEpoch &&
		leaseIdentity.retainedFromMs == retention.retainedFromMs &&
		leaseIdentity.retentionPolicyId == retention.opaquePolicyId &&
		leaseIdentity.retentionApprovalRevision == retention.approvalRevision &&
		demand.sourceKind == SourceKind.STEPS.stableCode &&
		demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
		demand.demandId == create(
				consumerId = demand.consumerId,
				sourcePolicyRevision = demand.sourcePolicyRevision,
				consentEpoch = demand.consentEpoch,
				requestedBootId = demand.requestedBootId,
				requestedElapsedRealtimeNanos = demand.requestedElapsedRealtimeNanos,
				minimumAcquisitionSpec = demand.minimumAcquisitionSpec,
				retention = retention,
				leaseIdentity = leaseIdentity,
			)

	fun create(
		consumerId: String,
		sourcePolicyRevision: Long,
		consentEpoch: Long,
		requestedBootId: String,
		requestedElapsedRealtimeNanos: Long,
		minimumAcquisitionSpec: String,
		retention: LiveAmbientRetentionGrant,
		leaseIdentity: AmbientReconciliationIdentity,
	): String = buildString {
		append(
			retentionDigest(
				consumerId,
				sourcePolicyRevision,
				consentEpoch,
				requestedBootId,
				requestedElapsedRealtimeNanos,
				minimumAcquisitionSpec,
				AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				retention.opaquePolicyId,
				retention.approvalRevision,
			),
		)
		append('.')
		append(leaseIdentity.rolloutRevision)
		append('.')
		append(leaseIdentity.executionRevision)
		append('.')
		append(
			Base64.getUrlEncoder().withoutPadding().encodeToString(
				leaseIdentity.ownerCasToken.toByteArray(Charsets.UTF_8),
			),
		)
	}

	fun matches(
		demand: SourceDemandEntity,
		retention: LiveAmbientRetentionGrant,
		leaseIdentity: AmbientReconciliationIdentity,
	): Boolean =
		retention.source == SourceKind.STEPS &&
			retention.sourcePolicyRevision == demand.sourcePolicyRevision &&
			retention.ambientConsentEpoch == demand.consentEpoch &&
			retention.collectedDataEpoch == leaseIdentity.collectedDataEpoch &&
			retention.retainedFromMs == leaseIdentity.retainedFromMs &&
			retention.opaquePolicyId == leaseIdentity.retentionPolicyId &&
			retention.approvalRevision == leaseIdentity.retentionApprovalRevision &&
			demand.demandId == create(
				demand.consumerId,
				demand.sourcePolicyRevision,
				demand.consentEpoch,
				demand.requestedBootId,
				demand.requestedElapsedRealtimeNanos,
				demand.minimumAcquisitionSpec,
				retention,
				leaseIdentity,
			)

	fun parseLeaseBinding(demandId: String): AmbientStepsLeaseBinding? {
		return try {
			val parts = demandId.split('.')
			if (parts.size != AMBIENT_STEPS_LEASE_BINDING_COMPONENT_COUNT) {
				return null
			}
			val ownerCasToken = Base64.getUrlDecoder().decode(parts[3])
				.toString(Charsets.UTF_8)
			AmbientStepsLeaseBinding(
				rolloutRevision = parts[1].toLong(),
				executionRevision = parts[2].toLong(),
				ownerCasToken = ownerCasToken,
			)
		} catch (_: IllegalArgumentException) {
			null
		}
	}

	fun matchesRetention(
		demand: SourceDemandEntity,
		retention: AmbientStepsRetentionAuthorityEntity,
	): Boolean =
		demand.sourceKind == SourceKind.STEPS.stableCode &&
			demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
			demand.demandId.substringBefore('.') == retentionDigest(
				consumerId = demand.consumerId,
				sourcePolicyRevision = demand.sourcePolicyRevision,
				consentEpoch = demand.consentEpoch,
				requestedBootId = demand.requestedBootId,
				requestedElapsedRealtimeNanos = demand.requestedElapsedRealtimeNanos,
				minimumAcquisitionSpec = demand.minimumAcquisitionSpec,
				retention = retention,
			)

	private fun retentionDigest(
		consumerId: String,
		sourcePolicyRevision: Long,
		consentEpoch: Long,
		requestedBootId: String,
		requestedElapsedRealtimeNanos: Long,
		minimumAcquisitionSpec: String,
		retention: AmbientStepsRetentionAuthorityEntity,
	): String = retentionDigest(
		consumerId,
		sourcePolicyRevision,
		consentEpoch,
		requestedBootId,
		requestedElapsedRealtimeNanos,
		minimumAcquisitionSpec,
		retention.scope,
		retention.opaquePolicyId,
		retention.approvalRevision,
	)

	private fun retentionDigest(
		consumerId: String,
		sourcePolicyRevision: Long,
		consentEpoch: Long,
		requestedBootId: String,
		requestedElapsedRealtimeNanos: Long,
		minimumAcquisitionSpec: String,
		scope: String,
		opaquePolicyId: String,
		approvalRevision: Long,
	): String = digest(
		"ambient-steps-retention-demand-v1",
		consumerId,
		SourceKind.STEPS.stableCode,
		SourceBrokerPurpose.AMBIENT_PRODUCT,
		sourcePolicyRevision,
		consentEpoch,
		requestedBootId,
		requestedElapsedRealtimeNanos,
		minimumAcquisitionSpec,
		scope,
		opaquePolicyId,
		approvalRevision,
	)

	private fun digest(vararg values: Any): String {
		val canonical = values.joinToString(separator = "") { value ->
			val text = value.toString()
			"${text.length}:$text"
		}
		return MessageDigest.getInstance("SHA-256")
			.digest(canonical.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}
}

private data class AmbientStepsLeaseBinding(
	val rolloutRevision: Long,
	val executionRevision: Long,
	val ownerCasToken: String,
) {
	init {
		require(rolloutRevision >= 0L)
		require(executionRevision >= 0L)
		require(ownerCasToken.isNotBlank())
	}
}

private data class AmbientRadioRetentionAuthority(
	val sourcePolicyRevision: Long?,
	val ambientConsentEpoch: Long?,
	val collectedDataEpoch: Long,
	val retainedFromMs: Long?,
	val opaquePolicyId: String,
	val approvalRevision: Long,
	val isActive: Boolean,
) {
	init {
		require(sourcePolicyRevision == null || sourcePolicyRevision > 0L)
		require(ambientConsentEpoch == null || ambientConsentEpoch >= 0L)
		require(collectedDataEpoch >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= MAX_AMBIENT_RETENTION_POLICY_ID_LENGTH)
		require(approvalRevision > 0L)
	}
}

internal data class LiveAmbientRetentionSnapshot(
	val grants: Map<SourceKind, LiveAmbientRetentionGrant>,
	val settlementOperationId: String? = null,
) {
	init {
		require(grants.all { (source, grant) -> source == grant.source })
		require(settlementOperationId == null || settlementOperationId.isNotBlank())
	}
}

internal class RetentionFloorSettlementOperationContext(
	val operationId: String,
) : AbstractCoroutineContextElement(Key) {
	init {
		require(operationId.isNotBlank())
	}

	companion object Key : CoroutineContext.Key<RetentionFloorSettlementOperationContext>
}

internal suspend fun <T> withRetentionFloorSettlementOperation(
	operationId: String?,
	operation: suspend () -> T,
): T = if (operationId == null) {
	operation()
} else {
	withContext(RetentionFloorSettlementOperationContext(operationId)) { operation() }
}

private suspend fun currentRetentionFloorSettlementOperationId(): String? =
	currentCoroutineContext()[RetentionFloorSettlementOperationContext]?.operationId

internal data class LiveAmbientRetentionGrant(
	val source: SourceKind,
	val sourcePolicyRevision: Long,
	val ambientConsentEpoch: Long,
	val collectedDataEpoch: Long,
	val retainedFromMs: Long?,
	val opaquePolicyId: String,
	val approvalRevision: Long,
	val effectiveBootId: String,
	val effectiveElapsedRealtimeNanos: Long,
	val effectiveWallTimeMs: Long,
) {
	init {
		require(sourcePolicyRevision > 0L)
		require(ambientConsentEpoch > 0L)
		require(collectedDataEpoch >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(opaquePolicyId.isNotBlank())
		require(approvalRevision > 0L)
		require(effectiveBootId.isNotBlank())
		require(effectiveElapsedRealtimeNanos >= 0L)
		require(effectiveWallTimeMs >= 0L)
	}

	fun matchesDemand(demand: SourceDemandEntity): Boolean =
		demand.sourceKind == source.stableCode &&
		demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
		demand.sourcePolicyRevision == sourcePolicyRevision &&
		demand.consentEpoch == ambientConsentEpoch &&
		demand.liveAmbientRetentionPolicyId == opaquePolicyId &&
			demand.liveAmbientRetentionApprovalRevision == approvalRevision

	fun isEffectiveAt(
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Boolean = effectiveBootId == bootId &&
		effectiveElapsedRealtimeNanos <= elapsedRealtimeNanos &&
		(source == SourceKind.ACTIVITY || source == SourceKind.PRESSURE || effectiveWallTimeMs <= wallTimeMs)
}

internal sealed interface AmbientRadioDemandResult {
	data class Active(
		val demand: SourceDemandEntity,
		val authorityRevision: Long,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority,
	) : AmbientRadioDemandResult {
		init {
			require(authorityRevision > 0L)
			require(reconciliationAuthority.authorityRevision == authorityRevision)
			require(reconciliationAuthority.source.stableCode == demand.sourceKind)
			require(reconciliationAuthority.policyRevision == demand.sourcePolicyRevision)
			require(reconciliationAuthority.ambientConsentEpoch == demand.consentEpoch)
		}
	}

	data class Inactive(
		val reason: AmbientRadioDemandInactiveReason,
		val reconciliationAuthority: AmbientRadioReconciliationAuthority? = null,
		val retiredDemandId: String? = null,
	) : AmbientRadioDemandResult {
		init {
			require(retiredDemandId == null || retiredDemandId.isNotBlank())
		}
	}
}

private fun AmbientRadioLeaseMutation<AmbientRadioDemandResult>.toDemandResult():
	AmbientRadioDemandResult = when (this) {
	is AmbientRadioLeaseMutation.Applied -> value
	AmbientRadioLeaseMutation.Stale -> AmbientRadioDemandResult.Inactive(
		AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
	)
}

internal enum class AmbientRadioDemandInactiveReason {
	REQUEST_DISABLED,
	AUTHORITY_INACTIVE,
	POLICY_MISSING,
	CONSENT_REVOKED,
	PERSISTENCE_INELIGIBLE,
	ROLLOUT_CONTAINED,
	RETENTION_APPROVAL_MISSING,
	RETENTION_APPROVAL_MISMATCH,
	SOURCE_EVIDENCE_STATE_MISSING,
	AUTHORITY_REVISION_EXHAUSTED,
	STALE_RECONCILIATION_LEASE,
	STALE_RECONCILIATION_ATTEMPT,
	OWNERSHIP_CONFLICT,
	DELETION_AUTHORITY_MISMATCH,
}

/**
 * Snapshot of radio policy, consent, data epoch and exact owner attempt used to reject stale
 * reconciliation results.
 */
data class AmbientRadioReconciliationAuthority(
	val source: SourceKind,
	val policyRevision: Long?,
	val ambientConsentEpoch: Long?,
	val collectedDataEpoch: Long?,
	val retainedFromMs: Long? = null,
	val rolloutRevision: Long,
	val executionGeneration: Long?,
	val authorityRevision: Long?,
	val ownerCasToken: String?,
	val reconciliationAttempt: Long?,
) {
	init {
		require(source == SourceKind.WIFI || source == SourceKind.CELL)
		require(policyRevision == null || policyRevision > 0L)
		require(ambientConsentEpoch == null || ambientConsentEpoch >= 0L)
		require(collectedDataEpoch == null || collectedDataEpoch >= 0L)
		require(retainedFromMs == null || retainedFromMs >= 0L)
		require(rolloutRevision >= 0L)
		require(executionGeneration == null || executionGeneration > 0L)
		require(authorityRevision == null || authorityRevision > 0L)
		require(ownerCasToken == null || ownerCasToken.isNotBlank())
		require(reconciliationAttempt == null || reconciliationAttempt > 0L)
		require((executionGeneration == null) == (authorityRevision == null))
		require((ownerCasToken == null) == (reconciliationAttempt == null))
	}
}


internal sealed interface AmbientRadioRetirementPlan {
	data object AlreadyRetired : AmbientRadioRetirementPlan
	data class Required(
		val lease: AmbientReconciliationLease,
		val previousReconciliationAttempt: Long,
		val expectedDemandId: String,
		val expectedAuthorityRevision: Long,
		val sourceCallerAuthorityReference: SourceCallerReplayReference,
	) : AmbientRadioRetirementPlan {
		init {
			require(previousReconciliationAttempt > 0L)
			require(expectedDemandId.isNotBlank())
			require(expectedAuthorityRevision > 0L)
		}
	}
	data object Unverifiable : AmbientRadioRetirementPlan
}


private data class AmbientRadioAuthority(
	val authorityRevision: Long,
	val state: String,
	val sourcePolicyRevision: Long,
	val ambientConsentEpoch: Long,
	val retentionPolicyId: String,
	val retentionApprovalRevision: Long,
	val collectedDataEpoch: Long,
	val scopeDeletionGeneration: Long,
	val effectiveBootId: String,
	val effectiveElapsedRealtimeNanos: Long,
	val effectiveWallTimeMs: Long,
	val rolloutRevision: Long,
	val ownerCasToken: String,
	val reconciliationAttempt: Long,
	val demandId: String?,
	val executionGeneration: Long,
)

private fun SourceDemandEntity.matchesRadioReductionDemand(
	source: SourceKind,
	consumerId: String,
	plan: AmbientRadioRetirementPlan.Required,
): Boolean {
	if (demandId != plan.expectedDemandId || this.consumerId != consumerId ||
		sourceKind != source.stableCode || purpose != SourceBrokerPurpose.AMBIENT_PRODUCT) {
		return false
	}
	val identity = plan.lease.identity
	if (sourcePolicyRevision != identity.policyRevision || consentEpoch != identity.consentEpoch ||
		liveAmbientRetentionPolicyId != identity.retentionPolicyId ||
		liveAmbientRetentionApprovalRevision != identity.retentionApprovalRevision) {
		return false
	}
	return sourceCallerAuthorityReference == plan.sourceCallerAuthorityReference.value
}

private suspend fun AppDatabase.authenticatedAmbientRadioAuthority(source: SourceKind): AmbientRadioAuthority? =
	when (source) {
		SourceKind.WIFI -> ambientWifiFactDao().latestAuthority()
			?.takeIf(AmbientWifiAuthorityIntegrity::isAuthentic)?.toRadioAuthority()
		SourceKind.CELL -> ambientCellFactDao().latestAuthority()
			?.takeIf(AmbientCellAuthorityIntegrity::isAuthentic)?.toRadioAuthority()
		else -> null
	}

private fun AmbientRadioAuthority.matchesRadioReductionPlan(plan: AmbientRadioRetirementPlan.Required): Boolean {
	val identity = plan.lease.identity
	return state == AmbientWifiAuthorityEntity.STATE_ACTIVE && authorityRevision == plan.expectedAuthorityRevision &&
		matchesRadioReductionGrant(identity) && matchesRadioReductionExecution(identity, plan)
}

private fun AmbientRadioAuthority.matchesRadioReductionGrant(identity: AmbientReconciliationIdentity): Boolean =
	sourcePolicyRevision == identity.policyRevision && ambientConsentEpoch == identity.consentEpoch &&
		retentionPolicyId == identity.retentionPolicyId && retentionApprovalRevision == identity.retentionApprovalRevision

private fun AmbientRadioAuthority.matchesRadioReductionExecution(
	identity: AmbientReconciliationIdentity,
	plan: AmbientRadioRetirementPlan.Required,
): Boolean {
	if (collectedDataEpoch != identity.collectedDataEpoch || rolloutRevision != identity.rolloutRevision ||
		executionGeneration != identity.executionRevision || ownerCasToken != identity.ownerCasToken) {
		return false
	}
	return reconciliationAttempt == plan.previousReconciliationAttempt && demandId == plan.expectedDemandId
}

private fun StoredSourceCallerAuthorityLoadResult.authorizesAmbientRadioLease(
	identity: AmbientReconciliationIdentity,
): Boolean = this is StoredSourceCallerAuthorityLoadResult.Available &&
	authority.origin == StoredSourceCallerOrigin.PURPOSE_OWNER &&
	authority.purpose == TrackingPurpose.AMBIENT_PRODUCT &&
	authority.permittedDemandIdentities == setOf(SourceCallerDemandIdentity(identity.purposeLeaseIdentity,
		manifestIdentity = null))

private fun AmbientWifiAuthorityEntity.toRadioAuthority() = AmbientRadioAuthority(
	authorityRevision,
	state,
	sourcePolicyRevision,
	ambientConsentEpoch,
	retentionPolicyId,
	retentionApprovalRevision,
	collectedDataEpoch,
	scopeDeletionGeneration,
	effectiveBootId,
	effectiveElapsedRealtimeNanos,
	effectiveWallTimeMs,
	rolloutRevision,
	ownerCasToken,
	reconciliationAttempt,
	demandId,
	writerOwnerGeneration,
)

private fun AmbientCellAuthorityEntity.toRadioAuthority() = AmbientRadioAuthority(
	authorityRevision,
	state,
	sourcePolicyRevision,
	ambientConsentEpoch,
	retentionPolicyId,
	retentionApprovalRevision,
	collectedDataEpoch,
	scopeDeletionGeneration,
	effectiveBootId,
	effectiveElapsedRealtimeNanos,
	effectiveWallTimeMs,
	rolloutRevision,
	ownerCasToken,
	reconciliationAttempt,
	demandId,
	writerOwnerGeneration,
)

private fun AmbientWifiRetentionAuthorityEntity.toRadioRetentionAuthority():
	AmbientRadioRetentionAuthority? {
	if (!AmbientWifiRetentionAuthorityIntegrity.isAuthentic(this)) {
		return null
	}
	return AmbientRadioRetentionAuthority(
		sourcePolicyRevision,
		ambientConsentEpoch,
		collectedDataEpoch,
		retainedFromMs,
		opaquePolicyId,
		approvalRevision,
		state == AmbientWifiRetentionAuthorityEntity.STATE_ACTIVE,
	)
}

private fun AmbientCellRetentionAuthorityEntity.toRadioRetentionAuthority():
	AmbientRadioRetentionAuthority? {
	if (!AmbientCellRetentionAuthorityIntegrity.isAuthentic(this)) {
		return null
	}
	return AmbientRadioRetentionAuthority(
		sourcePolicyRevision,
		ambientConsentEpoch,
		collectedDataEpoch,
		retainedFromMs,
		opaquePolicyId,
		approvalRevision,
		state == AmbientCellRetentionAuthorityEntity.STATE_ACTIVE,
	)
}
private fun SourceKind.toAmbientTrackingSource(): AmbientTrackingSource = when (this) {
	SourceKind.WIFI -> AmbientTrackingSource.WIFI
	SourceKind.CELL -> AmbientTrackingSource.CELL
	else -> error("$this is not an Ambient radio source")
}

private fun SourceKind.toTrackingSourceComponent(): TrackingSourceComponent = when (this) {
	SourceKind.LOCATION -> TrackingSourceComponent.LOCATION
	SourceKind.ACTIVITY -> TrackingSourceComponent.ACTIVITY
	SourceKind.STEPS -> TrackingSourceComponent.STEPS
	SourceKind.PRESSURE -> TrackingSourceComponent.PRESSURE
	SourceKind.WIFI -> TrackingSourceComponent.WIFI
	SourceKind.CELL -> TrackingSourceComponent.CELL
}

private fun String.toCallerReferenceOrNull(): SourceCallerReplayReference? =
	runCatching { SourceCallerReplayReference(this) }.getOrNull()

internal fun SourceDemandEntity.toSourceDemandContract(): SourceDemandContract =
	SourceDemandContract.decode(
		source = SourceKind.entries.single { source -> source.stableCode == sourceKind },
		floorSpec = minimumAcquisitionSpec,
		maximumProviderItemAgeMs = maximumAgeMs,
		targetPlanningLatencyMs = desiredLatencyMs,
		requestedDeliveryLatencyMs = requestedDeliveryLatencyMs,
		adaptiveReductionAllowed = adaptiveReductionAllowed,
	)

private fun String.toDirectDemandPurpose(): DirectSourceDemandPurpose = when (this) {
	SourceBrokerPurpose.SESSION_CAPTURE -> DirectSourceDemandPurpose.SESSION_CAPTURE
	SourceBrokerPurpose.CONTROL_CONTINUATION -> DirectSourceDemandPurpose.CONTROL_CONTINUATION
	SourceBrokerPurpose.CONTROL_AUTOSTART -> DirectSourceDemandPurpose.CONTROL_AUTOSTART
	SourceBrokerPurpose.AMBIENT_PRODUCT -> DirectSourceDemandPurpose.AMBIENT_PRODUCT
	else -> error("Unsupported direct demand purpose $this")
}

data class ProviderRegistrationAuthorization(
	val generation: ProviderRegistrationGenerationEntity,
	val authorization: SourceAuthorizationSnapshot,
) {
	fun captureEligibility(
		logicalTrackingId: String,
		manifestRevision: Long,
	): SourceAuthorizationEntity? = authorization.authorizedMembers.singleOrNull { item ->
		item.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
			item.logicalTrackingId == logicalTrackingId &&
			item.manifestRevision == manifestRevision &&
			item.persistenceEligible
	}
}

private const val AMBIENT_STEPS_LEASE_BINDING_COMPONENT_COUNT = 4
private const val MAX_AMBIENT_RETENTION_POLICY_ID_LENGTH = 256
