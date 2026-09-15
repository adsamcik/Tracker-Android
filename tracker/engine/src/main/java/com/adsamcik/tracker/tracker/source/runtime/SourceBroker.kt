package com.adsamcik.tracker.tracker.source.runtime

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.reconcileActivityAutomationEpochInTransaction
import com.adsamcik.tracker.shared.base.database.rotateCurrentSourceAuthorizationInTransaction
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientCellAuthorityIntegrity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.AmbientWifiAuthorityIntegrity
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
) {
	constructor(database: AppDatabase) : this(database, RoomTrackingRolloutStateStore(database))
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
				demand.requestedDeliveryLatencyMs == contract.requestedDeliveryLatencyMs
		}?.let { unchanged -> return@withTransaction AmbientStepsDemandResult.Active(unchanged) }
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
		val demand = SourceDemandEntity(
			demandId = demandId(
				consumerId,
				source.stableCode,
				SourceBrokerPurpose.AMBIENT_PRODUCT,
				authority.currentPolicyRevision,
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
		retentionApproval: AmbientRadioRetentionApproval?,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): AmbientRadioDemandResult = database.withTransaction {
		replaceAmbientRadioDemandInTransaction(
			consumerId = consumerId,
			source = SourceKind.WIFI,
			requested = requested,
			retentionApproval = retentionApproval,
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
			latestAuthority = { database.ambientWifiFactDao().latestAuthority()?.toRadioAuthority() },
			maximumAuthorityRevision = { database.ambientWifiFactDao().maximumAuthorityRevision() },
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
					),
				)
			},
			currentDeletionGeneration = { epoch ->
				database.ambientWifiFactDao().latestDeletionMarker(epoch)?.deletionGeneration ?: 0L
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
		retentionApproval: AmbientRadioRetentionApproval?,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): AmbientRadioDemandResult = database.withTransaction {
		replaceAmbientRadioDemandInTransaction(
			consumerId = consumerId,
			source = SourceKind.CELL,
			requested = requested,
			retentionApproval = retentionApproval,
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
			latestAuthority = { database.ambientCellFactDao().latestAuthority()?.toRadioAuthority() },
			maximumAuthorityRevision = { database.ambientCellFactDao().maximumAuthorityRevision() },
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
					),
				)
			},
			currentDeletionGeneration = { epoch ->
				database.ambientCellFactDao().latestDeletionMarker(epoch)?.deletionGeneration ?: 0L
			},
		)
	}

	@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod", "ReturnCount")
	private suspend fun replaceAmbientRadioDemandInTransaction(
		consumerId: String,
		source: SourceKind,
		requested: Boolean,
		retentionApproval: AmbientRadioRetentionApproval?,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		latestAuthority: suspend () -> AmbientRadioAuthority?,
		maximumAuthorityRevision: suspend () -> Long,
		insertAuthority: suspend (AmbientRadioAuthority) -> Unit,
		currentDeletionGeneration: suspend (Long) -> Long,
	): AmbientRadioDemandResult {
		require(source == SourceKind.WIFI || source == SourceKind.CELL)
		require(consumerId.isNotBlank())
		require(bootId.isNotBlank())
		require(elapsedRealtimeNanos >= 0L && wallTimeMs >= 0L)
		val dao = database.sourceBrokerDao()
		val currentDemands = dao.currentDemands(consumerId)
		val affectedSourceKinds = (
			currentDemands.map(SourceDemandEntity::sourceKind) + source.stableCode
		).toSet()
		val priorAuthority = latestAuthority()

		suspend fun nextAuthorityRevision(): Long? {
			val current = maximumAuthorityRevision()
			return if (current == Long.MAX_VALUE) null else current + 1L
		}

		suspend fun revoke(reason: AmbientRadioDemandInactiveReason): AmbientRadioDemandResult {
			dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
			var effectiveReason = reason
			if (priorAuthority?.state == AmbientWifiAuthorityEntity.STATE_ACTIVE) {
				val revision = nextAuthorityRevision()
				if (revision == null) {
					effectiveReason = AmbientRadioDemandInactiveReason.AUTHORITY_REVISION_EXHAUSTED
				} else {
					insertAuthority(
						priorAuthority.copy(
							authorityRevision = revision,
							state = AmbientWifiAuthorityEntity.STATE_REVOKED,
							effectiveBootId = bootId,
							effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
							effectiveWallTimeMs = wallTimeMs,
						),
					)
				}
			}
			rotateCurrentAuthorizationsInTransaction(
				affectedSourceKinds,
				bootId,
				elapsedRealtimeNanos,
				wallTimeMs,
			)
			return AmbientRadioDemandResult.Inactive(effectiveReason)
		}

		if (!requested) return revoke(AmbientRadioDemandInactiveReason.REQUEST_DISABLED)
		val approval = retentionApproval
			?: return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISSING)
		if (approval.source != source) {
			return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISMATCH)
		}
		val policyAuthority = database.sourcePolicyDao().authority()
		if (policyAuthority?.bootstrapState != SourcePolicyAuthorityEntity.STATE_ACTIVE) {
			return revoke(AmbientRadioDemandInactiveReason.AUTHORITY_INACTIVE)
		}
		val policy = database.sourcePolicyDao().policyAtRevision(
			policyAuthority.currentPolicyRevision,
			source.stableCode,
		) ?: return revoke(AmbientRadioDemandInactiveReason.POLICY_MISSING)
		val consentEpoch = policy.ambientConsentEpoch
			?: return revoke(AmbientRadioDemandInactiveReason.CONSENT_REVOKED)
		if (!policy.ambientPersistenceEligible) {
			return revoke(AmbientRadioDemandInactiveReason.PERSISTENCE_INELIGIBLE)
		}
		if (approval.sourcePolicyRevision != policy.policyRevision ||
			approval.ambientConsentEpoch != consentEpoch
		) {
			return revoke(AmbientRadioDemandInactiveReason.RETENTION_APPROVAL_MISMATCH)
		}
		if (!trackingRolloutStateStore.load().isCaptureReachable(
				source,
				CaptureReachabilityMode.AMBIENT,
			)
		) {
			return revoke(AmbientRadioDemandInactiveReason.ROLLOUT_CONTAINED)
		}
		val evidence = database.sourceEvidenceStateDao().get()
			?: return revoke(AmbientRadioDemandInactiveReason.SOURCE_EVIDENCE_STATE_MISSING)
		val deletionGeneration = currentDeletionGeneration(evidence.collectedDataEpoch)
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
				authority.collectedDataEpoch == evidence.collectedDataEpoch &&
				authority.scopeDeletionGeneration == deletionGeneration
		}
		if (unchangedDemand != null && unchangedAuthority != null) {
			return AmbientRadioDemandResult.Active(
				unchangedDemand,
				unchangedAuthority.authorityRevision,
			)
		}
		val authorityRevision = nextAuthorityRevision()
			?: return revoke(AmbientRadioDemandInactiveReason.AUTHORITY_REVISION_EXHAUSTED)
		dao.retireConsumer(consumerId, bootId, elapsedRealtimeNanos, wallTimeMs)
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
				collectedDataEpoch = evidence.collectedDataEpoch,
				scopeDeletionGeneration = deletionGeneration,
				effectiveBootId = bootId,
				effectiveElapsedRealtimeNanos = elapsedRealtimeNanos,
				effectiveWallTimeMs = wallTimeMs,
			),
		)
		rotateCurrentAuthorizationsInTransaction(
			affectedSourceKinds,
			bootId,
			elapsedRealtimeNanos,
			wallTimeMs,
		)
		return AmbientRadioDemandResult.Active(demand, authorityRevision)
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
	ROLLOUT_CONTAINED,
}

internal data class AmbientRadioRetentionApproval(
	val source: SourceKind,
	val sourcePolicyRevision: Long,
	val ambientConsentEpoch: Long,
	val opaquePolicyId: String,
	val approvalRevision: Long,
) {
	init {
		require(source == SourceKind.WIFI || source == SourceKind.CELL)
		require(sourcePolicyRevision > 0L && ambientConsentEpoch >= 0L)
		require(opaquePolicyId.isNotBlank() && opaquePolicyId.length <= 256)
		require(approvalRevision > 0L)
	}
}

internal sealed interface AmbientRadioDemandResult {
	data class Active(
		val demand: SourceDemandEntity,
		val authorityRevision: Long,
	) : AmbientRadioDemandResult

	data class Inactive(val reason: AmbientRadioDemandInactiveReason) : AmbientRadioDemandResult
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
)

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
