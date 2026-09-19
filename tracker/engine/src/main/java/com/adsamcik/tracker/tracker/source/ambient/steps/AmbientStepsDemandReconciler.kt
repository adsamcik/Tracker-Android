package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.preferences.lifecycle.CollectedDataLifecycleStore
import com.adsamcik.tracker.shared.preferences.retention.CurrentRetentionAuthority
import com.adsamcik.tracker.shared.preferences.retention.RetentionAuthorityProducer
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyAuthorityState
import com.adsamcik.tracker.shared.preferences.tracking.SourcePolicyRepository
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.tracker.api.AmbientReconciliationLease
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandInactiveReason
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandResult
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsRetirementPlan
import com.adsamcik.tracker.tracker.source.runtime.BootClockDomainProvider
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandDispatchRequest
import com.adsamcik.tracker.tracker.source.runtime.GuardedPurposeDemandResult
import com.adsamcik.tracker.tracker.source.runtime.SourceCallerDemandDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
	private val sourceCallerDemandDispatcher: SourceCallerDemandDispatcher,
	@Suppress("unused")
	private val bootClockDomainProvider: BootClockDomainProvider,
	private val sourcePolicyRepository: SourcePolicyRepository,
	private val trackingRolloutStateStore: TrackingRolloutStateStore,
	private val currentRetentionAuthority: suspend (Long, Long) -> CurrentRetentionAuthority,
	private val currentSettlementRetentionAuthority: suspend (
		Long,
		Long,
		String,
	) -> CurrentRetentionAuthority = { policyRevision, consentEpoch, _ ->
		currentRetentionAuthority(policyRevision, consentEpoch)
	},
	private val currentPurposeAvailabilityReader: CurrentTrackingPurposeAvailabilityReader,
) {
	@Inject
	internal constructor(
		capabilityResolver: AndroidAmbientStepsCapabilityResolver,
		sourceBroker: SourceBroker,
		sourceCallerDemandDispatcher: SourceCallerDemandDispatcher,
		bootClockDomainProvider: BootClockDomainProvider,
		sourcePolicyRepository: SourcePolicyRepository,
		trackingRolloutStateStore: TrackingRolloutStateStore,
		retentionAuthorityProducer: RetentionAuthorityProducer,
		collectedDataLifecycleStore: CollectedDataLifecycleStore,
		currentPurposeAvailabilityReader: CurrentTrackingPurposeAvailabilityReader,
	) : this(
		capabilityResolver::resolve,
		sourceBroker,
		sourceCallerDemandDispatcher,
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
		{ policyRevision, consentEpoch, settlementOperationId ->
			val lifecycle = collectedDataLifecycleStore.snapshot()
			retentionAuthorityProducer.currentLiveAmbientForSettlement(
				source = TrackingSourceComponent.STEPS,
				expectedSourcePolicyRevision = policyRevision,
				expectedAmbientConsentEpoch = consentEpoch,
				expectedCollectedDataEpoch = lifecycle.epoch,
				expectedRetainedFromMs = lifecycle.retainedFromMs,
				settlementOperationId = settlementOperationId,
			)
		},
		currentPurposeAvailabilityReader,
	)

	internal suspend fun reconcileAt(
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsDemandReconciliation {
		val identity = currentPurposeAvailabilityReader.availability.value
			.ambientSources.getValue(AmbientTrackingSource.STEPS)
			.operationalIdentity
			?: return AmbientStepsDemandReconciliation.PolicyBlocked(
				provider = null,
				reason = AmbientStepsDemandBlockReason.CALLER_AUTHORITY_UNAVAILABLE,
			)
		val retention = currentRetentionAuthority(identity.policyRevision, identity.consentEpoch)
			as? CurrentRetentionAuthority.Approved
			?: return AmbientStepsDemandReconciliation.PolicyBlocked(
				provider = null,
				reason = AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
			)
		return reconcileAt(
			boundary,
			AmbientReconciliationLease(
				com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity.from(
					identity,
					retention.opaquePolicyId,
					retention.approvalRevision,
				),
			),
		)
	}

	internal suspend fun reconcileAt(
		boundary: AmbientStepsDemandBoundary,
		lease: AmbientReconciliationLease,
	): AmbientStepsDemandReconciliation = reconcileAt(
		boundary,
		lease,
		settlementOperationId = null,
	)

	internal suspend fun reconcileForRetentionFloorAt(
		boundary: AmbientStepsDemandBoundary,
		lease: AmbientReconciliationLease,
		settlementOperationId: String,
	): AmbientStepsDemandReconciliation {
		require(settlementOperationId.isNotBlank())
		return reconcileAt(boundary, lease, settlementOperationId)
	}

	private suspend fun reconcileAt(
		boundary: AmbientStepsDemandBoundary,
		lease: AmbientReconciliationLease,
		settlementOperationId: String?,
	): AmbientStepsDemandReconciliation {
		require(lease.identity.source == AmbientTrackingSource.STEPS)
		val policyAuthority = ambientPolicyAuthority(boundary, lease, settlementOperationId)
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
				val guarded = try {
					sourceCallerDemandDispatcher.dispatchAmbientSteps(
						AmbientStepsDemandDispatchRequest(
							identity = lease.identity,
							consumerId = CONSUMER_ID,
							mechanism = capability.provider.toAcquisitionMechanism(),
							bootId = boundary.bootId,
							elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
							wallTimeMs = boundary.wallTimeMs,
						),
					)
				} catch (cancelled: CancellationException) {
					withContext(NonCancellable) { retireDemand(boundary, lease) }
					throw cancelled
				} catch (_: Exception) {
					retireDemand(boundary, lease)
					return AmbientStepsDemandReconciliation.PolicyBlocked(
						provider = capability.provider,
						reason = AmbientStepsDemandBlockReason.CALLER_AUTHORITY_UNAVAILABLE,
					)
				}
				when (guarded) {
					is GuardedPurposeDemandResult.Rejected,
					is GuardedPurposeDemandResult.RejectedAfterCleanup,
					GuardedPurposeDemandResult.Stale,
					-> {
						retireDemand(boundary, lease)
						AmbientStepsDemandReconciliation.PolicyBlocked(
							provider = capability.provider,
							reason = AmbientStepsDemandBlockReason.CALLER_AUTHORITY_UNAVAILABLE,
						)
					}
					is GuardedPurposeDemandResult.Applied -> when (val demand = guarded.value) {
						is AmbientStepsDemandResult.Active ->
							AmbientStepsDemandReconciliation.DemandReady(
								provider = capability.provider,
								importAccess = capability.importAccess,
								optionalPermissions = capability.optionalPermissions,
								demandId = demand.demand.demandId,
							)
						is AmbientStepsDemandResult.Inactive ->
							AmbientStepsDemandReconciliation.PolicyBlocked(
								provider = capability.provider,
								reason = demand.reason.toPublicReason(),
							)
					}
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
			AmbientStepsRetirementPlan.Unverifiable -> {
				val retired = retireDemand(boundary, lease = null)
				return AmbientStepsDemandReconciliation.PolicyBlocked(
					provider = null,
					reason = AmbientStepsDemandBlockReason.RETENTION_POLICY_UNAVAILABLE,
					retirementComplete = retired,
				)
			}
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
		settlementOperationId: String?,
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
					else -> when (val retention = if (settlementOperationId == null) {
						currentRetentionAuthority(
							authority.snapshot.revision,
							requireNotNull(policy.ambientConsentEpoch),
						)
					} else {
						currentSettlementRetentionAuthority(
							authority.snapshot.revision,
							requireNotNull(policy.ambientConsentEpoch),
							settlementOperationId,
						)
					}) {
						is CurrentRetentionAuthority.Approved ->
							if (
								retention.collectedDataEpoch ==
									lease.identity.collectedDataEpoch &&
								retention.retainedFromMs == lease.identity.retainedFromMs &&
								retention.opaquePolicyId ==
									lease.identity.retentionPolicyId &&
								retention.approvalRevision ==
									lease.identity.retentionApprovalRevision &&
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

	internal suspend fun retireDemand(
		boundary: AmbientStepsDemandBoundary,
		lease: AmbientReconciliationLease?,
	): Boolean {
		lease?.let { require(it.identity.source == AmbientTrackingSource.STEPS) }
		return try {
			sourceCallerDemandDispatcher.retireAmbientSteps(
				consumerId = CONSUMER_ID,
				leaseIdentity = lease?.identity,
				bootId = boundary.bootId,
				elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
				wallTimeMs = boundary.wallTimeMs,
			) is GuardedPurposeDemandResult.Applied
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			false
		}
	}

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
	CALLER_AUTHORITY_UNAVAILABLE,
	RETENTION_AUTHORITY_UNAVAILABLE,
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
	AmbientStepsDemandInactiveReason.RETENTION_AUTHORITY_UNAVAILABLE ->
		AmbientStepsDemandBlockReason.RETENTION_AUTHORITY_UNAVAILABLE
}

private fun AmbientStepsProvider.toAcquisitionMechanism(): AmbientStepsAcquisitionMechanism = when (this) {
	AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
		AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
	AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
		AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS
}
