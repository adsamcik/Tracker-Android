package com.adsamcik.tracker.tracker.source.ambient.steps

import com.adsamcik.tracker.shared.base.Time
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandInactiveReason
import com.adsamcik.tracker.tracker.source.runtime.AmbientStepsDemandResult
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
	private val bootClockDomainProvider: BootClockDomainProvider,
) {
	@Inject
	constructor(
		capabilityResolver: AndroidAmbientStepsCapabilityResolver,
		sourceBroker: SourceBroker,
		bootClockDomainProvider: BootClockDomainProvider,
	) : this(capabilityResolver::resolve, sourceBroker, bootClockDomainProvider)

	suspend fun reconcile(): AmbientStepsDemandReconciliation = reconcileAt(
		AmbientStepsDemandBoundary(
			bootId = bootClockDomainProvider.current(),
			elapsedRealtimeNanos = Time.elapsedRealtimeNanos,
			wallTimeMs = Time.nowMillis,
		),
	)

	internal suspend fun reconcileAt(
		boundary: AmbientStepsDemandBoundary,
	): AmbientStepsDemandReconciliation = when (val capability = resolveCapability()) {
		is AmbientStepsCapability.ReadyForRegistration -> {
			when (val demand = sourceBroker.replaceAmbientStepsDemand(
				consumerId = CONSUMER_ID,
				mechanism = capability.provider.toAcquisitionMechanism(),
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
			retireDemand(boundary)
			AmbientStepsDemandReconciliation.PermissionRequired(
				provider = capability.provider,
				requiredPermissions = capability.requiredPermissions,
				optionalPermissions = capability.optionalPermissions,
			)
		}
		is AmbientStepsCapability.Unavailable -> {
			retireDemand(boundary)
			AmbientStepsDemandReconciliation.Unavailable(
				healthConnect = capability.healthConnect,
				localRecording = capability.localRecording,
			)
		}
	}

	private suspend fun retireDemand(boundary: AmbientStepsDemandBoundary) {
		sourceBroker.replaceAmbientStepsDemand(
			consumerId = CONSUMER_ID,
			mechanism = null,
			bootId = boundary.bootId,
			elapsedRealtimeNanos = boundary.elapsedRealtimeNanos,
			wallTimeMs = boundary.wallTimeMs,
		)
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
	) : AmbientStepsDemandReconciliation

	data class PolicyBlocked(
		val provider: AmbientStepsProvider,
		val reason: AmbientStepsDemandBlockReason,
	) : AmbientStepsDemandReconciliation

	data class Unavailable(
		val healthConnect: HealthConnectAmbientStepsAvailability,
		val localRecording: LocalRecordingAmbientStepsAvailability,
	) : AmbientStepsDemandReconciliation
}

enum class AmbientStepsDemandBlockReason {
	REQUEST_DISABLED,
	AUTHORITY_INACTIVE,
	POLICY_MISSING,
	CONSENT_REVOKED,
	PERSISTENCE_INELIGIBLE,
	ROLLOUT_CONTAINED,
}

private fun AmbientStepsDemandInactiveReason.toPublicReason(): AmbientStepsDemandBlockReason = when (this) {
	AmbientStepsDemandInactiveReason.REQUEST_DISABLED -> AmbientStepsDemandBlockReason.REQUEST_DISABLED
	AmbientStepsDemandInactiveReason.AUTHORITY_INACTIVE -> AmbientStepsDemandBlockReason.AUTHORITY_INACTIVE
	AmbientStepsDemandInactiveReason.POLICY_MISSING -> AmbientStepsDemandBlockReason.POLICY_MISSING
	AmbientStepsDemandInactiveReason.CONSENT_REVOKED -> AmbientStepsDemandBlockReason.CONSENT_REVOKED
	AmbientStepsDemandInactiveReason.PERSISTENCE_INELIGIBLE ->
		AmbientStepsDemandBlockReason.PERSISTENCE_INELIGIBLE
	AmbientStepsDemandInactiveReason.ROLLOUT_CONTAINED -> AmbientStepsDemandBlockReason.ROLLOUT_CONTAINED
}

private fun AmbientStepsProvider.toAcquisitionMechanism(): AmbientStepsAcquisitionMechanism = when (this) {
	AmbientStepsProvider.HEALTH_CONNECT_MOBILE_STEPS ->
		AmbientStepsAcquisitionMechanism.HEALTH_CONNECT_MOBILE_STEPS
	AmbientStepsProvider.LOCAL_RECORDING_STEPS ->
		AmbientStepsAcquisitionMechanism.LOCAL_RECORDING_STEPS
}
