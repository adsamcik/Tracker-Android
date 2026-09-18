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
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.RoomTrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.coordinator.SessionManifestPurpose
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.DirectSourceDemandPurpose
import com.adsamcik.tracker.tracker.source.model.SourceDemandContract
import com.adsamcik.tracker.tracker.source.model.SourceDemandContractFactory
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import java.security.MessageDigest
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
class SourceBroker @Inject constructor(
	private val database: AppDatabase,
	private val trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	private val ambientRadioMutationLeaseGuard: AmbientRadioMutationLeaseGuard,
) {
	constructor(database: AppDatabase) : this(
		database,
		RoomTrackingRolloutStateStore(database),
		RejectingAmbientRadioMutationLeaseGuard,
	)

	constructor(
		database: AppDatabase,
		trackingRolloutStateStore: RoomTrackingRolloutStateStore,
	) : this(
		database,
		trackingRolloutStateStore,
		RejectingAmbientRadioMutationLeaseGuard,
	)
	fun sessionConsumerId(logicalTrackingId: String): String = "session:$logicalTrackingId"

	/** Current durable authority vector used to reconcile the one physical source owner. */
	internal suspend fun authorizationDemands(source: SourceKind): List<SourceDemandEntity> =
		database.sourceBrokerDao().authorizationDemands(source.stableCode)

	fun buildSessionDemands(
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevision: Long,
		lifecycleLeaseGeneration: Long,
		policyRevision: Long,
		bindings: Collection<SessionManifestSourceEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
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
			)
		}
	}

	/** Must be called from the manifest/intent transaction. */
	suspend fun replaceSessionDemandsInTransaction(
		logicalTrackingId: String,
		demands: List<SourceDemandEntity>,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	) {
		val consumerId = sessionConsumerId(logicalTrackingId)
		val dao = database.sourceBrokerDao()
		val affectedSources = (dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind) +
			demands.map(SourceDemandEntity::sourceKind)).toSet()
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
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
	suspend fun stageSessionDemandsInTransaction(
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
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
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
	suspend fun activatePreparedSessionDemandsInTransaction(
		logicalTrackingId: String,
		serviceRunId: String,
		manifestRevision: Long,
		leaseGeneration: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
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
				)
		}) return false
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
	) = database.withTransaction {
		retireSessionDemandsInTransaction(
			logicalTrackingId,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
	}

	/** Retires session authority atomically with a caller-owned lifecycle transaction. */
	suspend fun retireSessionDemandsInTransaction(
		logicalTrackingId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): Int {
		val dao = database.sourceBrokerDao()
		val consumerId = sessionConsumerId(logicalTrackingId)
		val affectedSources = dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind).toSet()
		val updated = dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		affectedSources.forEach { sourceKind ->
			rotateCurrentAuthorizationInTransaction(sourceKind, bootId, elapsedRealtimeNanos, wallTimeMs)
		}
		return updated
	}

	/**
	 * Replaces one application-scoped control demand before its provider is reconciled.
	 * Fails closed if the authoritative policy has no current CONTROL epoch.
	 */
	suspend fun replaceAutomaticControlDemand(
		consumerId: String,
		source: SourceKind,
		enabled: Boolean,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		maximumAgeMs: Long,
		desiredLatencyMs: Long,
	): SourceDemandEntity? = database.withTransaction {
		val dao = database.sourceBrokerDao()
		val affectedSourceKinds = (
			dao.currentDemands(consumerId).map(SourceDemandEntity::sourceKind) + source.stableCode
		).toSet()
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
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
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): AmbientStepsDemandResult = database.withTransaction {
		require(consumerId.isNotBlank())
		val source = SourceKind.STEPS
		val dao = database.sourceBrokerDao()
		val currentDemands = dao.currentDemands(consumerId)
		val affectedSourceKinds = (
			currentDemands.map(SourceDemandEntity::sourceKind) + source.stableCode
		).toSet()

		suspend fun inactive(reason: AmbientStepsDemandInactiveReason): AmbientStepsDemandResult {
			dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return AmbientStepsDemandResult.Inactive(reason)
		}

		if (mechanism == null) {
			return@withTransaction inactive(AmbientStepsDemandInactiveReason.REQUEST_DISABLED)
		}
		val policyDao = database.sourcePolicyDao()
		val authority = policyDao.authority()
		if (authority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			return@withTransaction inactive(AmbientStepsDemandInactiveReason.AUTHORITY_INACTIVE)
		}
		val policy = policyDao.policyAtRevision(authority.currentPolicyRevision, source.stableCode)
			?: return@withTransaction inactive(AmbientStepsDemandInactiveReason.POLICY_MISSING)
		val consentEpoch = policy.ambientConsentEpoch
			?: return@withTransaction inactive(AmbientStepsDemandInactiveReason.CONSENT_REVOKED)
		if (!policy.ambientPersistenceEligible) {
			return@withTransaction inactive(AmbientStepsDemandInactiveReason.PERSISTENCE_INELIGIBLE)
		}
		if (!trackingRolloutStateStore.load().isCaptureReachable(source, CaptureReachabilityMode.AMBIENT)) {
			return@withTransaction inactive(AmbientStepsDemandInactiveReason.ROLLOUT_CONTAINED)
		}
		val evidence = database.sourceEvidenceStateDao().get()
			?: return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.RETENTION_APPROVAL_MISSING,
			)
		val retention = database.ambientStepsFactRevisionDao().latestRetentionAuthority(
			AmbientStepsRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
		) ?: return@withTransaction inactive(
			AmbientStepsDemandInactiveReason.RETENTION_APPROVAL_MISSING,
		)
		if (!AmbientStepsRetentionAuthorityIntegrity.isAuthentic(retention) ||
			!retention.isActive ||
			retention.sourcePolicyRevision != authority.currentPolicyRevision ||
			retention.ambientConsentEpoch != consentEpoch ||
			retention.collectedDataEpoch != evidence.collectedDataEpoch ||
			retention.retainedFromMs != evidence.retainedFromMs ||
			retention.effectiveBootId != bootId ||
			retention.effectiveElapsedRealtimeNanos > elapsedRealtimeNanos ||
			retention.effectiveWallTimeMs > wallTimeMs
		) {
			return@withTransaction inactive(
				AmbientStepsDemandInactiveReason.RETENTION_APPROVAL_MISMATCH,
			)
		}

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
				demand.hasExactAmbientStepsRetentionBinding(retention)
		}?.let { unchanged -> return@withTransaction AmbientStepsDemandResult.Active(unchanged) }
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		val demand = SourceDemandEntity(
			demandId = AmbientStepsDemandIdentity.create(
				consumerId = consumerId,
				sourcePolicyRevision = authority.currentPolicyRevision,
				consentEpoch = consentEpoch,
				requestedBootId = bootId,
				requestedElapsedRealtimeNanos = elapsedRealtimeNanos,
				minimumAcquisitionSpec = contract.encodeFloor(),
				retention = retention,
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
	): AmbientRadioDemandResult =
		withAmbientRadioMutationLease(leaseIdentity) {
			replaceAmbientWifiDemandUnderHeldLease(
				consumerId,
				requested,
				leaseIdentity,
				reconciliationAttempt,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}.toDemandResult()

	internal suspend fun replaceAmbientWifiDemandUnderHeldLease(
		consumerId: String,
		requested: Boolean,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
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
					latestRetentionAuthority = {
						database.ambientWifiFactDao().latestRetentionAuthority(
							AmbientWifiRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
						)?.toRadioRetentionAuthority()
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
	): AmbientRadioDemandResult =
		withAmbientRadioMutationLease(leaseIdentity) {
			replaceAmbientCellDemandUnderHeldLease(
				consumerId,
				requested,
				leaseIdentity,
				reconciliationAttempt,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
		}.toDemandResult()

	internal suspend fun replaceAmbientCellDemandUnderHeldLease(
		consumerId: String,
		requested: Boolean,
		leaseIdentity: AmbientReconciliationIdentity,
		reconciliationAttempt: Long,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
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
					latestRetentionAuthority = {
						database.ambientCellFactDao().latestRetentionAuthority(
							AmbientCellRetentionAuthorityEntity.SCOPE_LIVE_AMBIENT,
						)?.toRadioRetentionAuthority()
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
				)
	}

	internal suspend fun <T> withAmbientRadioMutationLease(
		identity: AmbientReconciliationIdentity,
		mutation: suspend () -> T,
	): AmbientRadioLeaseMutation<T> =
		ambientRadioMutationLeaseGuard.mutateIfCurrent(identity, mutation)

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
			rolloutRevision = rollout.revision,
			executionGeneration = sourceAuthority?.executionGeneration,
			authorityRevision = sourceAuthority?.authorityRevision,
			ownerCasToken = sourceAuthority?.ownerCasToken,
			reconciliationAttempt = sourceAuthority?.reconciliationAttempt,
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
		if (trackingRolloutStateStore.load().revision != leaseIdentity.rolloutRevision) return null
		val demand = database.sourceBrokerDao().currentDemands(consumerId).singleOrNull()
			?.takeIf {
				it.demandId == expectedDemandId &&
					it.sourceKind == source.stableCode &&
					it.purpose == SourceBrokerPurpose.AMBIENT_PRODUCT &&
					it.sourcePolicyRevision == leaseIdentity.policyRevision &&
					it.consentEpoch == leaseIdentity.consentEpoch
			} ?: return null
		val authority = latestAuthority()?.takeIf {
			it.state == AmbientWifiAuthorityEntity.STATE_ACTIVE &&
				it.sourcePolicyRevision == leaseIdentity.policyRevision &&
				it.ambientConsentEpoch == leaseIdentity.consentEpoch &&
				it.collectedDataEpoch == leaseIdentity.collectedDataEpoch &&
				it.rolloutRevision == leaseIdentity.rolloutRevision &&
				it.ownerCasToken == leaseIdentity.ownerCasToken &&
				it.reconciliationAttempt == reconciliationAttempt &&
				it.demandId == expectedDemandId
		} ?: return null
		val maximumRevision = maximumAuthorityRevision()
		if (maximumRevision == Long.MAX_VALUE) return null
		if (retireExactDemand(demand) != 1) return null
		val revokedAuthority = authority.copy(
			authorityRevision = maximumRevision + 1L,
			state = AmbientWifiAuthorityEntity.STATE_REVOKED,
			effectiveBootId = bootId,
			effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
			effectiveWallTimeMs = wallTimeMs,
		)
		insertAuthority(
			revokedAuthority,
		)
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
			rolloutRevision = leaseIdentity.rolloutRevision,
			executionGeneration = revokedAuthority.executionGeneration,
			authorityRevision = revokedAuthority.authorityRevision,
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
		latestRetentionAuthority: suspend () -> AmbientRadioRetentionAuthority?,
		retireExactDemand: suspend (SourceDemandEntity) -> Int,
		currentDeletionGeneration: suspend (Long) -> Long?,
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
		if (policy == null ||
			consent == null ||
			consent.epoch != leaseIdentity.consentEpoch ||
			consent.policyRevision != leaseIdentity.policyRevision ||
			evidence?.collectedDataEpoch != leaseIdentity.collectedDataEpoch ||
			rollout.revision != leaseIdentity.rolloutRevision
		) {
			return AmbientRadioDemandResult.Inactive(
				AmbientRadioDemandInactiveReason.STALE_RECONCILIATION_LEASE,
			)
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
			return retireExactDemand(demand) == 1
		}

		fun ownerReconciliationAuthority(
			executionAuthority: AmbientRadioAuthority?,
		): AmbientRadioReconciliationAuthority = AmbientRadioReconciliationAuthority(
			source = source,
			policyRevision = leaseIdentity.policyRevision,
			ambientConsentEpoch = leaseIdentity.consentEpoch,
			collectedDataEpoch = leaseIdentity.collectedDataEpoch,
			rolloutRevision = rollout.revision,
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
					rolloutRevision = rollout.revision,
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
		val consentEpoch = policy.ambientConsentEpoch
			?: return revoke(AmbientRadioDemandInactiveReason.CONSENT_REVOKED)
		if (consentEpoch != leaseIdentity.consentEpoch || !consent.eligible) {
			return revoke(AmbientRadioDemandInactiveReason.CONSENT_REVOKED)
		}
		if (!policy.ambientPersistenceEligible || !consent.persistenceEligible) {
			return revoke(AmbientRadioDemandInactiveReason.PERSISTENCE_INELIGIBLE)
		}
		val approval = latestRetentionAuthority()
			?: return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING)
		if (!approval.isActive ||
			approval.sourcePolicyRevision != leaseIdentity.policyRevision ||
			approval.ambientConsentEpoch != leaseIdentity.consentEpoch ||
			approval.collectedDataEpoch != leaseIdentity.collectedDataEpoch ||
			approval.retainedFromMs != requireNotNull(evidence).retainedFromMs
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
				demand.requestedDeliveryLatencyMs == null
		}
		val unchangedAuthority = priorAuthority?.takeIf { authority ->
			authority.state == AmbientWifiAuthorityEntity.STATE_ACTIVE &&
				authority.sourcePolicyRevision == policy.policyRevision &&
				authority.ambientConsentEpoch == consentEpoch &&
				authority.retentionPolicyId == approval.opaquePolicyId &&
				authority.retentionApprovalRevision == approval.approvalRevision &&
				authority.collectedDataEpoch == leaseIdentity.collectedDataEpoch &&
				authority.scopeDeletionGeneration == deletionGeneration
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
				contract.encodeFloor(),
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

internal enum class AmbientStepsDemandInactiveReason {
	REQUEST_DISABLED,
	AUTHORITY_INACTIVE,
	POLICY_MISSING,
	CONSENT_REVOKED,
	PERSISTENCE_INELIGIBLE,
	RETENTION_APPROVAL_MISSING,
	RETENTION_APPROVAL_MISMATCH,
	ROLLOUT_CONTAINED,
}

internal fun SourceDemandEntity.hasExactAmbientStepsRetentionBinding(
	retention: AmbientStepsRetentionAuthorityEntity,
): Boolean = AmbientStepsDemandIdentity.matches(this, retention)

private object AmbientStepsDemandIdentity {
	fun create(
		consumerId: String,
		sourcePolicyRevision: Long,
		consentEpoch: Long,
		requestedBootId: String,
		requestedElapsedRealtimeNanos: Long,
		minimumAcquisitionSpec: String,
		retention: AmbientStepsRetentionAuthorityEntity,
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
		retention.scope,
		retention.opaquePolicyId,
		retention.approvalRevision,
	)

	fun matches(
		demand: SourceDemandEntity,
		retention: AmbientStepsRetentionAuthorityEntity,
	): Boolean =
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
