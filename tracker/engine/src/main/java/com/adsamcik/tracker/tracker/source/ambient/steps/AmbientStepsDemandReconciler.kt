package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandInactiveReason
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsRetirementPlan
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.SourceBroker
import javax.inject.Inject
import javax.inject.Singleton

data class AmbientStepsDemandBoundary(
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
) {
	init {
		require(bootId.isNotBlank())
		require(elapsedRealtimeNanos >= 0L)
		require(wallTimeMs >= 0L)
	}
}

/**
 * Resolves current platform authority and reconciles only the durable Ambient Steps demand.
 * Provider subscription/acceptance and record import remain later explicit lifecycle steps.
 */
@Singleton
class AmbientStepsDemandReconciler internal constructor(
	private val resolveCapability: suspend () -> AmbientStepsCapability,
	private val sourceBroker: SourceBroker,
	@Suppress("unused")
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val trackingRolloutStateStore: TrackingRolloutStateStore,
	private val currentRetentionAuthority: suspend (Long, Long) -> CurrentRetentionAuthority,
) {
	@Inject
	constructor(
		capabilityResolver: AndroidAmbientStepsCapabilityResolver,
		sourceBroker: SourceBroker,
		bootClockDomainProvider: BootClockDomainProvider,
		sourcePolicyRepository: SourcePolicyRepository,
		trackingRolloutStateStore: TrackingRolloutStateStore,
		retentionAuthorityProducer: RetentionAuthorityProducer,
		collectedDataLifecycleStore: CollectedDataLifecycleStore,
	) : this(
		capabilityResolver::resolve,
		sourceBroker,
		bootClockDomainProvider,
		sourcePolicyRepository,
		trackingRolloutStateStore,
		{ policyRevision, consentEpoch ->
			val lifecycle = collectedDataLifecycleStore.snapshot()
			retentionAuthorityProducer.currentLiveAmbient(
				source = TrackingSourceComponent.STEPS,
				expectedSourcePolicyRevision = policyRevision,
				expectedAmbientConsentEpoch = consentEpoch,
				expectedCollectedDataEpoch = lifecycle.epoch,
				expectedRetainedFromMs = lifecycle.retainedFromMs,
			)
		},
	)

	internal suspend fun reconcileAt(
		boundary: AmbientStepsDemandBoundary,
		lease: AmbientReconciliationLease,
	): AmbientStepsDemandReconciliation {
		require(lease.identity.source == AmbientTrackingSource.STEPS)
		val policyAuthority = ambientPolicyAuthority(boundary, lease)
		if (policyAuthority is AmbientStepsPolicyAuthority.Blocked) {
			val retired = retireDemand(boundary, lease)
			return AmbientStepsDemandReconciliation.PolicyBlocked(
				provider = null,
				reason = policyAuthority.reason,
				retirementComplete = retired,
			)
		}
		return when (val capability = resolveCapability()) {
			is AmbientStepsCapability.ReadyForRegistration -> {
				when (val demand = sourceBroker.replaceAmbientStepsDemand(
					consumerId = CONSUMER_ID,
					mechanism = capability.provider.toAcquisitionMechanism(),
					leaseIdentity = lease.identity,
					bootId = boundary.bootId,
					elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
					wallTimeMs = boundary.wallTimeMs,
				)) {
					is AmbientStepsDemandResult.Active -> AmbientStepsDemandReconciliation.DemandReady(
						provider = capability.provider,
						importAccess = capability.importAccess,
						optionalPermissions = capability.optionalPermissions,
						demandId = demand.demand.demandId,
					)
					is AmbientStepsDemandResult.Inactive -> AmbientStepsDemandReconciliation.PolicyBlocked(
						provider = capability.provider,
						reason = demand.reason.toPublicReason(),
					)
				}
			}
			is AmbientStepsCapability.PermissionRequired -> {
				val retired = retireDemand(boundary, lease)
				AmbientStepsDemandReconciliation.PermissionRequired(
					provider = capability.provider,
					requiredPermissions = capability.requiredPermissions,
					optionalPermissions = capability.optionalPermissions,
					retirementComplete = retired,
				)
			}
			is AmbientStepsCapability.Unavailable -> {
				val retired = retireDemand(boundary, lease)
				AmbientStepsDemandReconciliation.Unavailable(
					healthConnect = capability.healthConnect,
					localRecording = capability.localRecording,
					retirementComplete = retired,
				)
			}
		}
	}

	internal suspend fun retireAfterRetentionAuthorityFailureAt(
		boundary: AmbientStepsDemandBoundary,
		lease: AmbientReconciliationLease?,
	): AmbientStepsDemandReconciliation.PolicyBlocked {
		val exactLease = lease ?: when (
			val plan = sourceBroker.ambientStepsRetirementPlan(CONSUMER_ID)
		) {
			AmbientStepsRetirementPlan.AlreadyRetired -> null
			is AmbientStepsRetirementPlan.Required -> plan.lease
			AmbientStepsRetirementPlan.Unverifiable ->
				return AmbientStepsDemandReconciliation.PolicyBlocked(
					provider = null,
					reason = AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
					retirementComplete = false,
				)
		}
		exactLease?.let { require(it.identity.source == AmbientTrackingSource.STEPS) }
		val retired = exactLease?.let { retireDemand(boundary, it) } ?: true
		return AmbientStepsDemandReconciliation.PolicyBlocked(
			provider = null,
			reason = AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
			retirementComplete = retired,
		)
	}

	/** Avoids platform permission/provider probes while Ambient Steps is not product-eligible. */
	private suspend fun ambientPolicyAuthority(
		boundary: AmbientStepsDemandBoundary,
		lease: AmbientReconciliationLease,
	): AmbientStepsPolicyAuthority =
		when (val authority = sourcePolicyRepository.currentState()) {
			SourcePolicyAuthorityState.Uninitialized,
			is SourcePolicyAuthorityState.Invalid -> AmbientStepsPolicyAuthority.Blocked(
				AmbientStepsDemandBlockReason.AUTHORITY_INACTIVE,
			)

			is SourcePolicyAuthorityState.Active -> {
				val policy = authority.snapshot[TrackingSourceComponent.STEPS]
				val rollout = trackingRolloutStateStore.load()
				when {
					authority.snapshot.revision != lease.identity.policyRevision ->
						AmbientStepsPolicyAuthority.Blocked(
							AmbientStepsDemandBlockReason.AUTHORITY_INACTIVE,
						)
					policy.ambientConsentEpoch == null ->
						AmbientStepsPolicyAuthority.Blocked(
							AmbientStepsDemandBlockReason.REQUEST_DISABLED,
						)
					policy.ambientConsentEpoch != lease.identity.consentEpoch ->
						AmbientStepsPolicyAuthority.Blocked(
							AmbientStepsDemandBlockReason.CONSENT_REVOKED,
						)
					!policy.ambientPersistenceEligible ->
						AmbientStepsPolicyAuthority.Blocked(
							AmbientStepsDemandBlockReason.PERSISTENCE_INELIGIBLE,
						)
					rollout.revision != lease.identity.rolloutRevision ||
						!rollout.isCaptureReachable(
						SourceKind.STEPS,
						CaptureReachabilityMode.AMBIENT,
					) -> AmbientStepsPolicyAuthority.Blocked(
						AmbientStepsDemandBlockReason.ROLLOUT_CONTAINED,
					)
					else -> when (val retention = currentRetentionAuthority(
						authority.snapshot.revision,
						requireNotNull(policy.ambientConsentEpoch),
					)) {
						is CurrentRetentionAuthority.Approved ->
							if (
								retention.collectedDataEpoch ==
									lease.identity.collectedDataEpoch &&
								retention.retainedFromMs == lease.identity.retainedFromMs &&
								retention.effectiveBootId == boundary.bootId &&
								retention.effectiveElapsedRealtimeNanos <=
									boundary.elapsedRealtimeNanos &&
								retention.effectiveWallTimeMs <= boundary.wallTimeMs
							) {
								AmbientStepsPolicyAuthority.Ready
							} else {
								AmbientStepsPolicyAuthority.Blocked(
									AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
								)
							}
						is CurrentRetentionAuthority.Unavailable ->
							AmbientStepsPolicyAuthority.Blocked(
								AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
							)
					}
				}
			}
		}

	private suspend fun retireDemand(
		boundary: AmbientStepsDemandBoundary,
		lease: AmbientReconciliationLease,
	): Boolean = sourceBroker.retireExactAmbientStepsDemand(
			consumerId = CONSUMER_ID,
			leaseIdentity = lease.identity,
			bootId = boundary.bootId,
			elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
			wallTimeMs = boundary.wallTimeMs,
		)

	companion object {
		const val CONSUMER_ID = "app:ambient:steps"
	}
}

