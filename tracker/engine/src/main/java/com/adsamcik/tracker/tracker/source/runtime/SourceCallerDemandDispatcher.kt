package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import androidx.room.withTransaction
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticRejectionCode
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticRejectedReason
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCallerAcceptedAuthorityEffectChecksum
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyAuthorityEntity
import com.adsamcik.tracker.shared.model.tracking.TrackingPurpose
import com.adsamcik.tracker.shared.model.tracking.TrackingSource
import com.adsamcik.tracker.tracker.api.AutomaticTrackingOperationalAvailability
import com.adsamcik.tracker.tracker.api.AmbientReconciliationIdentity
import com.adsamcik.tracker.tracker.api.AmbientTrackingSource
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailability
import com.adsamcik.tracker.tracker.api.CurrentTrackingPurposeAvailabilityReader
import com.adsamcik.tracker.tracker.api.SourceCallerAcceptanceReceipt
import com.adsamcik.tracker.tracker.api.SourceCallerDemandIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerGuard
import com.adsamcik.tracker.tracker.api.SourceCallerGuardRejection
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.SourceCallerRequest
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionManifestPurpose
import com.adsamcik.tracker.tracker.source.coordinator.SessionStartOrigin
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.TrackingRolloutStateStore
import com.adsamcik.tracker.tracker.source.model.AmbientStepsAcquisitionMechanism
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal enum class SessionDemandMutation {
	STAGE_UNTIL_FOREGROUND,
	REPLACE_ACTIVE,
	AUTHORITY_ONLY,
}

internal data class SessionSourceDemandDispatchRequest(
	val manifest: SessionManifestVersionEntity,
	val bindings: List<SessionManifestSourceEntity>,
	val sessionMode: SessionMode,
	val startOrigin: SessionStartOrigin,
	val mutation: SessionDemandMutation,
	val lifecycleLeaseGeneration: Long,
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
)

internal sealed interface SessionSourceDemandDispatchResult {
	data class Permitted(
		val receipt: SourceCallerAcceptanceReceipt,
	) : SessionSourceDemandDispatchResult

	data class Rejected(
		val rejection: SourceCallerGuardRejection,
	) : SessionSourceDemandDispatchResult
}

internal data class AutomaticControlDemandDispatchRequest(
	val identity: TrackingPurposeLeaseIdentity,
	val consumerId: String,
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
	val maximumAgeMs: Long,
	val desiredLatencyMs: Long,
)

internal sealed interface GuardedPurposeDemandResult<out T> {
	data class Applied<T>(
		val value: T,
		val receipt: SourceCallerAcceptanceReceipt?,
	) : GuardedPurposeDemandResult<T>

	data class Rejected(
		val rejection: SourceCallerGuardRejection,
	) : GuardedPurposeDemandResult<Nothing>

	data object Stale : GuardedPurposeDemandResult<Nothing>
}

internal data class AmbientStepsDemandDispatchRequest(
	val identity: TrackingPurposeLeaseIdentity,
	val consumerId: String,
	val mechanism: AmbientStepsAcquisitionMechanism,
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
)

internal data class AmbientRadioDemandDispatchRequest(
	val source: AmbientTrackingSource,
	val leaseIdentity: AmbientReconciliationIdentity,
	val consumerId: String,
	val requested: Boolean,
	val reconciliationAttempt: Long,
	val bootId: String,
	val elapsedRealtimeNanos: Long,
	val wallTimeMs: Long,
)

internal data class GuardedAmbientRadioAttempt(
	val request: AmbientRadioDemandDispatchRequest,
	val receipt: SourceCallerAcceptanceReceipt?,
)

internal fun interface SourceCallerCurrentAuthorityPredicate {
	suspend fun permitsActivation(
		reference: SourceCallerReplayReference,
		manifestIdentity: SourceCallerManifestIdentity,
		demands: List<SourceDemandEntity>,
	): Boolean
}

internal interface SourceCallerDemandDispatcher : SourceCallerCurrentAuthorityPredicate {
	suspend fun dispatchSession(
		request: SessionSourceDemandDispatchRequest,
	): SessionSourceDemandDispatchResult

	suspend fun replayPreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult

	suspend fun dispatchAutomaticControl(
		request: AutomaticControlDemandDispatchRequest,
	): GuardedPurposeDemandResult<SourceDemandEntity>

	suspend fun retireAutomaticControl(
		consumerId: String,
		source: SourceKind,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		maximumAgeMs: Long,
		desiredLatencyMs: Long,
	): GuardedPurposeDemandResult<Unit>

