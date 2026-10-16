package com.adsamcik.tracker.tracker.source.runtime

import android.os.SystemClock
import com.adsamcik.tracker.shared.base.startup.TrackingStartupGate
import javax.inject.Provider
import androidx.room.withTransaction
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticLog
import com.adsamcik.tracker.diagnostics.TrackerDiagnosticRejectionCode
import com.adsamcik.tracker.diagnostics.TrackingDiagnosticRejectedReason
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCoordinatorLeaseEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
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
import com.adsamcik.tracker.tracker.api.SourceCallerGuardRejection
import com.adsamcik.tracker.tracker.api.SourceCallerGuardResult
import com.adsamcik.tracker.tracker.api.SourceCallerManifestIdentity
import com.adsamcik.tracker.tracker.api.SourceCallerRejectionReason
import com.adsamcik.tracker.tracker.api.SourceCallerReplayKind
import com.adsamcik.tracker.tracker.api.SourceCallerReplayReference
import com.adsamcik.tracker.tracker.api.SourceCallerRequest
import com.adsamcik.tracker.tracker.api.TrackingPurposeAvailabilitySnapshot
import com.adsamcik.tracker.tracker.api.TrackingPurposeLeaseIdentity
import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
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
	val policies: Map<Int, com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity>? = null,
	val stepsContinuation: StepsRecordingContinuation? = null,
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

	/** The caller was rejected, but exact source cleanup completed and must reach its owner. */
	data class RejectedAfterCleanup<T>(
		val rejection: SourceCallerGuardRejection,
		val cleanupResult: T,
	) : GuardedPurposeDemandResult<T>

	data object Stale : GuardedPurposeDemandResult<Nothing>
}