sealed interface AmbientStepsDemandReconciliation {
	data class DemandReady(
		val provider: AmbientStepsProvider,
		val importAccess: AmbientStepsImportAccess,
		val optionalPermissions: Set<AmbientStepsPermission>,
		val demandId: String,
	) : AmbientStepsDemandReconciliation

	data class PermissionRequired(
		val provider: AmbientStepsProvider,
		val requiredPermissions: Set<AmbientStepsPermission>,
		val optionalPermissions: Set<AmbientStepsPermission>,
		val retirementComplete: Boolean = true,
	) : AmbientStepsDemandReconciliation

	data class PolicyBlocked(
		/** Null when policy or rollout rejects collection before any provider is inspected. */
		val provider: AmbientStepsProvider?,
		val reason: AmbientStepsDemandBlockReason,
		val retirementComplete: Boolean = true,
	) : AmbientStepsDemandReconciliation

	data class Unavailable(
		val healthConnect: HealthConnectAmbientStepsAvailability,
		val localRecording: LocalRecordingAmbientStepsAvailability,
		val retirementComplete: Boolean = true,
	) : AmbientStepsDemandReconciliation
}

enum class AmbientStepsDemandBlockReason {
	REQUEST_DISABLED,
	STALE_RECONCILIATION_LEASE,
	AUTHORITY_INACTIVE,
	POLICY_MISSING,
	CONSENT_REVOKED,
	PERSISTENCE_INELIGIBLE,
	RETENTION_POLICY_UNAVAILABLE,
	ROLLOUT_CONTAINED,
}