	suspend fun dispatchAmbientSteps(
		request: AmbientStepsDemandDispatchRequest,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult>

	suspend fun retireAmbientSteps(
		consumerId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult>

	suspend fun <T> dispatchAmbientRadio(
		request: AmbientRadioDemandDispatchRequest,
		reconcile: suspend (AmbientRadioDemandResult, GuardedAmbientRadioAttempt) -> T,
	): GuardedPurposeDemandResult<T>

	suspend fun compensateAmbientRadio(
		attempt: GuardedAmbientRadioAttempt,
		expectedDemandId: String,
	): AmbientRadioReconciliationAuthority?

	suspend fun isCurrent(identity: TrackingPurposeLeaseIdentity): Boolean

	override suspend fun permitsActivation(
		reference: SourceCallerReplayReference,
		manifestIdentity: SourceCallerManifestIdentity,
		demands: List<SourceDemandEntity>,
	): Boolean
}

internal fun interface CurrentSourceCallerAuthorityProvider {
	suspend fun readCurrentManifest(
		identity: SourceCallerManifestIdentity,
	): SourceCallerAuthoritySnapshot?

	suspend fun readReplayManifest(
		identity: SourceCallerManifestIdentity,
		replayKind: SourceCallerReplayKind,
	): SourceCallerAuthoritySnapshot? = readCurrentManifest(identity)

	suspend fun readCurrentPurpose(
		requested: Set<SourceCallerDemandIdentity>,
	): SourceCallerAuthoritySnapshot = SourceCallerAuthoritySnapshot(
		emptySet(),
		TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
	)
}

/**
 * The only production bridge allowed to turn caller authority into broker session demand.
 *
 * This layer does not register providers. It reconstructs current identities from engine-owned
 * state, asks the opaque guard to accept them, and hands only the exact permitted set to the
 * broker's durable demand mutation.
 */
@Singleton
internal class GuardedSourceCallerDemandDispatcher @Inject constructor(
	private val database: AppDatabase,
	private val authorityReader: CurrentSourceCallerAuthorityProvider,
	private val guard: SourceCallerGuard,
	private val sourceBroker: SourceBroker,
	private val authorityRepository: SourceCallerAcceptedAuthorityRepository,
) : SourceCallerDemandDispatcher {
	override suspend fun dispatchSession(
		request: SessionSourceDemandDispatchRequest,
	): SessionSourceDemandDispatchResult {
		val manifestIdentity = request.manifest.toCallerIdentity()
		validateManifestBindings(request, manifestIdentity)?.let { return rejected(it) }
		val snapshot = try {
			authorityReader.readCurrentManifest(manifestIdentity)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return rejected(SourceCallerRejectionReason.AUTHORITY_STORAGE_UNAVAILABLE)
		}
		if (snapshot == null) return rejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		val identities = snapshot.currentDemandIdentities
		val captureSources = identities.asSequence()
			.filter { it.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE }
			.map { it.sourcePurpose.source }
			.toSet()
		val callerRequest = when {
			request.startOrigin == SessionStartOrigin.RECOVERY ->
				SourceCallerRequest.RecoverySessionStart(
					requestedCapturedSources = captureSources,
					manifestIdentity = manifestIdentity,
					requestedDemandIdentities = identities,
				)
			request.sessionMode == SessionMode.MANUAL -> SourceCallerRequest.ManualSessionStart(
				requestedCapturedSources = captureSources,
				manifestIdentity = manifestIdentity,
				requestedDemandIdentities = identities,
			)
			request.sessionMode == SessionMode.AUTOMATIC ->
				SourceCallerRequest.AutomaticSessionStart(
					requestedCapturedSources = captureSources,
					declaredControlDependencies = identities.asSequence()
						.filter { it.sourcePurpose.purpose == TrackingPurpose.CONTROL }
						.map { it.sourcePurpose.source }
						.toSet(),
					manifestIdentity = manifestIdentity,
					requestedDemandIdentities = identities,
				)
			else ->
				return rejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		}
		val permitted = when (val result = guard.accept(callerRequest)) {
			is SourceCallerGuardResult.Permitted -> result.receipt
			is SourceCallerGuardResult.Rejected -> return rejected(result.rejection)
		}
		if (permitted.permittedDemandIdentities != identities) {
			return rejectAccepted(
				permitted,
				SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
			)
		}
		if (permitted.permittedDemandIdentities.any { identity ->
				identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE &&
					identity.purposeLeaseIdentity.executionRevision !=
					request.lifecycleLeaseGeneration
			}
		) return rejectAccepted(
			permitted,
			SourceCallerRejectionReason.STALE_EXECUTION_REVISION,
		)
		val demands = sourceBroker.buildSessionDemands(
			logicalTrackingId = request.manifest.logicalTrackingId,
			serviceRunId = request.manifest.serviceRunId,
			manifestRevision = request.manifest.manifestRevision,
			lifecycleLeaseGeneration = request.lifecycleLeaseGeneration,
			policyRevision = request.manifest.sourcePolicyRevision,
			bindings = request.bindings,
			bootId = request.bootId,
			elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			wallTimeMs = request.wallTimeMs,
			sourceCallerAuthorityReference = permitted.reference.value,
		)
		if (!demands.match(permitted)) {
			return rejectAccepted(
				permitted,
				SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
			)
		}
		when (request.mutation) {
			SessionDemandMutation.STAGE_UNTIL_FOREGROUND ->
				sourceBroker.stageSessionDemandsInTransaction(
					request.manifest.logicalTrackingId,
					demands,
					request.bootId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
				)
			SessionDemandMutation.REPLACE_ACTIVE ->
				sourceBroker.replaceSessionDemandsInTransaction(
					request.manifest.logicalTrackingId,
					demands,
					request.bootId,
					request.elapsedRealtimeNanos,
					request.wallTimeMs,
					retireSupersededAuthority =
						request.startOrigin != SessionStartOrigin.POLICY_RECONCILIATION,
				)
			SessionDemandMutation.AUTHORITY_ONLY -> Unit
		}
		return SessionSourceDemandDispatchResult.Permitted(permitted)
	}

	private suspend fun rejectAccepted(
		receipt: SourceCallerAcceptanceReceipt,
		reason: SourceCallerRejectionReason,
	): SessionSourceDemandDispatchResult.Rejected {
		check(authorityRepository.delete(receipt.reference)) {
			"Unable to roll back rejected source-caller authority"
		}
		return rejected(reason)
	}

	override suspend fun replayPreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult {
		if (replayKind == SourceCallerReplayKind.POLICY_RECONCILIATION) {
			return replayRejected(
				SourceCallerRejectionReason.REPLAY_KIND_REQUIRES_FRESH_ACCEPTANCE,
			)
		}
		val snapshot = try {
			authorityReader.readReplayManifest(manifestIdentity, replayKind)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			return replayRejected(SourceCallerRejectionReason.AUTHORITY_STORAGE_UNAVAILABLE)
		}
		if (snapshot == null) {
			return replayRejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		}
		return guard.accept(
			SourceCallerRequest.Replay(
				replayKind = replayKind,
				reference = reference,
				purpose = TrackingPurpose.SESSION_CAPTURE,
				requestedDemandIdentities = snapshot.currentDemandIdentities,
			),
		).also { result ->
			if (result is SourceCallerGuardResult.Rejected) logRejected()
		}
	}

	override suspend fun dispatchAutomaticControl(
		request: AutomaticControlDemandDispatchRequest,
	): GuardedPurposeDemandResult<SourceDemandEntity> = database.withTransaction {
		val identity = request.identity
		if (identity.source != TrackingSource.ACTIVITY ||
			identity.purpose != TrackingPurpose.CONTROL
		) return@withTransaction rejectedPurpose(
			SourceCallerRejectionReason.AUTOMATIC_CONTROL_SET_MISMATCH,
		)
		val demandIdentity = SourceCallerDemandIdentity(identity, manifestIdentity = null)
		val snapshot = readCurrentPurposeOrNull(setOf(demandIdentity))
			?: return@withTransaction rejectedPurpose(
				SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
			)
		if (demandIdentity !in snapshot.currentDemandIdentities) {
			return@withTransaction rejectedPurpose(
				SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
			)
		}
		val receipt = when (val accepted = guard.accept(
			SourceCallerRequest.PurposeOwnerMutation(
				source = TrackingSource.ACTIVITY,
				purpose = TrackingPurpose.CONTROL,
				enabled = true,
				requestedDemandIdentities = setOf(demandIdentity),
			),
		)) {
			is SourceCallerGuardResult.Permitted -> accepted.receipt
			is SourceCallerGuardResult.Rejected ->
				return@withTransaction rejectedPurpose(accepted.rejection)
		}
		val demand = sourceBroker.replaceAutomaticControlDemand(
			consumerId = request.consumerId,
			source = SourceKind.ACTIVITY,
			enabled = true,
			bootId = request.bootId,
			elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			wallTimeMs = request.wallTimeMs,
			maximumAgeMs = request.maximumAgeMs,
			desiredLatencyMs = request.desiredLatencyMs,
			sourceCallerAuthorityReference = receipt.reference.value,
		) ?: run {
			check(authorityRepository.delete(receipt.reference)) {
				"Unable to roll back automatic-control caller authority"
			}
			return@withTransaction rejectedPurpose(
				SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
			)
		}
		if (!isCurrent(identity)) {
			check(sourceBroker.retireAcceptedPurposeDemand(
				expected = demand,
				bootId = request.bootId,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				wallTimeMs = request.wallTimeMs,
			)) {
				"Unable to contain stale automatic-control demand"
			}
			return@withTransaction GuardedPurposeDemandResult.Stale
		}
		GuardedPurposeDemandResult.Applied(demand, receipt)
	}

	override suspend fun retireAutomaticControl(
		consumerId: String,
		source: SourceKind,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
		maximumAgeMs: Long,
		desiredLatencyMs: Long,
	): GuardedPurposeDemandResult<Unit> = retireAcceptedPurposeDemand(
			consumerId = consumerId,
			source = TrackingSource.fromStableCode(source.stableCode),
			purpose = TrackingPurpose.CONTROL,
			brokerPurpose = SourceBrokerPurpose.CONTROL_AUTOSTART,
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
		)

	override suspend fun dispatchAmbientSteps(
		request: AmbientStepsDemandDispatchRequest,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult> {
		val identity = request.identity
		if (identity.source != TrackingSource.STEPS ||
			identity.purpose != TrackingPurpose.AMBIENT_PRODUCT
		) return rejectedPurpose(
			SourceCallerRejectionReason.AMBIENT_SOURCE_NOT_SUPPORTED,
		)
		val retentionSnapshot = sourceBroker.captureLiveAmbientRetentionSnapshot(
			source = SourceKind.STEPS,
			sourcePolicyRevision = identity.policyRevision,
			ambientConsentEpoch = identity.consentEpoch,
			collectedDataEpoch = identity.collectedDataEpoch,
			currentBootId = request.bootId,
			currentElapsedRealtimeNanos = request.elapsedRealtimeNanos,
			currentWallTimeMs = request.wallTimeMs,
		)
		val retentionGrant = retentionSnapshot?.grants?.get(SourceKind.STEPS)
			?.takeIf { grant -> grant.retainedFromMs == identity.retainedFromMs }
			?: return rejectedPurpose(
				SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
			)
		val ambientIdentity = AmbientReconciliationIdentity.from(
			identity,
			retentionGrant.opaquePolicyId,
			retentionGrant.approvalRevision,
		)
		return database.withTransaction {
		val demandIdentity = SourceCallerDemandIdentity(identity, manifestIdentity = null)
		val snapshot = readCurrentPurposeOrNull(setOf(demandIdentity))
			?: return@withTransaction rejectedPurpose(
				SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
			)
		if (demandIdentity !in snapshot.currentDemandIdentities) {
			return@withTransaction rejectedPurpose(
				SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
			)
		}
		val receipt = when (val accepted = guard.accept(
			SourceCallerRequest.PurposeOwnerMutation(
				source = TrackingSource.STEPS,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				enabled = true,
				requestedDemandIdentities = setOf(demandIdentity),
			),
		)) {
			is SourceCallerGuardResult.Permitted -> accepted.receipt
			is SourceCallerGuardResult.Rejected ->
				return@withTransaction rejectedPurpose(accepted.rejection)
		}
		val result = sourceBroker.replaceAmbientStepsDemand(
			consumerId = request.consumerId,
			mechanism = request.mechanism,
			leaseIdentity = ambientIdentity,
			bootId = request.bootId,
			elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			wallTimeMs = request.wallTimeMs,
			sourceCallerAuthorityReference = receipt.reference.value,
			retentionSnapshot = retentionSnapshot,
		)
		if (result is AmbientStepsDemandResult.Inactive) {
			check(authorityRepository.delete(receipt.reference)) {
				"Unable to roll back Ambient Steps caller authority"
			}
			return@withTransaction GuardedPurposeDemandResult.Applied(result, null)
		}
		val active = result as AmbientStepsDemandResult.Active
		if (!isCurrent(identity)) {
			check(sourceBroker.retireAcceptedPurposeDemand(
				expected = active.demand,
				bootId = request.bootId,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				wallTimeMs = request.wallTimeMs,
			)) {
				"Unable to contain stale Ambient Steps demand"
			}
			return@withTransaction GuardedPurposeDemandResult.Stale
		}
		GuardedPurposeDemandResult.Applied(result, receipt)
		}
	}

	override suspend fun retireAmbientSteps(
		consumerId: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult> =
		when (val retired = retireAcceptedPurposeDemand(
			consumerId = consumerId,
			source = TrackingSource.STEPS,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			brokerPurpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
			bootId = bootId,
			elapsedRealtimeNanos = elapsedRealtimeNanos,
			wallTimeMs = wallTimeMs,
		)) {
			is GuardedPurposeDemandResult.Applied -> GuardedPurposeDemandResult.Applied(
				AmbientStepsDemandResult.Inactive(AmbientStepsDemandInactiveReason.REQUEST_DISABLED),
				retired.receipt,
			)
			is GuardedPurposeDemandResult.Rejected -> retired
			GuardedPurposeDemandResult.Stale -> GuardedPurposeDemandResult.Stale
		}

	override suspend fun <T> dispatchAmbientRadio(
		request: AmbientRadioDemandDispatchRequest,
		reconcile: suspend (AmbientRadioDemandResult, GuardedAmbientRadioAttempt) -> T,
	): GuardedPurposeDemandResult<T> {
		if (request.source != AmbientTrackingSource.WIFI &&
			request.source != AmbientTrackingSource.CELL
		) {
			return rejectedPurpose(SourceCallerRejectionReason.AMBIENT_SOURCE_NOT_SUPPORTED)
		}
		val retentionSnapshot = if (request.requested) {
			sourceBroker.captureLiveAmbientRetentionSnapshot(
				source = SourceKind.entries.single {
					it.stableCode == request.source.canonicalSource.stableCode
				},
				sourcePolicyRevision = request.leaseIdentity.policyRevision,
				ambientConsentEpoch = request.leaseIdentity.consentEpoch,
				collectedDataEpoch = request.leaseIdentity.collectedDataEpoch,
				currentBootId = request.bootId,
				currentElapsedRealtimeNanos = request.elapsedRealtimeNanos,
				currentWallTimeMs = request.wallTimeMs,
			)
		} else {
			LiveAmbientRetentionSnapshot(emptyMap())
		}
		val leaseMutation = if (request.requested) {
			sourceBroker.withAmbientRadioMutationLease(request.leaseIdentity) {
				dispatchAmbientRadioUnderHeldLease(request, retentionSnapshot, reconcile)
			}
		} else {
			sourceBroker.withAmbientRadioReductionLease(request.leaseIdentity) {
				dispatchAmbientRadioUnderHeldLease(request, retentionSnapshot, reconcile)
			}
		}
		return when (leaseMutation) {
			is AmbientRadioLeaseMutation.Applied -> leaseMutation.value
			AmbientRadioLeaseMutation.Stale -> GuardedPurposeDemandResult.Stale
		}
	}

	private suspend fun <T> dispatchAmbientRadioUnderHeldLease(
		request: AmbientRadioDemandDispatchRequest,
		retentionSnapshot: LiveAmbientRetentionSnapshot?,
		reconcile: suspend (AmbientRadioDemandResult, GuardedAmbientRadioAttempt) -> T,
	): GuardedPurposeDemandResult<T> {
			if (!request.requested) {
				val result = when (request.source) {
					AmbientTrackingSource.WIFI ->
						sourceBroker.replaceAmbientWifiDemandUnderHeldLease(
							consumerId = request.consumerId,
							requested = false,
							leaseIdentity = request.leaseIdentity,
							reconciliationAttempt = request.reconciliationAttempt,
							bootId = request.bootId,
							elapsedRealtimeNanos = request.elapsedRealtimeNanos,
							wallTimeMs = request.wallTimeMs,
						)
					AmbientTrackingSource.CELL ->
						sourceBroker.replaceAmbientCellDemandUnderHeldLease(
							consumerId = request.consumerId,
							requested = false,
							leaseIdentity = request.leaseIdentity,
							reconciliationAttempt = request.reconciliationAttempt,
							bootId = request.bootId,
							elapsedRealtimeNanos = request.elapsedRealtimeNanos,
							wallTimeMs = request.wallTimeMs,
						)
					AmbientTrackingSource.STEPS,
					AmbientTrackingSource.LOCATION,
					-> error("Unsupported ambient-radio source passed validation")
				}
				val attempt = GuardedAmbientRadioAttempt(request, receipt = null)
				return GuardedPurposeDemandResult.Applied(
					reconcile(result, attempt),
					receipt = null,
				)
			}
			val mutation = database.withTransaction {
				val identity = request.leaseIdentity.purposeLeaseIdentity
				val demandIdentity = SourceCallerDemandIdentity(identity, manifestIdentity = null)
				val snapshot = readCurrentPurposeOrNull(setOf(demandIdentity))
					?: run {
						fenceAmbientRadioDemand(request)
						return@withTransaction rejectedPurpose<AmbientRadioDemandResult>(
							SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
						)
					}
				if (demandIdentity !in snapshot.currentDemandIdentities) {
					fenceAmbientRadioDemand(request)
					return@withTransaction rejectedPurpose<AmbientRadioDemandResult>(
						SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH,
					)
				}
				val receipt = when (val accepted = guard.accept(
					SourceCallerRequest.PurposeOwnerMutation(
						source = request.source.canonicalSource,
						purpose = TrackingPurpose.AMBIENT_PRODUCT,
						enabled = request.requested,
						requestedDemandIdentities = setOf(demandIdentity),
					),
				)) {
					is SourceCallerGuardResult.Permitted -> accepted.receipt
					is SourceCallerGuardResult.Rejected -> {
						fenceAmbientRadioDemand(request)
						return@withTransaction rejectedPurpose(accepted.rejection)
					}
				}
				val result = when (request.source) {
					AmbientTrackingSource.WIFI ->
						sourceBroker.replaceAmbientWifiDemandUnderHeldLease(
							consumerId = request.consumerId,
							requested = request.requested,
							leaseIdentity = request.leaseIdentity,
							reconciliationAttempt = request.reconciliationAttempt,
							bootId = request.bootId,
							elapsedRealtimeNanos = request.elapsedRealtimeNanos,
							wallTimeMs = request.wallTimeMs,
							sourceCallerAuthorityReference = receipt.reference.value,
							retentionSnapshot = retentionSnapshot,
						)
					AmbientTrackingSource.CELL ->
						sourceBroker.replaceAmbientCellDemandUnderHeldLease(
							consumerId = request.consumerId,
							requested = request.requested,
							leaseIdentity = request.leaseIdentity,
							reconciliationAttempt = request.reconciliationAttempt,
							bootId = request.bootId,
							elapsedRealtimeNanos = request.elapsedRealtimeNanos,
							wallTimeMs = request.wallTimeMs,
							sourceCallerAuthorityReference = receipt.reference.value,
							retentionSnapshot = retentionSnapshot,
						)
					AmbientTrackingSource.STEPS,
					AmbientTrackingSource.LOCATION,
					-> error("Unsupported ambient-radio source passed validation")
				}
				if (request.requested && result is AmbientRadioDemandResult.Inactive) {
					check(authorityRepository.delete(receipt.reference)) {
						"Unable to roll back ambient-radio caller authority"
					}
					GuardedPurposeDemandResult.Applied(result, null)
				} else {
					GuardedPurposeDemandResult.Applied(result, receipt)
				}
			}
			when (mutation) {
				is GuardedPurposeDemandResult.Applied -> {
					val attempt = GuardedAmbientRadioAttempt(request, mutation.receipt)
					val current = try {
						isCurrent(request.leaseIdentity.purposeLeaseIdentity)
					} catch (cancelled: CancellationException) {
						(mutation.value as? AmbientRadioDemandResult.Active)?.let { active ->
							kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
								compensateAmbientRadio(attempt, active.demand.demandId)
							}
						}
						throw cancelled
					} catch (failure: RuntimeException) {
						(mutation.value as? AmbientRadioDemandResult.Active)?.let { active ->
							kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
								compensateAmbientRadio(attempt, active.demand.demandId)
							}
						}
						throw failure
					}
					if (!current) {
						(mutation.value as? AmbientRadioDemandResult.Active)?.let { active ->
							kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
								compensateAmbientRadio(attempt, active.demand.demandId)
							}
						}
						return GuardedPurposeDemandResult.Stale
					}
					GuardedPurposeDemandResult.Applied(
						reconcile(mutation.value, attempt),
						mutation.receipt,
					)
				}
				is GuardedPurposeDemandResult.Rejected -> mutation
				GuardedPurposeDemandResult.Stale -> GuardedPurposeDemandResult.Stale
			}
	}

	private suspend fun fenceAmbientRadioDemand(
		request: AmbientRadioDemandDispatchRequest,
	) {
		try {
			sourceBroker.retirePurposeDemand(
				consumerId = request.consumerId,
				expectedSourceKind = request.source.canonicalSource.stableCode,
				expectedPurpose = SourceBrokerPurpose.AMBIENT_PRODUCT,
				bootId = request.bootId,
				elapsedRealtimeNanos = request.elapsedRealtimeNanos,
				wallTimeMs = request.wallTimeMs,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: Exception) {
			// Provider reconciliation still runs and records durable physical cleanup debt.
		}
	}

	override suspend fun compensateAmbientRadio(
		attempt: GuardedAmbientRadioAttempt,
		expectedDemandId: String,
	): AmbientRadioReconciliationAuthority? {
		val compensated = when (attempt.request.source) {
		AmbientTrackingSource.WIFI ->
			sourceBroker.compensateAmbientWifiDemandUnderHeldLease(
				attempt.request.consumerId,
				attempt.request.leaseIdentity,
				attempt.request.reconciliationAttempt,
				expectedDemandId,
				attempt.request.bootId,
				attempt.request.elapsedRealtimeNanos,
				attempt.request.wallTimeMs,
			)
		AmbientTrackingSource.CELL ->
			sourceBroker.compensateAmbientCellDemandUnderHeldLease(
				attempt.request.consumerId,
				attempt.request.leaseIdentity,
				attempt.request.reconciliationAttempt,
				expectedDemandId,
				attempt.request.bootId,
				attempt.request.elapsedRealtimeNanos,
				attempt.request.wallTimeMs,
			)
		AmbientTrackingSource.STEPS,
		AmbientTrackingSource.LOCATION,
		-> null
		}
		return compensated
	}

	override suspend fun isCurrent(identity: TrackingPurposeLeaseIdentity): Boolean =
		readCurrentPurposeOrNull(
			setOf(SourceCallerDemandIdentity(identity, manifestIdentity = null)),
		)?.currentDemandIdentities
			?.any { it.purposeLeaseIdentity == identity } == true

	override suspend fun permitsActivation(
		reference: SourceCallerReplayReference,
		manifestIdentity: SourceCallerManifestIdentity,
		demands: List<SourceDemandEntity>,
	): Boolean {
		val accepted = when (val loaded = authorityRepository.load(reference)) {
			is StoredSourceCallerAuthorityLoadResult.Available -> loaded.authority
			StoredSourceCallerAuthorityLoadResult.Missing,
			StoredSourceCallerAuthorityLoadResult.Corrupt,
			StoredSourceCallerAuthorityLoadResult.Retired,
			-> return false
		}
		if (accepted.purpose != TrackingPurpose.SESSION_CAPTURE) return false
		val current = authorityReader.readCurrentManifest(manifestIdentity) ?: return false
		if (accepted.permittedDemandIdentities != current.currentDemandIdentities) return false
		return demands.match(
			SourceCallerAcceptanceReceipt(reference, accepted.permittedDemandIdentities),
		)
	}

	private suspend fun retireAcceptedPurposeDemand(
		consumerId: String,
		source: TrackingSource,
		purpose: TrackingPurpose,
		brokerPurpose: String,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): GuardedPurposeDemandResult<Unit> {
		if (!source.supports(purpose)) {
			return rejectedPurpose(
				SourceCallerRejectionReason.UNDECLARED_DEMAND,
			)
		}
		return if (sourceBroker.retirePurposeDemand(
				consumerId = consumerId,
				expectedSourceKind = source.stableCode,
				expectedPurpose = brokerPurpose,
				bootId = bootId,
				elapsedRealtimeNanos = elapsedRealtimeNanos,
				wallTimeMs = wallTimeMs,
			)
		) {
			GuardedPurposeDemandResult.Applied(Unit, null)
		} else {
			GuardedPurposeDemandResult.Stale
		}
	}

	private suspend fun readCurrentPurposeOrNull(
		requested: Set<SourceCallerDemandIdentity>,
	): SourceCallerAuthoritySnapshot? = try {
		authorityReader.readCurrentPurpose(requested)
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (_: Exception) {
		null
	}

	private fun <T> rejectedPurpose(
		reason: SourceCallerRejectionReason,
	): GuardedPurposeDemandResult<T> = rejectedPurpose(SourceCallerGuardRejection(reason))

	private fun <T> rejectedPurpose(
		rejection: SourceCallerGuardRejection,
	): GuardedPurposeDemandResult<T> {
		logRejected()
		return GuardedPurposeDemandResult.Rejected(rejection)
	}

	private fun rejected(
		reason: SourceCallerRejectionReason,
	): SessionSourceDemandDispatchResult.Rejected = rejected(
		SourceCallerGuardRejection(reason),
	)

	private fun rejected(
		rejection: SourceCallerGuardRejection,
	): SessionSourceDemandDispatchResult.Rejected {
		logRejected()
		return SessionSourceDemandDispatchResult.Rejected(rejection)
	}

	private fun validateManifestBindings(
		request: SessionSourceDemandDispatchRequest,
		manifestIdentity: SourceCallerManifestIdentity,
	): SourceCallerGuardRejection? {
		if (request.manifest.sessionMode != request.sessionMode.name ||
			request.manifest.startOrigin != request.startOrigin.name ||
			request.bindings.isEmpty() ||
			request.bindings.any { binding ->
				binding.logicalTrackingId != manifestIdentity.logicalTrackingId ||
					binding.manifestRevision != manifestIdentity.manifestRevision
			}
		) return SourceCallerGuardRejection(SourceCallerRejectionReason.SESSION_MANIFEST_MISMATCH)
		val controls = request.bindings.filter {
			it.purpose == SessionManifestPurpose.CONTROL.name
		}
		val invalidPurpose = request.bindings.firstOrNull {
			it.purpose !in setOf(
				SourceBrokerPurpose.SESSION_CAPTURE,
				SessionManifestPurpose.CONTROL.name,
			)
		}
		if (invalidPurpose != null) {
			return SourceCallerGuardRejection(SourceCallerRejectionReason.UNDECLARED_DEMAND)
		}
		if (request.bindings.distinctBy { it.sourceKind to it.purpose }.size !=
			request.bindings.size
		) {
			return SourceCallerGuardRejection(SourceCallerRejectionReason.DUPLICATE_DEMAND_IDENTITY)
		}
		return when (request.sessionMode) {
			SessionMode.MANUAL -> controls.firstOrNull()?.let { binding ->
				SourceCallerGuardRejection(
					reason = SourceCallerRejectionReason.UNDECLARED_DEMAND,
					source = TrackingSource.fromStableCode(binding.sourceKind),
					purpose = TrackingPurpose.CONTROL,
				)
			}
			SessionMode.AUTOMATIC -> if (
				controls.size == 1 &&
				controls.single().sourceKind == TrackingSource.ACTIVITY.stableCode
			) {
				null
			} else {
				SourceCallerGuardRejection(
					reason = SourceCallerRejectionReason.AUTOMATIC_CONTROL_SET_MISMATCH,
					source = TrackingSource.ACTIVITY,
					purpose = TrackingPurpose.CONTROL,
				)
			}
			SessionMode.LEGACY_UNKNOWN ->
				SourceCallerGuardRejection(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		}
	}

	private fun logRejected() {
		TrackerDiagnosticLog.rejected(
			TrackerDiagnosticRejectionCode.TRACKING_SOURCE_SESSION_START_REJECTED,
			TrackingDiagnosticRejectedReason.START_NOT_AUTHORIZED,
		)
	}

	private fun replayRejected(
		reason: SourceCallerRejectionReason,
	): SourceCallerGuardResult.Rejected {
		logRejected()
		return SourceCallerGuardResult.Rejected(SourceCallerGuardRejection(reason))
	}
}

/**
 * Rebuilds caller identities from current persisted engine authority. Request identities are never
 * trusted as the current value.
 */
@Singleton
internal class CurrentSourceCallerAuthorityReader @Inject constructor(
	private val database: AppDatabase,
	private val rolloutStateStore: TrackingRolloutStateStore,
	private val purposeAvailabilityReader: CurrentTrackingPurposeAvailabilityReader,
	private val exactPurposeAuthorityReader: TrackingPurposeAuthorityReader,
	private val executionRevisionRegistry: TrackingPurposeExecutionRevisionRegistry,
	private val clockDomainProvider: BootClockDomainProvider,
) : SourceCallerAuthoritySnapshotReader, CurrentSourceCallerAuthorityProvider {
	override suspend fun read(request: SourceCallerRequest): SourceCallerAuthoritySnapshot {
		val manifests = request.requestedDemandIdentities.mapNotNull { it.manifestIdentity }.toSet()
		if (manifests.size > 1) return unavailableSnapshot()
		val manifest = manifests.singleOrNull()
		return if (manifest == null) {
			readSessionless(request.requestedDemandIdentities)
		} else if (request is SourceCallerRequest.Replay) {
			readReplayManifest(manifest, request.replayKind) ?: unavailableSnapshot()
		} else {
			readCurrentManifest(manifest) ?: unavailableSnapshot()
		}
	}

	override suspend fun readCurrentManifest(
		identity: SourceCallerManifestIdentity,
	): SourceCallerAuthoritySnapshot? = readCurrentManifest(
		identity = identity,
		requireLiveLease = true,
		allowSuspendedSession = false,
	)

	override suspend fun readReplayManifest(
		identity: SourceCallerManifestIdentity,
		replayKind: SourceCallerReplayKind,
	): SourceCallerAuthoritySnapshot? = readCurrentManifest(
		identity = identity,
		requireLiveLease =
			replayKind == SourceCallerReplayKind.FOREGROUND_SERVICE_DELIVERY,
		allowSuspendedSession =
			replayKind == SourceCallerReplayKind.ACTIVE_REDELIVERY ||
				replayKind == SourceCallerReplayKind.PROCESS_RECOVERY,
	)

	private suspend fun readCurrentManifest(
		identity: SourceCallerManifestIdentity,
		requireLiveLease: Boolean,
		allowSuspendedSession: Boolean,
	): SourceCallerAuthoritySnapshot? {
		val currentBootId = clockDomainProvider.current()
		repeat(AUTHORITY_READ_ATTEMPTS) {
			val availabilityBefore = purposeAvailabilityReader.availability.value
			val current = database.withTransaction {
				readCurrentManifestInTransaction(
					identity,
					availabilityBefore,
					requireLiveLease,
					allowSuspendedSession,
					currentBootId,
				)
			} ?: return null
			if (availabilityBefore == purposeAvailabilityReader.availability.value) return current
		}
		return null
	}

	override suspend fun readCurrentPurpose(
		requested: Set<SourceCallerDemandIdentity>,
	): SourceCallerAuthoritySnapshot = readSessionless(requested)

	private suspend fun readCurrentManifestInTransaction(
		identity: SourceCallerManifestIdentity,
		availability: CurrentTrackingPurposeAvailability,
		requireLiveLease: Boolean,
		allowSuspendedSession: Boolean,
		currentBootId: String,
	): SourceCallerAuthoritySnapshot? {
		val sessionDao = database.sourceSessionDao()
		val session = sessionDao.session(identity.logicalTrackingId)
			?.takeIf {
				it.currentManifestRevision == identity.manifestRevision &&
					it.lifecycleBootId == currentBootId &&
					it.state in setOf(
						SessionLifecycleState.STARTING.name,
						SessionLifecycleState.ACTIVE.name,
						SessionLifecycleState.RECONFIGURING.name,
					)
			}
			?: return null
		val manifest = sessionDao.manifest(identity.logicalTrackingId, identity.manifestRevision)
			?.takeIf {
				it.rolloutRevision == session.rolloutRevision &&
					it.effectiveBootId == session.lifecycleBootId &&
					it.effectiveBootId == currentBootId &&
					(
						it.serviceRunId == session.currentServiceRunId ||
							(allowSuspendedSession &&
								session.state == SessionLifecycleState.ACTIVE.name &&
								session.currentServiceRunId == null)
						)
			}
			?: return null
		val policyAuthority = database.sourcePolicyDao().authority()
			?.takeIf {
				it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
					it.currentPolicyRevision == manifest.sourcePolicyRevision
			}
			?: return null
		val evidenceEpoch = database.sourceEvidenceStateDao().get()?.collectedDataEpoch ?: return null
		val rollout = rolloutStateStore.load().takeIf { it.revision == manifest.rolloutRevision }
			?: return null
		val lease = database.sourceProjectionStateDao().lease(SESSION_LEASE_NAME)
			?.takeIf {
				it.ownerToken.isNotBlank() &&
					it.generation == session.lifecycleLeaseGeneration &&
					it.bootId == session.lifecycleBootId &&
					it.bootId == currentBootId &&
					(!requireLiveLease ||
						it.expiresElapsedRealtimeNanos > SystemClock.elapsedRealtimeNanos())
			}
			?: return null
		val bindings = sessionDao.manifestSources(
			identity.logicalTrackingId,
			identity.manifestRevision,
		)
		if (bindings.isEmpty() || !SessionManifestIntegrity.verify(manifest, bindings)) return null
		val policies = database.sourcePolicyDao()
			.policiesAtRevision(policyAuthority.currentPolicyRevision)
			.associateBy { it.sourceKind }
		val demandIdentities = linkedSetOf<SourceCallerDemandIdentity>()
		for (binding in bindings) {
			val source = TrackingSource.fromStableCode(binding.sourceKind)
			val policy = policies[binding.sourceKind] ?: return null
			when (binding.purpose) {
				SourceBrokerPurpose.SESSION_CAPTURE -> {
					if (!policy.enabled || !policy.capturePersistenceEligible ||
						policy.captureConsentEpoch != binding.consentEpoch
					) return null
					demandIdentities += SourceCallerDemandIdentity(
						purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
							sourcePurpose = source.forPurpose(TrackingPurpose.SESSION_CAPTURE),
							policyRevision = policyAuthority.currentPolicyRevision,
							consentEpoch = binding.consentEpoch,
							collectedDataEpoch = evidenceEpoch,
							rolloutRevision = rollout.revision,
							executionRevision = lease.generation,
							ownerCasToken = lease.ownerToken,
						),
						manifestIdentity = identity,
					)
				}
				SessionManifestPurpose.CONTROL.name -> {
					val ready = availability.automaticControl
						as? AutomaticTrackingOperationalAvailability.Ready
						?: continue
					if (source != TrackingSource.ACTIVITY ||
						policy.controlConsentEpoch != binding.consentEpoch ||
						ready.identity.source != source ||
						ready.identity.purpose != TrackingPurpose.CONTROL ||
						ready.identity.policyRevision != policyAuthority.currentPolicyRevision ||
						ready.identity.consentEpoch != binding.consentEpoch ||
						ready.identity.collectedDataEpoch != evidenceEpoch ||
						ready.identity.rolloutRevision != rollout.revision ||
						!purposeAvailabilityReader.isCurrent(ready.identity)
					) return null
					demandIdentities += SourceCallerDemandIdentity(
						ready.identity,
						manifestIdentity = null,
					)
				}
				else -> return null
			}
		}
		return SourceCallerAuthoritySnapshot(
			currentDemandIdentities = demandIdentities,
			purposeAvailability = availability.toSnapshot(),
		)
	}

	private suspend fun readSessionless(
		requested: Set<SourceCallerDemandIdentity>,
	): SourceCallerAuthoritySnapshot {
		val availability = purposeAvailabilityReader.availability.value
		val requestedKeys = requested.map(SourceCallerDemandIdentity::sourcePurpose).toSet()
		val candidates = buildList {
			(availability.automaticControl as? AutomaticTrackingOperationalAvailability.Ready)
				?.identity
				?.takeIf { it.sourcePurpose !in requestedKeys }
				?.let(::add)
			availability.ambientSources.values.mapNotNullTo(this) { ambient ->
				ambient.operationalIdentity?.takeIf { it.sourcePurpose !in requestedKeys }
			}
		}
		val identities = candidates.mapNotNullTo(linkedSetOf()) { identity ->
			identity.takeIf { purposeAvailabilityReader.isCurrent(it) }
				?.let { SourceCallerDemandIdentity(it, manifestIdentity = null) }
		}
		requested.forEach { requestedIdentity ->
			val lease = requestedIdentity.purposeLeaseIdentity
			val registeredExecution =
				executionRevisionRegistry.revisions.value[lease.sourcePurpose] ?: 0L
			val registeredIdentity =
				executionRevisionRegistry.identities.value[lease.sourcePurpose]
			val current = exactPurposeAuthorityReader.read(
				lease.sourcePurpose,
				registeredExecution,
			)
			val observed = current ?: return@forEach
			if (registeredIdentity == lease &&
				registeredExecution == lease.executionRevision &&
				observed.sourcePurpose == lease.sourcePurpose &&
				observed.policyRevision == lease.policyRevision &&
				observed.consentEpoch == lease.consentEpoch &&
				observed.collectedDataEpoch == lease.collectedDataEpoch &&
				observed.rolloutRevision == lease.rolloutRevision &&
				observed.executionRevision == lease.executionRevision
			) identities += requestedIdentity
		}
		return SourceCallerAuthoritySnapshot(identities, availability.toSnapshot())
	}

	private fun unavailableSnapshot() = SourceCallerAuthoritySnapshot(
		currentDemandIdentities = emptySet(),
		purposeAvailability = TrackingPurposeAvailabilitySnapshot.SAFE_DEFAULT,
	)

	private companion object {
		const val AUTHORITY_READ_ATTEMPTS = 3
		const val SESSION_LEASE_NAME = "tracking-session-coordinator"
	}
}

@Singleton
internal class RoomSourceCallerAcceptedAuthorityRepository @Inject constructor(
	private val database: AppDatabase,
) : SourceCallerAcceptedAuthorityRepository {
	override suspend fun insertIfAbsent(
		reference: SourceCallerReplayReference,
		authority: StoredSourceCallerAuthority,
		createdAtMs: Long,
	): Boolean = database.withTransaction {
		require(createdAtMs >= 0L)
		val dao = database.sourceCallerAuthorityDao()
		if (dao.rows(reference.value).isNotEmpty()) return@withTransaction false
		val rows = SourceCallerAcceptedAuthorityEffectChecksum.seal(
			authority.permittedDemandIdentities.map { identity ->
				val lease = identity.purposeLeaseIdentity
				SourceCallerAcceptedAuthorityEntity(
					reference = reference.value,
					formatVersion = SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION,
					origin = authority.origin.name,
					acceptedPurpose = authority.purpose.stableName,
					sourceKind = lease.source.stableCode,
					purpose = lease.purpose.stableName,
					policyRevision = lease.policyRevision,
					consentEpoch = lease.consentEpoch,
					collectedDataEpoch = lease.collectedDataEpoch,
					retainedFromMs = lease.retainedFromMs,
					rolloutRevision = lease.rolloutRevision,
					executionRevision = lease.executionRevision,
					ownerCasToken = lease.ownerCasToken,
					logicalTrackingId = identity.manifestIdentity?.logicalTrackingId,
					manifestRevision = identity.manifestIdentity?.manifestRevision,
					status = SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE,
					createdAtMs = createdAtMs,
					retiredAtMs = null,
					retireReason = null,
					effectChecksum = "pending",
				)
			},
		)
		dao.insert(rows).size == rows.size
	}

	override suspend fun load(
		reference: SourceCallerReplayReference,
	): StoredSourceCallerAuthorityLoadResult = database.withTransaction {
		val rows = database.sourceCallerAuthorityDao().rows(reference.value)
		if (rows.isEmpty()) return@withTransaction StoredSourceCallerAuthorityLoadResult.Missing
		if (!rows.hasValidStoredAuthorityShape() ||
			!SourceCallerAcceptedAuthorityEffectChecksum.isAuthentic(rows) ||
			rows.any { it.reference != reference.value } ||
			rows.map { it.formatVersion }.toSet() !=
			setOf(SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION) ||
			rows.map { it.origin }.distinct().size != 1 ||
			rows.map { it.acceptedPurpose }.distinct().size != 1 ||
			rows.map { it.createdAtMs }.distinct().size != 1 ||
			rows.map { it.retiredAtMs }.distinct().size != 1 ||
			rows.map { it.retireReason }.distinct().size != 1
		) return@withTransaction StoredSourceCallerAuthorityLoadResult.Corrupt
		if (rows.all { it.status == SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED }) {
			return@withTransaction StoredSourceCallerAuthorityLoadResult.Retired
		}
		if (rows.any { it.status != SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE }) {
			return@withTransaction StoredSourceCallerAuthorityLoadResult.Corrupt
		}
		try {
			val identities = rows.mapTo(linkedSetOf()) { row ->
				val source = TrackingSource.fromStableCode(row.sourceKind)
				val purpose = TrackingPurpose.fromStableName(row.purpose)
				SourceCallerDemandIdentity(
					purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
						sourcePurpose = source.forPurpose(purpose),
						policyRevision = row.policyRevision,
						consentEpoch = row.consentEpoch,
						collectedDataEpoch = row.collectedDataEpoch,
						retainedFromMs = row.retainedFromMs,
						rolloutRevision = row.rolloutRevision,
						executionRevision = row.executionRevision,
						ownerCasToken = row.ownerCasToken,
					),
					manifestIdentity = row.logicalTrackingId?.let { logicalTrackingId ->
						SourceCallerManifestIdentity(
							logicalTrackingId,
							requireNotNull(row.manifestRevision),
						)
					},
				)
			}
			if (identities.size != rows.size) {
				StoredSourceCallerAuthorityLoadResult.Corrupt
			} else {
				StoredSourceCallerAuthorityLoadResult.Available(
					StoredSourceCallerAuthority(
						origin = StoredSourceCallerOrigin.valueOf(rows.first().origin),
						purpose = TrackingPurpose.fromStableName(rows.first().acceptedPurpose),
						permittedDemandIdentities = identities,
					),
				)
			}
		} catch (_: IllegalArgumentException) {
			StoredSourceCallerAuthorityLoadResult.Corrupt
		}
	}

	override suspend fun retire(
		reference: SourceCallerReplayReference,
		reason: String,
		retiredAtMs: Long,
	): Boolean = database.withTransaction {
		require(reason.isNotBlank())
		require(retiredAtMs >= 0L)
		val dao = database.sourceCallerAuthorityDao()
		val rows = dao.rows(reference.value)
		if (rows.isEmpty() || !rows.hasValidStoredAuthorityShape() ||
			!SourceCallerAcceptedAuthorityEffectChecksum.isAuthentic(rows)
		) return@withTransaction false
		if (rows.all { it.status == SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED }) {
			return@withTransaction true
		}
		if (rows.any { it.status != SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE }) {
			return@withTransaction false
		}
		val retired = SourceCallerAcceptedAuthorityEffectChecksum.seal(
			rows.map { row ->
				row.copy(
					status = SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED,
					retiredAtMs = maxOf(retiredAtMs, row.createdAtMs),
					retireReason = reason,
					effectChecksum = "pending",
				)
			},
		)
		dao.update(retired) == retired.size
	}

	override suspend fun retireForTeardown(
		reference: SourceCallerReplayReference,
		reason: String,
		retiredAtMs: Long,
	): Boolean = database.withTransaction {
		require(reason.isNotBlank())
		require(retiredAtMs >= 0L)
		val dao = database.sourceCallerAuthorityDao()
		val rows = dao.rows(reference.value)
		if (rows.isEmpty()) return@withTransaction true
		val canRetireNormally = rows.hasValidStoredAuthorityShape() &&
			SourceCallerAcceptedAuthorityEffectChecksum.isAuthentic(rows) &&
			rows.all { row ->
				row.formatVersion == SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION &&
					row.reference == reference.value
			}
		if (!canRetireNormally) {
			return@withTransaction dao.delete(reference.value) == rows.size
		}
		if (rows.all { it.status == SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED }) {
			return@withTransaction true
		}
		if (rows.any { it.status != SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE }) {
			return@withTransaction dao.delete(reference.value) == rows.size
		}
		val retired = SourceCallerAcceptedAuthorityEffectChecksum.seal(
			rows.map { row ->
				row.copy(
					status = SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED,
					retiredAtMs = maxOf(retiredAtMs, row.createdAtMs),
					retireReason = reason,
					effectChecksum = "pending",
				)
			},
		)
		dao.update(retired) == retired.size
	}

	override suspend fun delete(reference: SourceCallerReplayReference): Boolean =
		database.withTransaction {
			database.sourceCallerAuthorityDao().delete(reference.value) > 0
		}

	override suspend fun pruneRetired(
		retiredBeforeOrAtMs: Long,
		limit: Int,
	): Int = database.withTransaction {
		require(retiredBeforeOrAtMs >= 0L)
		require(limit > 0)
		val dao = database.sourceCallerAuthorityDao()
		val references = dao.retiredReferencesForPrune(retiredBeforeOrAtMs, limit)
		if (references.isEmpty()) {
			0
		} else {
			check(references.all { reference ->
				load(SourceCallerReplayReference(reference)) ==
					StoredSourceCallerAuthorityLoadResult.Retired
			}) {
				"Retired source-caller authority prune encountered invalid rows"
			}
			check(dao.deleteReferences(references) >= references.size) {
				"Retired source-caller authority prune lost rows"
			}
			references.size
		}
	}
}

private fun List<SourceCallerAcceptedAuthorityEntity>.hasValidStoredAuthorityShape(): Boolean =
	size in 1..7 &&
	all { row ->
		row.reference.isNotBlank() &&
			row.formatVersion > 0 &&
			row.origin.isNotBlank() &&
			row.acceptedPurpose.isNotBlank() &&
			row.sourceKind > 0 &&
			row.purpose.isNotBlank() &&
			row.policyRevision > 0L &&
			row.consentEpoch > 0L &&
			row.collectedDataEpoch >= 0L &&
			(row.retainedFromMs == null || row.retainedFromMs >= 0L) &&
			row.rolloutRevision >= 0L &&
			row.executionRevision > 0L &&
			row.ownerCasToken.isNotBlank() &&
			(row.logicalTrackingId == null) == (row.manifestRevision == null) &&
			(row.logicalTrackingId == null || row.logicalTrackingId.isNotBlank()) &&
			(row.manifestRevision == null || row.manifestRevision > 0L) &&
			row.status in setOf(
				SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE,
				SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED,
			) &&
			row.createdAtMs >= 0L &&
			(row.retiredAtMs == null) == (row.retireReason == null) &&
			(row.retiredAtMs == null || row.retiredAtMs >= row.createdAtMs) &&
			(row.retireReason == null || row.retireReason.isNotBlank()) &&
			row.effectChecksum.isNotBlank()
	}

private fun CurrentTrackingPurposeAvailability.toSnapshot() = TrackingPurposeAvailabilitySnapshot(
	automaticControl = automaticControl,
	ambientSources = ambientSources,
)

private fun SessionManifestVersionEntity.toCallerIdentity() = SourceCallerManifestIdentity(
	logicalTrackingId = logicalTrackingId,
	manifestRevision = manifestRevision,
)

private fun List<SourceDemandEntity>.match(receipt: SourceCallerAcceptanceReceipt): Boolean {
	val permitted = receipt.permittedDemandIdentities.associateBy { it.sourcePurpose }
	if (permitted.size != size) return false
	return all { demand ->
		val source = TrackingSource.entries.singleOrNull { it.stableCode == demand.sourceKind }
			?: return@all false
		val purpose = when (demand.purpose) {
			SourceBrokerPurpose.SESSION_CAPTURE -> TrackingPurpose.SESSION_CAPTURE
			SourceBrokerPurpose.CONTROL_CONTINUATION -> TrackingPurpose.CONTROL
			else -> return@all false
		}
		val identity = permitted[source.forPurpose(purpose)] ?: return@all false
		demand.sourceCallerAuthorityReference == receipt.reference.value &&
			identity.purposeLeaseIdentity.policyRevision == demand.sourcePolicyRevision &&
			identity.purposeLeaseIdentity.consentEpoch == demand.consentEpoch &&
			if (purpose == TrackingPurpose.SESSION_CAPTURE) {
				identity.manifestIdentity?.logicalTrackingId == demand.logicalTrackingId &&
					identity.manifestIdentity.manifestRevision == demand.manifestRevision
			} else {
				identity.manifestIdentity == null
			}
	}
}
