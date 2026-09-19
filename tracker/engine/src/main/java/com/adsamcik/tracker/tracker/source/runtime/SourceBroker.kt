package com.adsamcik.tracker.tracker.source.runtime

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.reconcileActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.database.rotateCurrentSourceAuthorizationInTransaction
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
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationSnapshot
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.toAuthorizationSnapshotOrNull
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityReader
import com.adsamcik.tracker.shared.preferences.retention.UnavailableRetentionAuthorityProducer
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
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable authority for source consumers and physical registration eligibility.
 *
 * Provider adapters still own Android-specific calls, but they may reserve an identity only from
 * this broker's active demand vector. The vector is persisted before registration and later used to
 * reject callbacks that attempt to cross a purpose, policy, consent, manifest, or generation fence.
 */
@Singleton
class SourceBroker @Inject internal constructor(
	private val database: AppDatabase,
	private val trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	private val ambientRadioMutationLeaseGuard: AmbientRadioMutationLeaseGuard,
	private val sourceCallerAuthorityRepository: SourceCallerAcceptedAuthorityRepository,
	private val retentionAuthorityReader: RetentionAuthorityReader,
) {
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
	) : this(
		database,
		trackingRolloutStateStore,
		RejectingAmbientRadioMutationLeaseGuard,
		RoomSourceCallerAcceptedAuthorityRepository(database),
		retentionAuthorityReader,
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
	fun sessionConsumerId(logicalTrackingId: String): String = "session:$logicalTrackingId"

	/** Current durable authority vector used to reconcile the one physical source owner. */
	internal suspend fun authorizationDemands(source: SourceKind): List<SourceDemandEntity> =
		database.sourceBrokerDao().authorizationDemands(source.stableCode)

	/**
	 * Acquires retention authority before Room. The returned identity is only a candidate until the
	 * transaction performs an exact comparison with the append-only retention row.
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
		val ambientDemands = demands.filter { demand ->
			demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		}
		val grants = linkedMapOf<SourceKind, LiveAmbientRetentionGrant>()
		for (demand in ambientDemands) {
			val source = SourceKind.entries.singleOrNull {
				it.stableCode == demand.sourceKind
			} ?: return null
			val existing = grants[source]
			if (existing != null) {
				if (!existing.matchesDemand(demand)) return null
				continue
			}
			if (demand.liveAmbientRetentionPolicyId.isNullOrBlank() ||
				demand.liveAmbientRetentionApprovalRevision == null
			) return null
			val captured = captureLiveAmbientRetentionGrant(
				source = source,
				sourcePolicyRevision = demand.sourcePolicyRevision,
				ambientConsentEpoch = demand.consentEpoch,
				collectedDataEpoch = expectedCollectedDataEpoch,
				currentBootId = currentBootId,
				currentElapsedRealtimeNanos = currentElapsedRealtimeNanos,
				currentWallTimeMs = currentWallTimeMs,
			) ?: return null
			if (!captured.matchesDemand(demand)) return null
			grants[source] = captured
		}
		return LiveAmbientRetentionSnapshot(grants.toMap())
	}

	internal suspend fun captureLiveAmbientRetentionSnapshot(
		source: SourceKind,
		sourcePolicyRevision: Long,
		ambientConsentEpoch: Long,
		collectedDataEpoch: Long,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
	): LiveAmbientRetentionSnapshot? = captureLiveAmbientRetentionGrant(
		source,
		sourcePolicyRevision,
		ambientConsentEpoch,
		collectedDataEpoch,
		currentBootId,
		currentElapsedRealtimeNanos,
		currentWallTimeMs,
	)?.let { grant -> LiveAmbientRetentionSnapshot(mapOf(source to grant)) }

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
		val ambientDemands = demands.filter { demand ->
			demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT
		}
		val expectedSources = ambientDemands.mapTo(linkedSetOf()) { demand ->
			SourceKind.entries.singleOrNull { it.stableCode == demand.sourceKind } ?: return false
		}
		if (retentionSnapshot.grants.keys != expectedSources) return false
		return ambientDemands.all { demand ->
			val source = SourceKind.entries.single { it.stableCode == demand.sourceKind }
			val grant = retentionSnapshot.grants[source] ?: return@all false
			grant.matchesDemand(demand) &&
				isLiveAmbientRetentionCurrentInTransaction(
					grant,
					currentBootId,
					currentElapsedRealtimeNanos,
					currentWallTimeMs,
				)
		}
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
			val contract = SourceDemandContractFactory.forQos(
				source,
				binding.qosCode,
				purpose.toDirectDemandPurpose(),
			)
			SourceDemandEntity(
				demandId = demandId(
					consumerId,
					binding.sourceKind,
					purpose,
					policyRevision,
					binding.consentEpoch,
					manifestRevision,
					bootId,
					elapsedRealtimeNanos,
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

	/** Must be called from the manifest/intent transaction. */
	internal suspend fun replaceSessionDemandsInTransaction(
		logicalTrackingId: String,
		demands: List<SourceDemandEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		retireSupersededAuthority: Boolean = true,
	) {
		val consumerId = sessionConsumerId(logicalTrackingId)
		val dao = database.sourceBrokerDao()
		val affectedSources = (dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind) +
			demands.map(SourceDemandEntity::sourceKind)).toSet()
		val retiredReferences = (
			currentAuthorityReferences(consumerId) +
				database.sourceSessionDao().lifecycleIntents(logicalTrackingId)
					.mapNotNull { intent -> intent.sourceCallerAuthorityReference }
					.mapNotNull(String::toCallerReferenceOrNull)
		).toSet()
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		if (retireSupersededAuthority) {
			retireAuthorityReferences(retiredReferences, "SESSION_DEMAND_REPLACED", wallTimeMs)
		}
		if (demands.isNotEmpty()) dao.insertDemands(demands)
		affectedSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(
				sourceKind,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
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
		val retiredReferences = (
			currentAuthorityReferences(consumerId) +
				database.sourceSessionDao().lifecycleIntents(logicalTrackingId)
					.mapNotNull { intent -> intent.sourceCallerAuthorityReference }
					.mapNotNull(String::toCallerReferenceOrNull)
		).toSet()
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		retireAuthorityReferences(retiredReferences, "SESSION_DEMAND_STAGED", wallTimeMs)
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

	/** Opens only the exact foreground-accepted prepared demand vector. */
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
	): Boolean {
		val consumerId = sessionConsumerId(logicalTrackingId)
		val dao = database.sourceBrokerDao()
		val exact = dao.demandHistory(consumerId).filter { demand ->
			demand.serviceRunId == serviceRunId &&
				demand.manifestRevision == manifestRevision &&
				demand.lifecycleLeaseGeneration == leaseGeneration
		}
		if (exact.isEmpty() || exact.any { demand ->
				demand.status !in setOf(
					SourceDemandEntity.STATUS_BLOCKED,
					SourceDemandEntity.STATUS_ACTIVE,
			) ||
				demand.sourceCallerAuthorityReference != sourceCallerAuthorityReference
		}) return false
		if (!currentAuthority.permitsActivation(
				SourceCallerReplayReference(sourceCallerAuthorityReference),
				SourceCallerManifestIdentity(logicalTrackingId, manifestRevision),
				exact,
			)
		) return false
		val blocked = exact.filter { it.status == SourceDemandEntity.STATUS_BLOCKED }
		if (blocked.isEmpty()) return true
		if (dao.activatePreparedSessionDemands(
			consumerId,
			serviceRunId,
			manifestRevision,
			leaseGeneration,
		) != blocked.size) return false
		blocked.map(SourceDemandEntity::sourceKind).toSet().forEach { sourceKind ->
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
		val retiredReferences = (
			currentAuthorityReferences(consumerId) +
				database.sourceSessionDao().lifecycleIntents(logicalTrackingId)
					.mapNotNull { intent -> intent.sourceCallerAuthorityReference }
					.mapNotNull(String::toCallerReferenceOrNull)
		).toSet()
		val updated = dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		if (retireCallerAuthority) {
			retireAuthorityReferences(retiredReferences, "SESSION_DEMAND_RETIRED", wallTimeMs)
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

	/** Retires every non-current session authority after its new DataStore reference is visible. */
	internal suspend fun retireSupersededSessionAuthoritiesInTransaction(
		logicalTrackingId: String,
		currentReference: SourceCallerReplayReference,
		expectedSupersededReference: SourceCallerReplayReference,
		wallTimeMs: Long,
	): Boolean {
		val references = (
			currentAuthorityReferences(sessionConsumerId(logicalTrackingId)) +
				database.sourceSessionDao().lifecycleIntents(logicalTrackingId)
					.mapNotNull { intent -> intent.sourceCallerAuthorityReference }
					.mapNotNull(String::toCallerReferenceOrNull)
		).toSet()
		val superseded = references
			.filterTo(linkedSetOf()) { reference -> reference != currentReference }
		if (expectedSupersededReference !in superseded) return false
		return retireAuthorityReferences(
			superseded,
			"SESSION_AUTHORITY_SUPERSEDED",
			wallTimeMs,
		)
	}

	private suspend fun retireAuthorityReferences(
		references: Set<SourceCallerReplayReference>,
		reason: String,
		wallTimeMs: Long,
	): Boolean {
		var complete = true
		references.forEach { reference ->
			try {
				if (!sourceCallerAuthorityRepository.retireForTeardown(
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

	private suspend fun retireDemandAuthority(
		demand: SourceDemandEntity,
		reason: String,
		wallTimeMs: Long,
	): Boolean = demand.sourceCallerAuthorityReference?.toCallerReferenceOrNull()?.let { reference ->
		retireAuthorityReferences(
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
	): SourceDemandEntity? = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val affectedSourceKinds = (
			dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind) + source.stableCode
		).toSet()
		val priorReferences = currentAuthorityReferences(consumerId)
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		retireAuthorityReferences(priorReferences, "AUTOMATIC_CONTROL_REPLACED", wallTimeMs)
		if (!enabled) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			reconcileAutomaticControlEpochInTransaction(
				source,
				false,
				"AUTOMATIC_CONTROL_DISABLED",
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			reconcileAutomaticControlEpochInTransaction(
				source,
				false,
				"AUTOMATIC_CONTROL_AUTHORITY_INACTIVE",
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, source.stableCode)
		if (policy == null) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			reconcileAutomaticControlEpochInTransaction(
				source,
				false,
				"AUTOMATIC_CONTROL_POLICY_MISSING",
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		val consentEpoch = policy.controlConsentEpoch
		if (consentEpoch == null) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			reconcileAutomaticControlEpochInTransaction(
				source,
				false,
				"AUTOMATIC_CONTROL_REVOKED",
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		if (!automaticControlAcquisitionEligibleInTransaction(source)) {
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			reconcileAutomaticControlEpochInTransaction(
				source,
				false,
				"AUTOMATIC_CONTROL_ROLLOUT_CONTAINED",
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return@withTransaction null
		}
		val contract = SourceDemandContractFactory.forQos(
			source,
			policy.qosCode,
			DirectSourceDemandPurpose.CONTROL_AUTOSTART,
		).copy(
			maximumProviderItemAgeMs = maximumAgeMs,
			targetPlanningLatencyMs = desiredLatencyMs,
		)
		val demand = SourceDemandEntity(
			demandId = demandId(
				consumerId,
				source.stableCode,
				SourceBrokerPurpose.CONTROL_AUTOSTART,
				authority.currentPolicyRevision,
				consentEpoch,
				null,
				bootId,
				elapsedRealtimeNanos,
			),
			consumerId = consumerId,
			sourceKind = source.stableCode,
			purpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = authority.currentPolicyRevision,
			consentEpoch = consentEpoch,
			persistenceEligible = policy.controlPersistenceEligible,
			qosCode = policy.qosCode,
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
		dao.insertDemands(listOf(demand))
		rotateCurrentAuthorizationsInTransaction(
			affectedSourceKinds,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		reconcileAutomaticControlEpochInTransaction(
			source,
			true,
			"AUTOMATIC_CONTROL_ENABLED",
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		demand
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
			return@withTransaction retireAuthorityReferences(
				historicalReferences,
				"PURPOSE_OWNER_DEMAND_RETIRED",
				wallTimeMs,
			)
		}
		val exactOwner = current.all { demand ->
			demand.sourceKind == expectedSourceKind && demand.purpose == expectedPurpose
		}
		val references = current.mapNotNull(SourceDemandEntity::sourceCallerAuthorityReference)
			.mapNotNullTo(linkedSetOf(), String::toCallerReferenceOrNull)
		val affectedSources = current.mapTo(linkedSetOf(), SourceDemandEntity::sourceKind)
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		val callerAuthorityRetired = retireAuthorityReferences(
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
		exactOwner && callerAuthorityRetired
	}

	/**
	 * App-scoped control is useful only when its control-operational lane and at least one currently
	 * enabled capture source's product lane are both reachable. Control reachability deliberately
	 * does not make the control source captured or materializable.
	 */
	private suspend fun automaticControlAcquisitionEligibleInTransaction(source: SourceKind): Boolean {
		val rollout = trackingRolloutStateStore.load()
		if (!rollout.isControlAcquisitionReachable(source)) return false
		val authority = database.sourcePolicyDao().authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) return false
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
		if (source != SourceKind.ACTIVITY) return
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
	): AmbientStepsDemandResult = database.withTransaction {
		require(consumerId.isNotBlank())
		require(leaseIdentity.source == AmbientTrackingSource.STEPS)
		val source = SourceKind.STEPS
		val dao = database.sourceBrokerDao()
		val currentDemands = dao.currentDemands(consumerId)
		val affectedSourceKinds = (
			currentDemands.map(SourceDemandEntity::sourceKind) + source.stableCode
		).toSet()

		suspend fun inactive(
			reason: AmbientStepsDemandInactiveReason,
			retention: AmbientStepsRetentionAuthorityEntity? = null,
		): AmbientStepsDemandResult {
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
			dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
			retireAuthorityReferences(priorReferences, "AMBIENT_STEPS_RETIRED", wallTimeMs)
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return AmbientStepsDemandResult.Inactive(reason)
		}

		if (mechanism == null) {
			return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.REQUEST_DISABLED,
				database.ambientStepsFactRevisionDao().latestRetentionAuthority(
					AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
				),
			)
		}
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		if (
			authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE ||
			authority.currentPolicyRevision != leaseIdentity.policyRevision
		) {
			return@withTransaction inactive(AmbientStepsDemandInactiveReason.AUTHORITY_INACTIVE)
		}
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, source.stableCode)
			?: return@withTransaction inactive(AmbientStepsDemandInactiveReason.POLICY_MISSING)
		val consentEpoch = policy.ambientConsentEpoch
			?: return@withTransaction inactive(AmbientStepsDemandInactiveReason.CONSENT_REVOKED)
		if (consentEpoch != leaseIdentity.consentEpoch) {
			return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
		}
		if (!policy.ambientPersistenceEligible) {
			return@withTransaction inactive(AmbientStepsDemandInactiveReason.PERSISTENCE_INELIGIBLE)
		}
		val rollout = trackingRolloutStateStore.load()
		if (
			rollout.revision != leaseIdentity.rolloutRevision ||
			!rollout.isCaptureReachable(source, CaptureReachabilityMode.AMBIENT)
		) {
			return@withTransaction inactive(AmbientStepsDemandInactiveReason.ROLLOUT_CONTAINED)
		}
		val evidence = database.sourceEvidenceStateDao().get()
			?: return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.RETENTION_APPROVAL_MISSING,
			)
		val retention = retentionSnapshot?.grants?.get(source)
			?.takeIf { grant ->
				grant.sourcePolicyRevision == authority.currentPolicyRevision &&
					grant.ambientConsentEpoch == consentEpoch &&
					grant.collectedDataEpoch == evidence.collectedDataEpoch &&
					grant.collectedDataEpoch == leaseIdentity.collectedDataEpoch &&
					isLiveAmbientRetentionCurrentInTransaction(
						grant,
						bootId,
						elapsedRealtimeNanos,
						wallTimeMs,
					)
			}
			?: return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.RETENTION_AUTHORITY_UNAVAILABLE,
			)

		val contract = SourceDemandContractFactory.forAmbientSteps(mechanism)
		currentDemands.singleOrNull()?.takeIf { demand ->
			demand.status == SourceDemandEntity.STATUS_ACTIVE &&
				demand.sourceKind == source.stableCode &&
				demand.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				demand.sourcePolicyRevision == authority.currentPolicyRevision &&
				demand.consentEpoch == consentEpoch &&
				demand.persistenceEligible &&
				demand.qosCode == 0 &&
				demand.minimumAcquisitionSpec == contract.encodeFloor() &&
				demand.adaptiveReductionAllowed == contract.adaptiveReductionAllowed &&
				demand.maximumAgeMs == contract.maximumProviderItemAgeMs &&
				demand.desiredLatencyMs == contract.targetPlanningLatencyMs &&
				demand.requestedDeliveryLatencyMs == contract.requestedDeliveryLatencyMs &&
				AmbientStepsDemandIdentity.matches(demand, retention, leaseIdentity) &&
				demand.requestedBootId == bootId &&
				demand.liveAmbientRetentionPolicyId == retention.opaquePolicyId &&
				demand.liveAmbientRetentionApprovalRevision == retention.approvalRevision &&
				demand.sourceCallerAuthorityReference == sourceCallerAuthorityReference
		}?.let { unchanged -> return@withTransaction AmbientStepsDemandResult.Active(unchanged) }
		val priorReferences = currentAuthorityReferences(consumerId)
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		retireAuthorityReferences(priorReferences, "AMBIENT_STEPS_REPLACED", wallTimeMs)
		val demand = SourceDemandEntity(
			demandId = AmbientStepsDemandIdentity.create(
				consumerId = consumerId,
				sourcePolicyRevision = authority.currentPolicyRevision,
				consentEpoch = consentEpoch,
				requestedBootId = bootId,
				requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
				minimumAcquisitionSpec = contract.encodeFloor(),
				retention = retention,
				leaseIdentity = leaseIdentity,
			),
			consumerId = consumerId,
			sourceKind = source.stableCode,
			purpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			logicalTrackingId = null,
			serviceRunId = null,
			manifestRevision = null,
			lifecycleLeaseGeneration = null,
			sourcePolicyRevision = authority.currentPolicyRevision,
			consentEpoch = consentEpoch,
			persistenceEligible = true,
			qosCode = 0,
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
			liveAmbientRetentionPolicyId = retention.opaquePolicyId,
			liveAmbientRetentionApprovalRevision = retention.approvalRevision,
		)
		dao.insertDemands(listOf(demand))
		rotateCurrentAuthorizationsInTransaction(
			affectedSourceKinds,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		AmbientStepsDemandResult.Active(demand)
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
		if (demands.isEmpty()) return@withTransaction true
		val retentionPolicyId = leaseIdentity.retentionPolicyId ?: return@withTransaction false
		val retentionApprovalRevision =
			leaseIdentity.retentionApprovalRevision ?: return@withTransaction false
		val retention = database.ambientStepsFactRevisionDao().retentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
			retentionPolicyId,
			retentionApprovalRevision,
		) ?: return@withTransaction false
		if (
			demands.singleOrNull()?.let { demand ->
				AmbientStepsDemandIdentity.matches(demand, retention, leaseIdentity)
			} != true
		) {
			return@withTransaction false
		}
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
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
		if (demands.isEmpty()) return@withTransaction AmbientStepsRetirementPlan.AlreadyRetired
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
	): AmbientRadioDemandResult {
		val guarded = if (requested) {
			withAmbientRadioMutationLease(leaseIdentity) {
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
				)
			}
		} else {
			withAmbientRadioReductionLease(leaseIdentity) {
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
				)
			}
		}
		return guarded.toDemandResult()
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
					insertAuthority = { authority ->
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
					},
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
						if (marker == null) 0L else {
							marker.takeIf(AmbientWifiFactIntegrity::isAuthentic)
								?.deletionGeneration
						}
					},
					retentionSnapshot = retentionSnapshot,
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
	): AmbientRadioDemandResult {
		val guarded = if (requested) {
			withAmbientRadioMutationLease(leaseIdentity) {
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
				)
			}
		} else {
			withAmbientRadioReductionLease(leaseIdentity) {
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
				)
			}
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
					insertAuthority = { authority ->
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
					},
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
						if (marker == null) 0L else {
							marker.takeIf(AmbientCellFactIntegrity::isAuthentic)
								?.deletionGeneration
						}
					},
					retentionSnapshot = retentionSnapshot,
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

	internal suspend fun compensateAmbientWifiDemandUnderHeldLease(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		expectedDemandId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
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
			latestAuthority = {
				database.ambientWifiFactDao().latestAuthority()
					?.takeIf(AmbientWifiAuthorityIntegrity::isAuthentic)
					?.toRadioAuthority()
			},
			maximumAuthorityRevision = {
				database.ambientWifiFactDao().maximumAuthorityRevision()
			},
			insertAuthority = { authority ->
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
			},
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
			latestAuthority = {
				database.ambientCellFactDao().latestAuthority()
					?.takeIf(AmbientCellAuthorityIntegrity::isAuthentic)
					?.toRadioAuthority()
			},
			maximumAuthorityRevision = {
				database.ambientCellFactDao().maximumAuthorityRevision()
			},
			insertAuthority = { authority ->
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
			},
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
		val sourceAuthority = when (source) {
			SourceKind.WIFI -> database.ambientWifiFactDao().latestAuthority()?.takeIf {
				AmbientWifiAuthorityIntegrity.isAuthentic(it) &&
				it.sourcePolicyRevision == policyRevision &&
					it.ambientConsentEpoch == consentEpoch &&
					it.collectedDataEpoch == evidence?.collectedDataEpoch &&
					it.rolloutRevision == rollout.revision
			}?.let {
				AmbientRadioExecutionAuthority(
					it.authorityRevision,
					it.writerOwnerGeneration,
					it.ownerCasToken,
					it.reconciliationAttempt,
				)
			}
			SourceKind.CELL -> database.ambientCellFactDao().latestAuthority()?.takeIf {
				AmbientCellAuthorityIntegrity.isAuthentic(it) &&
				it.sourcePolicyRevision == policyRevision &&
					it.ambientConsentEpoch == consentEpoch &&
					it.collectedDataEpoch == evidence?.collectedDataEpoch &&
					it.rolloutRevision == rollout.revision
			}?.let {
				AmbientRadioExecutionAuthority(
					it.authorityRevision,
					it.writerOwnerGeneration,
					it.ownerCasToken,
					it.reconciliationAttempt,
				)
			}
			else -> null
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
		if (demands.size > 1 || demands.any {
			it.sourceKind != source.stableCode ||
				it.purpose != SourceBrokerPurpose.AMBIENT_PRODUCT
		}) {
			return@withTransaction AmbientRadioRetirementPlan.Unverifiable
		}
		val demand = demands.singleOrNull()
		val authority = when (source) {
			SourceKind.WIFI -> database.ambientWifiFactDao().latestAuthority()?.let { stored ->
				if (!AmbientWifiAuthorityIntegrity.isAuthentic(stored)) {
					return@withTransaction AmbientRadioRetirementPlan.Unverifiable
				}
				AmbientRadioRetirementAuthority(
					active = stored.isActive,
					policyRevision = stored.sourcePolicyRevision,
					consentEpoch = stored.ambientConsentEpoch,
					collectedDataEpoch = stored.collectedDataEpoch,
					rolloutRevision = stored.rolloutRevision,
					executionRevision = stored.writerOwnerGeneration,
					ownerCasToken = stored.ownerCasToken,
					retentionPolicyId = stored.retentionPolicyId,
					retentionApprovalRevision = stored.retentionApprovalRevision,
					reconciliationAttempt = stored.reconciliationAttempt,
					demandId = stored.demandId,
				)
			}
			SourceKind.CELL -> database.ambientCellFactDao().latestAuthority()?.let { stored ->
				if (!AmbientCellAuthorityIntegrity.isAuthentic(stored)) {
					return@withTransaction AmbientRadioRetirementPlan.Unverifiable
				}
				AmbientRadioRetirementAuthority(
					active = stored.isActive,
					policyRevision = stored.sourcePolicyRevision,
					consentEpoch = stored.ambientConsentEpoch,
					collectedDataEpoch = stored.collectedDataEpoch,
					rolloutRevision = stored.rolloutRevision,
					executionRevision = stored.writerOwnerGeneration,
					ownerCasToken = stored.ownerCasToken,
					retentionPolicyId = stored.retentionPolicyId,
					retentionApprovalRevision = stored.retentionApprovalRevision,
					reconciliationAttempt = stored.reconciliationAttempt,
					demandId = stored.demandId,
				)
			}
			else -> error("Unsupported ambient radio source $source")
		}
		if (authority == null || !authority.active) {
			return@withTransaction if (demand == null) {
				AmbientRadioRetirementPlan.AlreadyRetired
			} else {
				AmbientRadioRetirementPlan.Unverifiable
			}
		}
		if (
			demand == null ||
			authority.demandId != demand.demandId ||
			demand.sourcePolicyRevision != authority.policyRevision ||
			demand.consentEpoch != authority.consentEpoch
		) {
			return@withTransaction AmbientRadioRetirementPlan.Unverifiable
		}
		val evidence = database.sourceEvidenceStateDao().get()
			?: return@withTransaction AmbientRadioRetirementPlan.Unverifiable
		if (evidence.collectedDataEpoch != authority.collectedDataEpoch) {
			return@withTransaction AmbientRadioRetirementPlan.Unverifiable
		}
		AmbientRadioRetirementPlan.Required(
			AmbientReconciliationLease(
				AmbientReconciliationIdentity(
					source = source.toAmbientTrackingSource(),
					policyRevision = authority.policyRevision,
					consentEpoch = authority.consentEpoch,
					collectedDataEpoch = authority.collectedDataEpoch,
					rolloutRevision = authority.rolloutRevision,
					ownerCasToken = authority.ownerCasToken,
					executionRevision = authority.executionRevision,
					retainedFromMs = evidence.retainedFromMs,
					retentionPolicyId = authority.retentionPolicyId,
					retentionApprovalRevision = authority.retentionApprovalRevision,
				),
			),
			authority.reconciliationAttempt,
		)
	}

	@Suppress("LongParameterList")
	private suspend fun compensateAmbientRadioDemandInTransaction(
		consumerId: String,
		source: SourceKind,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		expectedDemandId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		latestAuthority: suspend () -> AmbientRadioAuthority?,
		maximumAuthorityRevision: suspend () -> Long,
		insertAuthority: suspend (AmbientRadioAuthority) -> Unit,
		retireExactDemand: suspend (SourceDemandEntity) -> Int,
	): AmbientRadioReconciliationAuthority? {
		require(source == SourceKind.WIFI || source == SourceKind.CELL)
		require(leaseIdentity.source == source.toAmbientTrackingSource())
		require(reconciliationAttempt > 0L)
		require(expectedDemandId.isNotBlank() && consumerId.isNotBlank() && bootId.isNotBlank())
		require(elapsedRealtimeNanos >= 0L && wallTimeMs >= 0L)
		val demandHistory = database.sourceBrokerDao().demandHistory(consumerId)
		val demand = demandHistory.singleOrNull {
			it.demandId == expectedDemandId &&
				it.sourceKind == source.stableCode &&
				it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
				it.sourcePolicyRevision == leaseIdentity.policyRevision &&
				it.consentEpoch == leaseIdentity.consentEpoch &&
				it.liveAmbientRetentionPolicyId == leaseIdentity.retentionPolicyId &&
				it.liveAmbientRetentionApprovalRevision ==
				leaseIdentity.retentionApprovalRevision
		} ?: return null
		val nonterminalDemands = demandHistory.filter { candidate ->
			candidate.status == SourceDemandEntity.STATUS_ACTIVE ||
				candidate.status == SourceDemandEntity.STATUS_RETIRING ||
				candidate.status == SourceDemandEntity.STATUS_BLOCKED
		}
		if (nonterminalDemands.any { it.demandId != expectedDemandId }) {
			if (demand.status != SourceDemandEntity.STATUS_RETIRED) return null
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
		val authority = latestAuthority()?.takeIf {
			(it.state == AmbientWifiAuthorityEntity.STATE_ACTIVE ||
				it.state == AmbientWifiAuthorityEntity.STATE_REVOKED) &&
				it.sourcePolicyRevision == leaseIdentity.policyRevision &&
				it.ambientConsentEpoch == leaseIdentity.consentEpoch &&
				it.retentionPolicyId == leaseIdentity.retentionPolicyId &&
				it.retentionApprovalRevision == leaseIdentity.retentionApprovalRevision &&
				it.collectedDataEpoch == leaseIdentity.collectedDataEpoch &&
				it.rolloutRevision == leaseIdentity.rolloutRevision &&
				it.executionGeneration == leaseIdentity.executionRevision &&
				it.ownerCasToken == leaseIdentity.ownerCasToken &&
				it.reconciliationAttempt == reconciliationAttempt &&
				it.demandId == expectedDemandId
		}
		if (authority == null && demand.status != SourceDemandEntity.STATUS_RETIRED) return null
		val retiredDemand = demand.status != SourceDemandEntity.STATUS_RETIRED
		if (retiredDemand && retireExactDemand(demand) != 1) return null
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
		check(retireDemandAuthority(demand, "AMBIENT_RADIO_COMPENSATED", wallTimeMs)) {
			"Unable to retire exact Ambient radio caller authority"
		}
		rotateCurrentAuthorizationInTransaction(
			source.stableCode,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
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
			return if (current == Long.MAX_VALUE) null else current + 1L
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
			} else null
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

		if (!requested) return revoke(AmbientRadioDemandInactiveReason.REQUEST_DISABLED)
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
		val approval = retentionSnapshot?.grants?.get(source)
			?: return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING)
		if (approval.sourcePolicyRevision != leaseIdentity.policyRevision ||
			approval.ambientConsentEpoch != leaseIdentity.consentEpoch ||
			approval.collectedDataEpoch != leaseIdentity.collectedDataEpoch ||
			approval.retainedFromMs != requireNotNull(evidence).retainedFromMs ||
			approval.retainedFromMs != leaseIdentity.retainedFromMs ||
			approval.opaquePolicyId != leaseIdentity.retentionPolicyId ||
			approval.approvalRevision != leaseIdentity.retentionApprovalRevision ||
			!isLiveAmbientRetentionCurrentInTransaction(
				approval,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		) return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISMATCH)
		if (!rollout.isCaptureReachable(
				source,
				CaptureReachabilityMode.AMBIENT,
			)
		) {
			return revoke(AmbientRadioDemandInactiveReason.ROLLOUT_CONTAINED)
		}
		val deletionGeneration = currentDeletionGeneration(leaseIdentity.collectedDataEpoch)
			?: return revoke(AmbientRadioDemandInactiveReason.DELETION_AUTHORITY_MISMATCH)
		val contract = SourceDemandContractFactory.forQos(
			source,
			qosCode = 0,
			purpose = DirectSourceDemandPurpose.AMBIENT_PRODUCT,
		)
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
				demand.sourceCallerAuthorityReference == sourceCallerAuthorityReference
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
			sourceCallerAuthorityReference = sourceCallerAuthorityReference,
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
		if (authorization.isDenied) return@withTransaction null
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
	): LiveAmbientRetentionGrant? {
		if (source !in setOf(SourceKind.STEPS, SourceKind.WIFI, SourceKind.CELL)) return null
		if (sourcePolicyRevision <= 0L || ambientConsentEpoch <= 0L || collectedDataEpoch < 0L) {
			return null
		}
		val approved = try {
			retentionAuthorityReader.currentLiveAmbient(
				source = source.toTrackingSourceComponent(),
				expectedSourcePolicyRevision = sourcePolicyRevision,
				expectedAmbientConsentEpoch = ambientConsentEpoch,
				expectedCollectedDataEpoch = collectedDataEpoch,
			) as? CurrentRetentionAuthority.Approved
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			null
		} ?: return null
		if (approved.collectedDataEpoch != collectedDataEpoch) return null
		val exactCurrent = try {
			retentionAuthorityReader.isCurrentLiveAmbientAt(
				source = source.toTrackingSourceComponent(),
				expectedSourcePolicyRevision = sourcePolicyRevision,
				expectedAmbientConsentEpoch = ambientConsentEpoch,
				expectedCollectedDataEpoch = collectedDataEpoch,
				expectedOpaquePolicyId = approved.opaquePolicyId,
				expectedApprovalRevision = approved.approvalRevision,
				currentBootId = currentBootId,
				currentElapsedRealtimeNanos = currentElapsedRealtimeNanos,
				currentWallTimeMs = currentWallTimeMs,
			)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			false
		}
		if (!exactCurrent) return null
		return LiveAmbientRetentionGrant(
			source = source,
			sourcePolicyRevision = sourcePolicyRevision,
			ambientConsentEpoch = ambientConsentEpoch,
			collectedDataEpoch = collectedDataEpoch,
			retainedFromMs = approved.retainedFromMs,
			opaquePolicyId = approved.opaquePolicyId,
			approvalRevision = approved.approvalRevision,
			effectiveBootId = approved.effectiveBootId,
			effectiveElapsedRealtimeNanos = approved.effectiveElapsedRealtimeNanos,
			effectiveWallTimeMs = approved.effectiveWallTimeMs,
		)
	}

	private suspend fun isLiveAmbientRetentionCurrentInTransaction(
		grant: LiveAmbientRetentionGrant,
		currentBootId: String,
		currentElapsedRealtimeNanos: Long,
		currentWallTimeMs: Long,
	): Boolean {
		if (!grant.isEffectiveAt(
				currentBootId,
				currentElapsedRealtimeNanos,
				currentWallTimeMs,
			)
		) return false
		val evidence = database.sourceEvidenceStateDao().get()
		if (evidence?.collectedDataEpoch != grant.collectedDataEpoch ||
			evidence.retainedFromMs != grant.retainedFromMs
		) {
			return false
		}
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()?.takeIf { current ->
			current.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				current.currentPolicyRevision == grant.sourcePolicyRevision
		} ?: return false
		val policy = policyDao.policyAtRevision(
			authority.currentPolicyRevision,
			grant.source.stableCode,
		) ?: return false
		val consent = policyDao.latestConsentEpoch(
			grant.source.stableCode,
			SourceBrokerPurpose.AMBIENT_PRODUCT,
		) ?: return false
		return policy.ambientPersistenceEligible &&
			policy.ambientConsentEpoch == grant.ambientConsentEpoch &&
			policy.effectiveBootId == currentBootId &&
			policy.effectiveElapsedRealtimeNanos <= currentElapsedRealtimeNanos &&
			policy.effectiveWallTimeMs <= currentWallTimeMs &&
			consent.epoch == grant.ambientConsentEpoch &&
			consent.policyRevision == grant.sourcePolicyRevision &&
			consent.eligible &&
			consent.persistenceEligible &&
			consent.effectiveBootId == currentBootId &&
			consent.effectiveElapsedRealtimeNanos <= currentElapsedRealtimeNanos &&
			consent.effectiveWallTimeMs <= currentWallTimeMs &&
			readPersistedLiveAmbientRetention(grant.source) == grant
	}

	private suspend fun readPersistedLiveAmbientRetention(
		source: SourceKind,
	): LiveAmbientRetentionGrant? = try {
		when (source) {
			SourceKind.STEPS -> database.ambientStepsFactRevisionDao()
				.latestRetentionAuthority(AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
				?.takeIf(AmbientStepsRetentionAuthorityIntegrity::isAuthentic)
				?.takeIf { authority -> authority.isActive }
				?.let { authority ->
					LiveAmbientRetentionGrant(
						source,
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
			SourceKind.WIFI -> database.ambientWifiFactDao()
				.latestRetentionAuthority(AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
				?.takeIf(AmbientWifiRetentionAuthorityIntegrity::isAuthentic)
				?.takeIf { authority -> authority.isActive }
				?.let { authority ->
					LiveAmbientRetentionGrant(
						source,
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
			SourceKind.CELL -> database.ambientCellFactDao()
				.latestRetentionAuthority(AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT)
				?.takeIf(AmbientCellRetentionAuthorityIntegrity::isAuthentic)
				?.takeIf { authority -> authority.isActive }
				?.let { authority ->
					LiveAmbientRetentionGrant(
						source,
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
			else -> null
		}
	} catch (cancelled: kotlinx.coroutines.CancellationException) {
		throw cancelled
	} catch (_: RuntimeException) {
		null
	}

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
		if (discriminator != null) components += discriminator
		val value = components.joinToString("\u001f")
		return MessageDigest.getInstance("SHA-256")
			.digest(value.toByteArray(Charsets.UTF_8))
			.joinToString("") { byte -> "%02x".format(byte) }
	}

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

	fun parseLeaseBinding(demandId: String): AmbientStepsLeaseBinding? = try {
		val parts = demandId.split('.')
		if (parts.size != 4) return null
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
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
		require(approvalRevision > 0L)
	}
}

internal data class LiveAmbientRetentionSnapshot(
	val grants: Map<SourceKind, LiveAmbientRetentionGrant>,
) {
	init {
		require(grants.all { (source, grant) -> source == grant.source })
	}
}

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
		require(source in setOf(SourceKind.STEPS, SourceKind.WIFI, SourceKind.CELL))
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
		effectiveWallTimeMs <= wallTimeMs
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

private data class AmbientRadioExecutionAuthority(
	val authorityRevision: Long,
	val executionGeneration: Long,
	val ownerCasToken: String,
	val reconciliationAttempt: Long,
)

internal sealed interface AmbientRadioRetirementPlan {
	data object AlreadyRetired : AmbientRadioRetirementPlan
	data class Required(
		val lease: AmbientReconciliationLease,
		val previousReconciliationAttempt: Long,
	) : AmbientRadioRetirementPlan
	data object Unverifiable : AmbientRadioRetirementPlan
}

private data class AmbientRadioRetirementAuthority(
	val active: Boolean,
	val policyRevision: Long,
	val consentEpoch: Long,
	val collectedDataEpoch: Long,
	val rolloutRevision: Long,
	val executionRevision: Long,
	val ownerCasToken: String,
	val retentionPolicyId: String,
	val retentionApprovalRevision: Long,
	val reconciliationAttempt: Long,
	val demandId: String?,
)

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
	if (!AmbientWifiRetentionAuthorityIntegrity.isAuthentic(this)) return null
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
	if (!AmbientCellRetentionAuthorityIntegrity.isAuthentic(this)) return null
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