private sealed interface AmbientStepsPolicyAuthority {
	data object Ready : AmbientStepsPolicyAuthority

	data class Blocked(val reason: AmbientStepsDemandBlockReason) :
		AmbientStepsPolicyAuthority
}

private fun AmbientStepsDemandInactiveReason.toPublicReason(): AmbientStepsDemandBlockReason = when (this) {
	AmbientStepsDemandInactiveReason.REQUEST_DISABLED -> AmbientStepsDemandBlockReason.REQUEST_DISABLED
	AmbientStepsDemandInactiveReason.STALE_RECONCILIATION_LEASE ->
		AmbientStepsDemandBlockReason.STALE_RECONCILIATION_LEASE
	AmbientStepsDemandInactiveReason.AUTHORITY_INACTIVE -> AmbientStepsDemandBlockReason.AUTHORITY_INACTIVE
	AmbientStepsDemandInactiveReason.POLICY_MISSING -> AmbientStepsDemandBlockReason.POLICY_MISSING
	AmbientStepsDemandInactiveReason.CONSENT_REVOKED -> AmbientStepsDemandBlockReason.CONSENT_REVOKED
	AmbientStepsDemandInactiveReason.PERSISTENCE_INELIGIBLE ->
		AmbientStepsDemandBlockReason.PERSISTENCE_INELIGIBLE
	AmbientStepsDemandInactiveReason.RETENTION_APPROVAL_MISSING,
	AmbientStepsDemandInactiveReason.RETENTION_APPROVAL_MISMATCH,
	-> AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE
	AmbientStepsDemandInactiveReason.ROLLOUT_CONTAINED -> AmbientStepsDemandBlockReason.ROLLOUT_CONTAINED
}

private fun AmbientStepsProvider.toAcquisitionMechanism(): AmbientStepsAcquisitionMechanism = when (this) {
	AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
		AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
	AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
		AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS
}