internal data class AmbientStepsDemandDispatchRequest(
	val identity: AmbientReconciliationIdentity,
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

	/**
	 * Authenticates one persisted reference against current engine authority without treating the
	 * check as the recovery replay boundary.
	 */
	suspend fun authenticatePreparedSession(
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

	suspend fun dispatchAmbientLocation(
		request: AmbientLocationDemandDispatchRequest,
	): GuardedPurposeDemandResult<AmbientLocationDemandResult>

	suspend fun retireAmbientSteps(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity?,
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
 * This layer does not register providers. It reconstructs current identities before Room,
 * prepares opaque caller acceptance, and persists that acceptance in the owning Room transaction
 * with the exact permitted demand mutation.
 */
// One guarded demand dispatcher owns these transaction/receipt workflows; method checks stay active.
@Suppress("LargeClass", "TooManyFunctions")
@Singleton
internal class GuardedSourceCallerDemandDispatcher @Inject constructor(
	private val authorityReader: CurrentSourceCallerAuthorityProvider,
	private val guard: TransactionalSourceCallerGuard,
	private val sourceBroker: SourceBroker,
	private val authorityRepository: SourceCallerAcceptedAuthorityRepository,
) : SourceCallerDemandDispatcher {
	override suspend fun dispatchSession(
		request: SessionSourceDemandDispatchRequest,
	): SessionSourceDemandDispatchResult {
		val manifestIdentity = request.manifest.toCallerIdentity()
		validateManifestBindings(request, manifestIdentity)?.let { return rejected(it) }
		val snapshot = when (val read = readAuthoritySnapshot {
			authorityReader.readCurrentManifest(manifestIdentity)
		}) {
			is CurrentPurposeAuthorityRead.Available -> read.snapshot
			is CurrentPurposeAuthorityRead.Rejected -> return rejected(read.reason)
		}
		val identities = snapshot.currentDemandIdentities
		val callerRequest = sessionCallerRequest(request, manifestIdentity, identities)
			?: return rejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		return when (val result = guard.prepareFreshAcceptance(
			callerRequest,
			snapshot,
			request.wallTimeMs,
		)) {
			is SourceCallerFreshAcceptanceResult.Prepared -> applyPreparedSession(request, identities, result.acceptance)
			is SourceCallerFreshAcceptanceResult.Rejected -> rejected(result.rejection)
		}
	}

	// Ordered receipt, execution and demand checks precede the one accepted mutation.
	@Suppress("ReturnCount")
	private suspend fun applyPreparedSession(
		request: SessionSourceDemandDispatchRequest, identities: Set<SourceCallerDemandIdentity>,
		prepared: PreparedSourceCallerAcceptance,
	): SessionSourceDemandDispatchResult {
		val permitted = prepared.receipt
		if (permitted.permittedDemandIdentities != identities) {
			return rejected(
				SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
			)
		}
		if (permitted.permittedDemandIdentities.any { identity ->
				identity.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE &&
					identity.purposeLeaseIdentity.executionRevision !=
					request.lifecycleLeaseGeneration
			}
		) {
			return rejected(
			SourceCallerRejectionReason.STALE_EXECUTION_REVISION,
		)
		}
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
			policies = request.policies,
		)
		if (!demands.match(permitted)) {
			return rejected(
				SourceCallerRejectionReason.REPLAY_AUTHORITY_MISMATCH,
			)
		}
		persistSessionAcceptance(prepared)?.let { return rejected(it) }
		applySessionDemandMutation(request, demands)
		return SessionSourceDemandDispatchResult.Permitted(permitted)
	}

	private fun sessionCallerRequest(
		request: SessionSourceDemandDispatchRequest,
		manifestIdentity: SourceCallerManifestIdentity,
		identities: Set<SourceCallerDemandIdentity>,
	): SourceCallerRequest? {
		val captureSources = identities.asSequence()
			.filter { it.sourcePurpose.purpose == TrackingPurpose.SESSION_CAPTURE }
			.map { it.sourcePurpose.source }
			.toSet()
		return when {
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
			else -> null
		}
	}

	private suspend fun persistSessionAcceptance(
		prepared: PreparedSourceCallerAcceptance,
	): SourceCallerGuardRejection? {
		val callerAuthorityPersisted = try {
			authorityRepository.insertIfAbsent(
				prepared.receipt.reference,
				prepared.authority,
				prepared.createdAtMs,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
			// The acceptance write reports storage/invariant failure before broker demand mutation.
			return SourceCallerGuardRejection(
				if (failure.isTrackingOperationalFailure()) {
					SourceCallerRejectionReason.AUTHORITY_PERSISTENCE_UNAVAILABLE
				} else {
					SourceCallerRejectionReason.AUTHORITY_INVARIANT_VIOLATION
				},
			)
		}
		return if (callerAuthorityPersisted) {
			null
		} else {
			SourceCallerGuardRejection(SourceCallerRejectionReason.AUTHORITY_PERSISTENCE_UNAVAILABLE)
		}
	}

	private suspend fun applySessionDemandMutation(
		request: SessionSourceDemandDispatchRequest,
		demands: List<SourceDemandEntity>,
	) {
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
					stepsContinuation = request.stepsContinuation,
				)
			SessionDemandMutation.AUTHORITY_ONLY -> Unit
		}
	}

	override suspend fun replayPreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult = authenticatePreparedSession(
		manifestIdentity,
		reference,
		replayKind.canonicalKind,
	).also { result ->
		if (result is SourceCallerGuardResult.Rejected) {
			logRejected()
		}
	}

	override suspend fun authenticatePreparedSession(
		manifestIdentity: SourceCallerManifestIdentity,
		reference: SourceCallerReplayReference,
		replayKind: SourceCallerReplayKind,
	): SourceCallerGuardResult {
		val canonicalReplayKind = replayKind.canonicalKind
		if (canonicalReplayKind == SourceCallerReplayKind.POLICY_RECONCILIATION) {
			return SourceCallerGuardResult.Rejected(
				SourceCallerGuardRejection(
					SourceCallerRejectionReason.REPLAY_KIND_REQUIRES_FRESH_ACCEPTANCE,
				),
			)
		}
		val snapshot = when (val read = readAuthoritySnapshot {
			authorityReader.readReplayManifest(manifestIdentity, canonicalReplayKind)
		}) {
			is CurrentPurposeAuthorityRead.Available -> read.snapshot
			is CurrentPurposeAuthorityRead.Rejected -> return SourceCallerGuardResult.Rejected(
				SourceCallerGuardRejection(read.reason),
			)
		}
		return guard.authenticateReplayAgainstSnapshot(
			SourceCallerRequest.Replay(
				replayKind = canonicalReplayKind,
				reference = reference,
				purpose = TrackingPurpose.SESSION_CAPTURE,
				requestedDemandIdentities = snapshot.currentDemandIdentities,
			),
			snapshot,
		)
	}

	// Ordered identity, acceptance and broker failures stay explicit without nested success branches.
	@Suppress("ReturnCount")
	override suspend fun dispatchAutomaticControl(
		request: AutomaticControlDemandDispatchRequest,
	): GuardedPurposeDemandResult<SourceDemandEntity> {
		val identity = request.identity
		if (identity.source != TrackingSource.ACTIVITY ||
			identity.purpose != TrackingPurpose.CONTROL
		) {
			return rejectedPurpose(
			SourceCallerRejectionReason.AUTOMATIC_CONTROL_SET_MISMATCH,
		)
		}
		val prepared = when (val acceptance = preparePurposeAcceptance(
			identity = identity,
			source = TrackingSource.ACTIVITY,
			purpose = TrackingPurpose.CONTROL,
			createdAtMs = request.wallTimeMs,
		)) {
			is GuardedPurposeDemandResult.Applied -> acceptance.value
			is GuardedPurposeDemandResult.Rejected -> return acceptance
			is GuardedPurposeDemandResult.RejectedAfterCleanup ->
				error("Purpose acceptance cannot complete cleanup")
			GuardedPurposeDemandResult.Stale -> return GuardedPurposeDemandResult.Stale
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
			sourceCallerAuthorityReference = prepared.receipt.reference.value,
			preparedCallerAcceptance = prepared,
			purposeLeaseIdentity = identity,
		) ?: return rejectedPurpose(
				SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
			)
		return GuardedPurposeDemandResult.Applied(demand, prepared.receipt)
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

	// Each short rejection preserves the ordered source, retention and acceptance checks.
	@Suppress("ReturnCount")
	override suspend fun dispatchAmbientLocation(
		request: AmbientLocationDemandDispatchRequest,
	): GuardedPurposeDemandResult<AmbientLocationDemandResult> {
		val ambient = request.identity
		val identity = ambient.purposeLeaseIdentity
		if (ambient.source != AmbientTrackingSource.LOCATION) {
			return rejectedPurpose(SourceCallerRejectionReason.AMBIENT_SOURCE_NOT_SUPPORTED)
		}
		val retention = sourceBroker.captureLiveAmbientRetentionSnapshot(
			source = SourceKind.LOCATION, sourcePolicyRevision = identity.policyRevision,
			ambientConsentEpoch = identity.consentEpoch, collectedDataEpoch = identity.collectedDataEpoch,
			currentBootId = request.bootId, currentElapsedRealtimeNanos = request.elapsedRealtimeNanos,
			currentWallTimeMs = request.wallTimeMs, expectedRetainedFromMs = ambient.retainedFromMs,
		) ?: return rejectedPurpose(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		val grant = retention.grants[SourceKind.LOCATION]
		if (grant?.opaquePolicyId != ambient.retentionPolicyId ||
			grant?.approvalRevision != ambient.retentionApprovalRevision ||
			grant?.retainedFromMs != ambient.retainedFromMs
		) {
			return rejectedPurpose(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		}
		val prepared = when (val acceptance = preparePurposeAcceptance(identity,
			TrackingSource.LOCATION, TrackingPurpose.AMBIENT_PRODUCT, request.wallTimeMs)) {
			is GuardedPurposeDemandResult.Applied -> acceptance.value
			is GuardedPurposeDemandResult.Rejected -> return acceptance
			is GuardedPurposeDemandResult.RejectedAfterCleanup -> error("Acceptance cannot complete cleanup")
			GuardedPurposeDemandResult.Stale -> return GuardedPurposeDemandResult.Stale
		}
		val result = sourceBroker.replaceAmbientLocationDemand(ambient, request.bootId,
			request.elapsedRealtimeNanos, request.wallTimeMs, retention, prepared)
		return GuardedPurposeDemandResult.Applied(result,
			prepared.receipt.takeIf { result is AmbientLocationDemandResult.Active })
	}

	override suspend fun dispatchAmbientSteps(
		request: AmbientStepsDemandDispatchRequest,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult> {
		val ambientIdentity = request.identity
		val identity = ambientIdentity.purposeLeaseIdentity
		if (ambientIdentity.source != AmbientTrackingSource.STEPS) {
			return rejectedPurpose(
			SourceCallerRejectionReason.AMBIENT_SOURCE_NOT_SUPPORTED,
		)
		}
		val retentionSnapshot = sourceBroker.captureLiveAmbientRetentionSnapshot(
			source = SourceKind.STEPS,
			sourcePolicyRevision = identity.policyRevision,
			ambientConsentEpoch = identity.consentEpoch,
			collectedDataEpoch = identity.collectedDataEpoch,
			currentBootId = request.bootId,
			currentElapsedRealtimeNanos = request.elapsedRealtimeNanos,
			currentWallTimeMs = request.wallTimeMs,
			expectedRetainedFromMs = ambientIdentity.retainedFromMs,
		)
		if (retentionSnapshot?.grants?.get(SourceKind.STEPS)
			?.let { grant ->
				grant.retainedFromMs == ambientIdentity.retainedFromMs &&
					grant.opaquePolicyId == ambientIdentity.retentionPolicyId &&
					grant.approvalRevision == ambientIdentity.retentionApprovalRevision
			} != true
		) {
			return rejectedPurpose(
				SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE,
			)
		}
		return when (val acceptance = preparePurposeAcceptance(
			identity = identity,
			source = TrackingSource.STEPS,
			purpose = TrackingPurpose.AMBIENT_PRODUCT,
			createdAtMs = request.wallTimeMs,
		)) {
			is GuardedPurposeDemandResult.Applied -> applyPreparedAmbientSteps(request, retentionSnapshot, acceptance.value)
			is GuardedPurposeDemandResult.Rejected -> acceptance
			is GuardedPurposeDemandResult.RejectedAfterCleanup ->
				error("Purpose acceptance cannot complete cleanup")
			GuardedPurposeDemandResult.Stale -> GuardedPurposeDemandResult.Stale
		}
	}

	private suspend fun applyPreparedAmbientSteps(
		request: AmbientStepsDemandDispatchRequest, retentionSnapshot: LiveAmbientRetentionSnapshot?,
		prepared: PreparedSourceCallerAcceptance,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult> {
		val ambientIdentity = request.identity

		val result = sourceBroker.replaceAmbientStepsDemand(
			consumerId = request.consumerId,
			mechanism = request.mechanism,
			leaseIdentity = ambientIdentity,
			bootId = request.bootId,
			elapsedRealtimeNanos = request.elapsedRealtimeNanos,
			wallTimeMs = request.wallTimeMs,
			sourceCallerAuthorityReference = prepared.receipt.reference.value,
			retentionSnapshot = retentionSnapshot,
			preparedCallerAcceptance = prepared,
		)
		if (result is AmbientStepsDemandResult.Inactive) {
			return GuardedPurposeDemandResult.Applied(result, null)
		}
		return GuardedPurposeDemandResult.Applied(result, prepared.receipt)
	}

	override suspend fun retireAmbientSteps(
		consumerId: String,
		leaseIdentity: AmbientReconciliationIdentity?,
		bootId: String,
		elapsedRealtimeNanos: Long,
		wallTimeMs: Long,
	): GuardedPurposeDemandResult<AmbientStepsDemandResult> =
		if (leaseIdentity != null) {
			if (sourceBroker.retireExactAmbientStepsDemand(
					consumerId = consumerId,
					leaseIdentity = leaseIdentity,
					bootId = bootId,
					elapsedRealtimeNanos = elapsedRealtimeNanos,
					wallTimeMs = wallTimeMs,
				)
			) {
				GuardedPurposeDemandResult.Applied(
					AmbientStepsDemandResult.Inactive(
						AmbientStepsDemandInactiveReason.REQUEST_DISABLED,
					),
					null,
				)
			} else {
				GuardedPurposeDemandResult.Stale
			}
		} else {
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
					AmbientStepsDemandResult.Inactive(
						AmbientStepsDemandInactiveReason.REQUEST_DISABLED,
					),
					retired.receipt,
				)
				is GuardedPurposeDemandResult.Rejected -> retired
				is GuardedPurposeDemandResult.RejectedAfterCleanup ->
					error("Purpose retirement cannot complete cleanup")
				GuardedPurposeDemandResult.Stale -> GuardedPurposeDemandResult.Stale
			}
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
				expectedRetainedFromMs = request.leaseIdentity.retainedFromMs,
			)
		} else {
			LiveAmbientRetentionSnapshot(emptyMap())
		}
		val preparedCallerAcceptance = if (request.requested) {
			when (val acceptance = preparePurposeAcceptance(
				identity = request.leaseIdentity.purposeLeaseIdentity,
				source = request.source.canonicalSource,
				purpose = TrackingPurpose.AMBIENT_PRODUCT,
				createdAtMs = request.wallTimeMs,
			)) {
				is GuardedPurposeDemandResult.Applied -> acceptance.value
				is GuardedPurposeDemandResult.Rejected ->
					return reduceAmbientRadioAfterRejectedAuthority(
						request,
						acceptance.rejection,
						reconcile,
					)
				is GuardedPurposeDemandResult.RejectedAfterCleanup ->
					error("Purpose acceptance cannot complete cleanup")
				GuardedPurposeDemandResult.Stale -> return GuardedPurposeDemandResult.Stale
			}
		} else {
			null
		}
		return mutateAmbientRadioLease(request, retentionSnapshot, preparedCallerAcceptance, reconcile)
	}

	private suspend fun <T> mutateAmbientRadioLease(
		request: AmbientRadioDemandDispatchRequest, retentionSnapshot: LiveAmbientRetentionSnapshot?,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance?,
		reconcile: suspend (AmbientRadioDemandResult, GuardedAmbientRadioAttempt) -> T,
	): GuardedPurposeDemandResult<T> {
		val dispatch: suspend () -> GuardedPurposeDemandResult<T> = {
			dispatchAmbientRadioUnderHeldLease(request, retentionSnapshot, preparedCallerAcceptance, reconcile)
		}
		val leaseMutation = if (request.requested) {
			sourceBroker.withAmbientRadioMutationLease(request.leaseIdentity, dispatch)
		} else {
			sourceBroker.withAmbientRadioReductionLease(request.leaseIdentity, dispatch)
		}
		return when (leaseMutation) {
			is AmbientRadioLeaseMutation.Applied -> leaseMutation.value
			AmbientRadioLeaseMutation.Stale -> GuardedPurposeDemandResult.Stale
		}
	}

	private suspend fun <T> dispatchAmbientRadioUnderHeldLease(
		request: AmbientRadioDemandDispatchRequest,
		retentionSnapshot: LiveAmbientRetentionSnapshot?,
		preparedCallerAcceptance: PreparedSourceCallerAcceptance?,
		reconcile: suspend (AmbientRadioDemandResult, GuardedAmbientRadioAttempt) -> T,
	): GuardedPurposeDemandResult<T> {
		val prepared = if (request.requested) {
			requireNotNull(preparedCallerAcceptance) {
				"Requested Ambient radio dispatch requires prepared caller authority"
			}
		} else {
			null
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
					sourceCallerAuthorityReference = prepared?.receipt?.reference?.value,
					retentionSnapshot = retentionSnapshot.takeIf { request.requested },
					preparedCallerAcceptance = prepared,
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
					sourceCallerAuthorityReference = prepared?.receipt?.reference?.value,
					retentionSnapshot = retentionSnapshot.takeIf { request.requested },
					preparedCallerAcceptance = prepared,
				)
			AmbientTrackingSource.STEPS,
			AmbientTrackingSource.LOCATION,
			-> error("Unsupported ambient-radio source passed validation")
		}
		val receipt = prepared?.receipt?.takeIf { result is AmbientRadioDemandResult.Active }
		val attempt = GuardedAmbientRadioAttempt(request, receipt)
		return GuardedPurposeDemandResult.Applied(reconcile(result, attempt), receipt)
	}

	private suspend fun <T> reduceAmbientRadioAfterRejectedAuthority(
		request: AmbientRadioDemandDispatchRequest,
		rejection: SourceCallerGuardRejection,
		reconcile: suspend (AmbientRadioDemandResult, GuardedAmbientRadioAttempt) -> T,
	): GuardedPurposeDemandResult<T> {
		val reduction: AmbientRadioLeaseMutation<GuardedPurposeDemandResult<T>> =
			sourceBroker.withAmbientRadioReductionLease(request.leaseIdentity) {
			val reduced = try {
				when (request.source) {
					AmbientTrackingSource.WIFI ->
						sourceBroker.reduceAmbientWifiAfterRejectedCallerUnderHeldLease(
							consumerId = request.consumerId,
							leaseIdentity = request.leaseIdentity,
							reconciliationAttempt = request.reconciliationAttempt,
							bootId = request.bootId,
							elapsedRealtimeNanos = request.elapsedRealtimeNanos,
							wallTimeMs = request.wallTimeMs,
						)
					AmbientTrackingSource.CELL ->
						sourceBroker.reduceAmbientCellAfterRejectedCallerUnderHeldLease(
							consumerId = request.consumerId,
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
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
				// Only the existing operational filter permits a rejected cleanup result; other failures propagate.
				if (!failure.isTrackingOperationalFailure()) {
					throw failure
				}
				return@withAmbientRadioReductionLease GuardedPurposeDemandResult.Rejected(
					rejection,
				)
			}
			GuardedPurposeDemandResult.RejectedAfterCleanup(
				rejection = rejection,
				cleanupResult = reconcile(
					reduced,
					GuardedAmbientRadioAttempt(request, receipt = null),
				),
			)
		}
		return when (reduction) {
			is AmbientRadioLeaseMutation.Applied -> reduction.value
			AmbientRadioLeaseMutation.Stale -> GuardedPurposeDemandResult.Stale
		}
	}

	override suspend fun compensateAmbientRadio(
		attempt: GuardedAmbientRadioAttempt,
		expectedDemandId: String,
	): AmbientRadioReconciliationAuthority? {
		val expectedCallerAuthorityReference = attempt.receipt?.reference ?: return null
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
				expectedCallerAuthorityReference,
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
				expectedCallerAuthorityReference,
			)
		AmbientTrackingSource.STEPS,
		AmbientTrackingSource.LOCATION,
		-> null
		}
		return compensated
	}

	// Ordered saved-authority and current-manifest rejection guards have one final demand comparison.
	@Suppress("ReturnCount")
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
		if (accepted.purpose != TrackingPurpose.SESSION_CAPTURE) {
			return false
		}
		val current = authorityReader.readCurrentManifest(manifestIdentity) ?: return false
		if (accepted.permittedDemandIdentities != current.currentDemandIdentities) {
			return false
		}
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

	private suspend fun readAuthoritySnapshot(
		read: suspend () -> SourceCallerAuthoritySnapshot?,
	): CurrentPurposeAuthorityRead = try {
		val snapshot = read()
		if (snapshot == null) {
			CurrentPurposeAuthorityRead.Rejected(SourceCallerRejectionReason.DEMAND_AUTHORITY_UNAVAILABLE)
		} else {
			CurrentPurposeAuthorityRead.Available(snapshot)
		}
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (@Suppress("TooGenericExceptionCaught") failure: Exception) {
		// Snapshot reads preserve storage/invariant rejection without granting caller authority.
		CurrentPurposeAuthorityRead.Rejected(failure.toAuthorityReadRejectionReason())
	}

	private suspend fun preparePurposeAcceptance(
		identity: TrackingPurposeLeaseIdentity,
		source: TrackingSource,
		purpose: TrackingPurpose,
		createdAtMs: Long,
	): GuardedPurposeDemandResult<PreparedSourceCallerAcceptance> {
		val demandIdentity = SourceCallerDemandIdentity(identity, manifestIdentity = null)
		val snapshot = when (val read = readAuthoritySnapshot {
			authorityReader.readCurrentPurpose(setOf(demandIdentity))
		}) {
			is CurrentPurposeAuthorityRead.Available -> read.snapshot
			is CurrentPurposeAuthorityRead.Rejected -> return rejectedPurpose(read.reason)
		}
		if (demandIdentity !in snapshot.currentDemandIdentities) {
			return rejectedPurpose(SourceCallerRejectionReason.READINESS_AUTHORITY_MISMATCH)
		}
		return when (val prepared = guard.prepareFreshAcceptance(
			SourceCallerRequest.PurposeOwnerMutation(
				source = source,
				purpose = purpose,
				enabled = true,
				requestedDemandIdentities = setOf(demandIdentity),
			),
			snapshot,
			createdAtMs,
		)) {
			is SourceCallerFreshAcceptanceResult.Prepared ->
				GuardedPurposeDemandResult.Applied(prepared.acceptance, receipt = null)
			is SourceCallerFreshAcceptanceResult.Rejected ->
				rejectedPurpose(prepared.rejection)
		}
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
		if (hasConflictingManifestBindings(request, manifestIdentity)) {
			return SourceCallerGuardRejection(SourceCallerRejectionReason.SESSION_MANIFEST_MISMATCH)
		}
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

	private fun hasConflictingManifestBindings(
		request: SessionSourceDemandDispatchRequest, manifestIdentity: SourceCallerManifestIdentity,
	): Boolean = request.manifest.sessionMode != request.sessionMode.name ||
			request.manifest.startOrigin != request.startOrigin.name ||
			request.bindings.isEmpty() ||
			request.bindings.any { binding ->
				binding.logicalTrackingId != manifestIdentity.logicalTrackingId ||
					binding.manifestRevision != manifestIdentity.manifestRevision
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

private sealed interface CurrentPurposeAuthorityRead {
	data class Available(
		val snapshot: SourceCallerAuthoritySnapshot,
	) : CurrentPurposeAuthorityRead

	data class Rejected(
		val reason: SourceCallerRejectionReason,
	) : CurrentPurposeAuthorityRead
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
	private val startupGateProvider: Provider<TrackingStartupGate>? = null,
) : SourceCallerAuthoritySnapshotReader, CurrentSourceCallerAuthorityProvider {
	override suspend fun read(request: SourceCallerRequest): SourceCallerAuthoritySnapshot {
		val manifests = request.requestedDemandIdentities.mapNotNull { it.manifestIdentity }.toSet()
		if (manifests.size > 1) {
			return unavailableSnapshot()
		}
		val manifest = manifests.singleOrNull()
		return if (manifest == null) {
			readSessionless(request.requestedDemandIdentities)
		} else if (request is SourceCallerRequest.Replay) {
			readReplayManifest(manifest, request.replayKind.canonicalKind) ?: unavailableSnapshot()
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
		allowPendingManualStart = true,
	)

	override suspend fun readReplayManifest(
		identity: SourceCallerManifestIdentity,
		replayKind: SourceCallerReplayKind,
	): SourceCallerAuthoritySnapshot? {
		val canonicalReplayKind = replayKind.canonicalKind
		return readCurrentManifest(
			identity = identity,
			requireLiveLease =
				canonicalReplayKind == SourceCallerReplayKind.FOREGROUND_SERVICE_DELIVERY,
			allowSuspendedSession =
				canonicalReplayKind == SourceCallerReplayKind.ACTIVE_REDELIVERY ||
					canonicalReplayKind == SourceCallerReplayKind.PROCESS_RECOVERY,
			allowPendingManualStart = canonicalReplayKind == SourceCallerReplayKind.FOREGROUND_SERVICE_DELIVERY,
		)
	}

	private suspend fun readCurrentManifest(
		identity: SourceCallerManifestIdentity,
		requireLiveLease: Boolean,
		allowSuspendedSession: Boolean,
		allowPendingManualStart: Boolean,
	): SourceCallerAuthoritySnapshot? {
		if (startupGateProvider?.get()?.permitsNewCollection == false && !allowPendingManualStart) {
			return null
		}
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
					allowPendingManualStart,
				)
			} ?: return null
			if (availabilityBefore == purposeAvailabilityReader.availability.value) {
				return current
			}
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
		allowPendingManualStart: Boolean,
	): SourceCallerAuthoritySnapshot? {
		val header = readManifestHeader(identity, currentBootId, allowSuspendedSession, allowPendingManualStart)
			?: return null
		val authority = readManifestCollectionAuthority(header, currentBootId, requireLiveLease)
			?: return null
		val bindings = database.sourceSessionDao().manifestSources(identity.logicalTrackingId, identity.manifestRevision)
		if (bindings.isEmpty() || !SessionManifestIntegrity.verify(header.manifest, bindings)) {
			return null
		}
		return readManifestDemandIdentities(identity, bindings, availability, authority)?.let { demands ->
			SourceCallerAuthoritySnapshot(
				currentDemandIdentities = demands,
				purposeAvailability = availability.toSnapshot(),
				pendingManualStartGeneration = header.pendingManualStartGeneration,
			)
		}
	}

	/** Reads the session and manifest before considering the one-use manual Start exception. */
	private suspend fun readManifestHeader(
		identity: SourceCallerManifestIdentity,
		currentBootId: String,
		allowSuspendedSession: Boolean,
		allowPendingManualStart: Boolean,
	): CurrentManifestHeader? {
		val sessionDao = database.sourceSessionDao()
		val session = sessionDao.session(identity.logicalTrackingId)?.takeIf {
			it.currentManifestRevision == identity.manifestRevision &&
				it.lifecycleBootId == currentBootId &&
				it.state in setOf(SessionLifecycleState.STARTING.name, SessionLifecycleState.ACTIVE.name,
					SessionLifecycleState.RECONFIGURING.name)
		} ?: return null
		val manifest = sessionDao.manifest(identity.logicalTrackingId, identity.manifestRevision)?.takeIf {
			it.matchesCurrentSession(session, currentBootId, allowSuspendedSession)
		} ?: return null
		val gate = startupGateProvider?.get()
		val pendingGeneration = if (gate?.permitsNewCollection == false) {
			readPendingManualStartGeneration(identity, session, manifest, gate, currentBootId, allowPendingManualStart)
				?: return null
		} else {
			null
		}
		return CurrentManifestHeader(session, manifest, pendingGeneration)
	}

	private suspend fun readPendingManualStartGeneration(
		identity: SourceCallerManifestIdentity,
		session: LogicalTrackingSessionEntity,
		manifest: SessionManifestVersionEntity,
		gate: TrackingStartupGate,
		currentBootId: String,
		allowPendingManualStart: Boolean,
	): Long? {
		if (!allowPendingManualStart || session.state != SessionLifecycleState.STARTING.name ||
			manifest.startOrigin != SessionStartOrigin.MANUAL_FOREGROUND_START.name) {
			return null
		}
		val runId = session.currentServiceRunId ?: return null
		val run = database.sourceSessionDao().serviceRun(runId) ?: return null
		return run.startCommandGeneration.takeIf { generation ->
			generation > 0L && run.matchesPendingManualOwner(session, manifest, currentBootId) &&
				run.isPreparedManualStart(identity) && gate.permitsPendingManualStart(generation)
		}
	}

	/** Keeps policy, deletion epoch, rollout and coordinator lease reads in their original order. */
	private suspend fun readManifestCollectionAuthority(
		header: CurrentManifestHeader,
		currentBootId: String,
		requireLiveLease: Boolean,
	): CurrentManifestCollectionAuthority? {
		val policyAuthority = database.sourcePolicyDao().authority()?.takeIf {
			it.bootstrapState == SourcePolicyAuthorityEntity.STATE_ACTIVE &&
				it.currentPolicyRevision == header.manifest.sourcePolicyRevision
		} ?: return null
		val evidenceEpoch = database.sourceEvidenceStateDao().get()?.collectedDataEpoch ?: return null
		val rollout = rolloutStateStore.load().takeIf { it.revision == header.manifest.rolloutRevision }
			?: return null
		return readManifestLease(header.session, currentBootId, requireLiveLease)?.let { lease ->
			CurrentManifestCollectionAuthority(policyAuthority.currentPolicyRevision, evidenceEpoch, rollout.revision, lease)
		}
	}

	private suspend fun readManifestLease(
		session: LogicalTrackingSessionEntity,
		currentBootId: String,
		requireLiveLease: Boolean,
	): SourceCoordinatorLeaseEntity? = database.sourceProjectionStateDao().lease(SESSION_LEASE_NAME)?.takeIf {
		it.belongsToCurrentSession(session, currentBootId) &&
			(!requireLiveLease || it.expiresElapsedRealtimeNanos > SystemClock.elapsedRealtimeNanos())
	}

	private suspend fun readManifestDemandIdentities(
		identity: SourceCallerManifestIdentity,
		bindings: List<SessionManifestSourceEntity>,
		availability: CurrentTrackingPurposeAvailability,
		authority: CurrentManifestCollectionAuthority,
	): Set<SourceCallerDemandIdentity>? {
		val policies = database.sourcePolicyDao().policiesAtRevision(authority.policyRevision).associateBy { it.sourceKind }
		val demands = linkedSetOf<SourceCallerDemandIdentity>()
		for (binding in bindings) {
			val source = TrackingSource.fromStableCode(binding.sourceKind)
			val policy = policies[binding.sourceKind] ?: return null
			val demand = when (binding.purpose) {
				SourceBrokerPurpose.SESSION_CAPTURE -> readCaptureDemandIdentity(identity, binding, source, policy, authority)
				SessionManifestPurpose.CONTROL.name -> {
					val ready = availability.automaticControl as? AutomaticTrackingOperationalAvailability.Ready ?: continue
					readControlDemandIdentity(binding, source, policy, ready, authority)
				}
				else -> null
			} ?: return null
			demands += demand
		}
		return demands
	}

	private fun readCaptureDemandIdentity(
		identity: SourceCallerManifestIdentity,
		binding: SessionManifestSourceEntity,
		source: TrackingSource,
		policy: SourcePolicyEntity,
		authority: CurrentManifestCollectionAuthority,
	): SourceCallerDemandIdentity? {
		if (!policy.enabled || !policy.capturePersistenceEligible || policy.captureConsentEpoch != binding.consentEpoch) {
			return null
		}
		return SourceCallerDemandIdentity(
			purposeLeaseIdentity = TrackingPurposeLeaseIdentity(
				sourcePurpose = source.forPurpose(TrackingPurpose.SESSION_CAPTURE),
				policyRevision = authority.policyRevision,
				consentEpoch = binding.consentEpoch,
				collectedDataEpoch = authority.evidenceEpoch,
				rolloutRevision = authority.rolloutRevision,
				executionRevision = authority.lease.generation,
				ownerCasToken = authority.lease.ownerToken,
			),
			manifestIdentity = identity,
		)
	}

	private suspend fun readControlDemandIdentity(
		binding: SessionManifestSourceEntity,
		source: TrackingSource,
		policy: SourcePolicyEntity,
		ready: AutomaticTrackingOperationalAvailability.Ready,
		authority: CurrentManifestCollectionAuthority,
	): SourceCallerDemandIdentity? {
		if (source != TrackingSource.ACTIVITY || policy.controlConsentEpoch != binding.consentEpoch ||
			!ready.identity.matchesManifestControl(source, binding.consentEpoch, authority) ||
			!purposeAvailabilityReader.isCurrent(ready.identity)) {
			return null
		}
		return SourceCallerDemandIdentity(ready.identity, manifestIdentity = null)
	}

	private suspend fun readSessionless(
		requested: Set<SourceCallerDemandIdentity>,
	): SourceCallerAuthoritySnapshot {
		if (startupGateProvider?.get()?.permitsNewCollection == false) {
			return unavailableSnapshot()
		}
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
			if (registeredIdentity == lease && registeredExecution == lease.executionRevision &&
				observed.matchesRegisteredLease(lease)) {
				identities += requestedIdentity
			}
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

private data class CurrentManifestHeader(
	val session: LogicalTrackingSessionEntity,
	val manifest: SessionManifestVersionEntity,
	val pendingManualStartGeneration: Long?,
)

private data class CurrentManifestCollectionAuthority(
	val policyRevision: Long,
	val evidenceEpoch: Long,
	val rolloutRevision: Long,
	val lease: SourceCoordinatorLeaseEntity,
)

private fun SessionManifestVersionEntity.matchesCurrentSession(
	session: LogicalTrackingSessionEntity,
	currentBootId: String,
	allowSuspendedSession: Boolean,
): Boolean = rolloutRevision == session.rolloutRevision && effectiveBootId == session.lifecycleBootId &&
	effectiveBootId == currentBootId && matchesCurrentRun(session, allowSuspendedSession)

private fun SessionManifestVersionEntity.matchesCurrentRun(
	session: LogicalTrackingSessionEntity,
	allowSuspendedSession: Boolean,
): Boolean = serviceRunId == session.currentServiceRunId ||
	(allowSuspendedSession && session.state == SessionLifecycleState.ACTIVE.name && session.currentServiceRunId == null)

private fun SourceServiceRunEntity.matchesPendingManualOwner(
	session: LogicalTrackingSessionEntity,
	manifest: SessionManifestVersionEntity,
	currentBootId: String,
): Boolean = matchesStartingManifestRun(session, manifest) && bootId == currentBootId &&
	leaseGeneration == session.lifecycleLeaseGeneration

private fun SourceServiceRunEntity.matchesStartingManifestRun(
	session: LogicalTrackingSessionEntity,
	manifest: SessionManifestVersionEntity,
): Boolean = logicalTrackingId == session.logicalTrackingId && serviceRunId == manifest.serviceRunId &&
	state == SessionLifecycleState.STARTING.name && completedAtMs == null

private fun SourceServiceRunEntity.isPreparedManualStart(identity: SourceCallerManifestIdentity): Boolean =
	startOrigin == SessionStartOrigin.MANUAL_FOREGROUND_START.name && startIsUserInitiated &&
		!startIsAmbient && preparedManifestRevision == identity.manifestRevision

private fun SourceCoordinatorLeaseEntity.belongsToCurrentSession(
	session: LogicalTrackingSessionEntity,
	currentBootId: String,
): Boolean = ownerToken.isNotBlank() && generation == session.lifecycleLeaseGeneration &&
	bootId == session.lifecycleBootId && bootId == currentBootId

private fun TrackingPurposeLeaseIdentity.matchesManifestControl(
	source: TrackingSource,
	bindingConsentEpoch: Long,
	authority: CurrentManifestCollectionAuthority,
): Boolean = matchesControlSelection(source, bindingConsentEpoch, authority.policyRevision) &&
	collectedDataEpoch == authority.evidenceEpoch && rolloutRevision == authority.rolloutRevision

private fun TrackingPurposeLeaseIdentity.matchesControlSelection(
	bindingSource: TrackingSource,
	bindingConsentEpoch: Long,
	bindingPolicyRevision: Long,
): Boolean = source == bindingSource && purpose == TrackingPurpose.CONTROL &&
	policyRevision == bindingPolicyRevision && consentEpoch == bindingConsentEpoch

private fun TrackingPurposeAuthoritySnapshot.matchesRegisteredLease(lease: TrackingPurposeLeaseIdentity): Boolean =
	matchesRecordedPurposeLease(lease) && rolloutRevision == lease.rolloutRevision &&
		executionRevision == lease.executionRevision

private fun TrackingPurposeAuthoritySnapshot.matchesRecordedPurposeLease(lease: TrackingPurposeLeaseIdentity): Boolean =
	sourcePurpose == lease.sourcePurpose && policyRevision == lease.policyRevision &&
		consentEpoch == lease.consentEpoch && collectedDataEpoch == lease.collectedDataEpoch

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
		if (dao.rows(reference.value).isNotEmpty()) {
			return@withTransaction false
		}
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
		authenticateStoredSourceCallerAuthority(
			database.sourceCallerAuthorityDao().rows(reference.value),
			reference,
		)
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
		) {
			return@withTransaction false
		}
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
		if (rows.isEmpty()) {
			return@withTransaction true
		}
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

private fun Throwable.toAuthorityReadRejectionReason(): SourceCallerRejectionReason =
	if (isTrackingOperationalFailure()) {
		SourceCallerRejectionReason.AUTHORITY_STORAGE_UNAVAILABLE
	} else {
		SourceCallerRejectionReason.AUTHORITY_INVARIANT_VIOLATION
	}

internal fun authenticateStoredSourceCallerAuthority(
	rows: List<SourceCallerAcceptedAuthorityEntity>,
	reference: SourceCallerReplayReference,
): StoredSourceCallerAuthorityLoadResult {
	if (rows.isEmpty()) {
		return StoredSourceCallerAuthorityLoadResult.Missing
	}
	if (!rows.hasValidStoredAuthorityShape() ||
		!SourceCallerAcceptedAuthorityEffectChecksum.isAuthentic(rows) ||
		hasConflictingStoredAuthorityHeader(rows, reference)
	) {
		return StoredSourceCallerAuthorityLoadResult.Corrupt
	}
	return when {
		rows.all { it.status == SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED } ->
			StoredSourceCallerAuthorityLoadResult.Retired
		rows.any { it.status != SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE } ->
			StoredSourceCallerAuthorityLoadResult.Corrupt
		else -> decodeStoredSourceCallerAuthority(rows)
	}
}

private fun decodeStoredSourceCallerAuthority(
	rows: List<SourceCallerAcceptedAuthorityEntity>,
): StoredSourceCallerAuthorityLoadResult {
	return try {
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

private fun hasConflictingStoredAuthorityHeader(
	rows: List<SourceCallerAcceptedAuthorityEntity>,
	reference: SourceCallerReplayReference,
): Boolean = rows.any { it.reference != reference.value } ||
	rows.map { it.formatVersion }.toSet() !=
		setOf(SourceCallerAcceptedAuthorityEntity.FORMAT_VERSION) ||
	rows.map { it.origin }.distinct().size != 1 ||
	rows.map { it.acceptedPurpose }.distinct().size != 1 ||
	rows.map { it.createdAtMs }.distinct().size != 1 ||
	rows.map { it.retiredAtMs }.distinct().size != 1 ||
	rows.map { it.retireReason }.distinct().size != 1

// Keep the complete saved authority row shape together; this predicate performs no reads or transitions.
@Suppress("CyclomaticComplexMethod")
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
			(row.retainedFromMs?.let { it >= 0L } ?: true) &&
			row.rolloutRevision >= 0L &&
			row.executionRevision > 0L &&
			row.ownerCasToken.isNotBlank() &&
			(row.logicalTrackingId == null) == (row.manifestRevision == null) &&
			(row.logicalTrackingId?.isNotBlank() ?: true) &&
			(row.manifestRevision?.let { it > 0L } ?: true) &&
			row.status in setOf(
				SourceCallerAcceptedAuthorityEntity.STATUS_ACTIVE,
				SourceCallerAcceptedAuthorityEntity.STATUS_RETIRED,
			) &&
			row.createdAtMs >= 0L &&
			(row.retiredAtMs == null) == (row.retireReason == null) &&
			(row.retiredAtMs?.let { it >= row.createdAtMs } ?: true) &&
			(row.retireReason?.isNotBlank() ?: true) &&
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
	if (permitted.size != size) {
		return false
	}
	return all { demand ->
		val source = TrackingSource.entries.singleOrNull { it.stableCode == demand.sourceKind }
			?: return@all false
		val purpose = when (demand.purpose) {
			SourceBrokerPurpose.SESSION_CAPTURE -> TrackingPurpose.SESSION_CAPTURE
			SourceBrokerPurpose.CONTROL_CONTINUATION -> TrackingPurpose.CONTROL
			else -> return@all false
		}
		val identity = permitted[source.forPurpose(purpose)] ?: return@all false
		demand.matchesCallerIdentity(identity, receipt.reference.value, purpose)
	}
}

private fun SourceDemandEntity.matchesCallerIdentity(
	identity: SourceCallerDemandIdentity, reference: String, purpose: TrackingPurpose,
): Boolean {
	return sourceCallerAuthorityReference == reference &&
		identity.purposeLeaseIdentity.policyRevision == sourcePolicyRevision &&
		identity.purposeLeaseIdentity.consentEpoch == consentEpoch &&
		if (purpose == TrackingPurpose.SESSION_CAPTURE) {
			val manifestIdentity = identity.manifestIdentity ?: return false
			manifestIdentity.logicalTrackingId == logicalTrackingId &&
				manifestIdentity.manifestRevision == manifestRevision
		} else {
			identity.manifestIdentity == null
		}
}
