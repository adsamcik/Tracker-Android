package com.adsamcik.tracker.tracker.source.activity

import com.adsamcik.tracker.shared.base.database.SavedSessionCutoff
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceCaptureAdmissionBarrierEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.LogicalTrackingId
import com.adsamcik.tracker.tracker.source.model.ServiceRunId
import com.adsamcik.tracker.tracker.source.model.SourceInstanceId

internal fun ActivityCapturedRegistrationPlanEntity.matchesCapturedActivityPlan(
	desiredPlan: SourceDesiredPlanEntity,
	selected: SourceEventWalEntity,
): Boolean = configurationRevision == selected.configRevision &&
	desiredPlanPayloadVersion == desiredPlan.payloadVersion &&
	desiredPlanPayload.contentEquals(desiredPlan.payload) &&
	desiredPlanPayloadChecksum == desiredPlan.payloadChecksum &&
	physicalConfigurationFingerprint == selected.physicalConfigurationFingerprint &&
	appliedAtElapsedRealtimeNanos <= selected.observedElapsedNanos

internal fun SourceEventWalEntity.toCapturedActivityAuthority(
	temporalAuthority: ActivityCaptureTemporalAuthority,
): ActivityCaptureAuthority = ActivityCaptureAuthority(
	logicalTrackingId = LogicalTrackingId(requireNotNull(logicalTrackingId)),
	serviceRunId = ServiceRunId(requireNotNull(serviceRunId)),
	sourceInstanceId = SourceInstanceId(sourceInstanceId),
	registrationGeneration = registrationGeneration,
	configurationRevision = requireNotNull(configRevision),
	physicalConfigurationFingerprint = requireNotNull(physicalConfigurationFingerprint),
	authorizationRevision = requireNotNull(authorizationRevision),
	authorizationFingerprint = requireNotNull(authorizationFingerprint),
	purposeEligibilityMask = authorizationPurposeEligibilityMask,
	sourcePolicyRevision = requireNotNull(sourcePolicyRevision),
	captureConsentEpoch = requireNotNull(captureConsentEpoch),
	sessionManifestRevision = requireNotNull(sessionManifestRevision),
	lifecycleLeaseGeneration = requireNotNull(lifecycleLeaseGeneration),
	collectedDataEpoch = capturedCollectedDataEpoch,
	clockDomainId = clockDomainId,
	temporalAuthority = temporalAuthority,
)

internal fun ActivityCaptureAuthority.withHistoricalActivityConfiguration(
	desiredPlan: SourceDesiredPlanEntity,
	planCodec: SourcePlanCodec,
): ActivityCaptureAcquisitionAuthority = ActivityCaptureAcquisitionAuthority(
	captureAuthority = this,
	historicalConfiguration = ActivityHistoricalAcquisitionConfiguration.fromSerializedPlan(
		identity = ActivityAcquisitionConfigurationIdentity(
			sourceInstanceId = sourceInstanceId,
			registrationGeneration = registrationGeneration,
			configurationRevision = configurationRevision,
			physicalConfigurationFingerprint = physicalConfigurationFingerprint,
			authorizationRevision = authorizationRevision,
			authorizationFingerprint = authorizationFingerprint,
		),
		providerAcceptance = temporalAuthority.providerAcceptance,
		authorizationEffect = temporalAuthority.authorizationEffect,
		sessionRunEffect = temporalAuthority.sessionRunEffect,
		desiredPlanPayloadVersion = desiredPlan.payloadVersion,
		desiredPlanPayloadChecksum = desiredPlan.payloadChecksum,
		desiredPlanPayload = desiredPlan.payload,
		planCodec = planCodec,
	),
)

internal fun SourceCaptureAdmissionBarrierEntity?.hasSettledActivityTail(
	registration: ProviderRegistrationGenerationEntity,
	selected: SourceEventWalEntity,
	maximumDeliveryOrdinal: Long,
	maximumDeliverySequence: Long,
): Boolean {
	if (this == null || registration.status != ProviderRegistrationGenerationEntity.STATUS_RETIRED) {
		return false
	}
	val authorizationRevision = selected.authorizationRevision ?: return false
	if (sourceInstanceId != selected.sourceInstanceId || throughAuthorizationRevision < authorizationRevision ||
		registration.captureCallbackBarrierAuthorizationRevision < throughAuthorizationRevision
	) {
		return false
	}
	return lastAdmissionOrdinal >= maximumDeliveryOrdinal && lastSourceSequence >= maximumDeliverySequence &&
		sealedElapsedRealtimeNanos >= selected.receivedElapsedNanos
}

internal fun LogicalTrackingSessionEntity.capturedActivitySessionEnd(
	savedCutoff: SavedSessionCutoff,
	measurementBootId: String,
	interruptedSealSettled: Boolean,
): Long? = when {
	savedCutoff.bootId == measurementBootId -> savedCutoff.elapsedNanos
	savedCutoff.bootId == null && lifecycleBootId == measurementBootId -> cutoffElapsedNanos
	else -> null
} ?: Long.MAX_VALUE.takeIf { interruptedSealSettled }

@Suppress("ReturnCount") // Keep ordered missing-reference and rejection exits together.
internal fun LogicalTrackingSessionEntity.hasValidLifecycleShape(
	maximumAdmissionOrdinal: Long,
	run: SourceServiceRunEntity,
): Boolean {
	val interruptedTerminalRun = isInterruptedTerminalRun(run)
	if (!hasValidLifecycleHeader(interruptedTerminalRun)) {
		return false
	}
	val terminal = state in ACTIVITY_CAPTURED_TERMINAL_STATES
	val stoppingRelationship = run.state == LIFECYCLE_STOPPING &&
		run.serviceRunId == currentServiceRunId &&
		state in SETTLED_STOPPING_SESSION_STATES
	if ((completedAtMs != null) != terminal ||
		completedAtMs?.let { completed -> completed < 0L } == true
	) {
		return false
	}
	if (!hasValidLifecycleCutoff(terminal, stoppingRelationship, interruptedTerminalRun)) {
		return false
	}
	return hasValidLifecycleOwnership(
		terminal, stoppingRelationship, run, maximumAdmissionOrdinal, interruptedTerminalRun,
	)
}

// Fixed persisted session header: IDs/revisions, original start and paired optional cutoff columns.
@Suppress("CyclomaticComplexMethod", "ComplexCondition")
private fun LogicalTrackingSessionEntity.hasValidLifecycleHeader(interruptedTerminalRun: Boolean): Boolean =
	state in ALL_LIFECYCLE_STATES && lifecycleRevision > 0L && desiredPlanRevision > 0L &&
	startedAtMs >= 0L && startedElapsedNanos >= 0L && lifecycleLeaseGeneration > 0L &&
	!lifecycleBootId.isNullOrBlank() && currentManifestRevision?.let { it <= 0L } == false &&
	((cutoffAtMs == null) == (cutoffElapsedNanos == null) || interruptedTerminalRun)

private fun LogicalTrackingSessionEntity.hasValidLifecycleCutoff(
	terminal: Boolean,
	stoppingRelationship: Boolean,
	interruptedTerminalRun: Boolean,
): Boolean = if (terminal || state == LIFECYCLE_STOPPING) {
	cutoffAtMs?.let { it < 0L } == false &&
		(cutoffElapsedNanos?.let { it < startedElapsedNanos } == false || interruptedTerminalRun)
} else {
	stoppingRelationship || (cutoffAtMs == null && cutoffElapsedNanos == null)
}

private fun LogicalTrackingSessionEntity.hasValidLifecycleOwnership(
	terminal: Boolean,
	stoppingRelationship: Boolean,
	run: SourceServiceRunEntity,
	maximumAdmissionOrdinal: Long,
	interruptedTerminalRun: Boolean,
): Boolean {
	val finalOrdinal = finalAdmissionOrdinal
	return when {
		terminal -> currentServiceRunId == null &&
			(finalOrdinal?.let { it >= maximumAdmissionOrdinal } ?: interruptedTerminalRun)
		stoppingRelationship -> currentServiceRunId == run.serviceRunId &&
			(finalOrdinal == null || finalOrdinal >= maximumAdmissionOrdinal)
		else -> !currentServiceRunId.isNullOrBlank() && finalOrdinal == null
	}
}

// These existing finalizers cannot recover an elapsed Stop or whole-session admission high-water.
// Admission below still requires this run's exact retired registration seal and original wall bound.
internal fun LogicalTrackingSessionEntity.isInterruptedTerminalRun(run: SourceServiceRunEntity): Boolean {
	if (state !in ACTIVITY_CAPTURED_TERMINAL_STATES || run.state !in ACTIVITY_CAPTURED_TERMINAL_STATES ||
		currentServiceRunId != null
	) {
		return false
	}
	if (failureCode !in INTERRUPTED_COMPLETION_REASONS || run.completionReason != failureCode) {
		return false
	}
	return cutoffAtMs?.let { it >= 0L } == true && cutoffElapsedNanos == null
}

// Missing saved cutoff, final ordinal or requested state cannot establish the stopping window.
@Suppress("ReturnCount")
internal fun LogicalTrackingSessionEntity.isExactSettledStoppingWindow(
	run: SourceServiceRunEntity,
	intent: SessionLifecycleIntentVersionEntity?,
	maximumAdmissionOrdinal: Long,
): Boolean {
	val cutoffElapsed = cutoffElapsedNanos ?: return false
	val cutoffWall = cutoffAtMs ?: return false
	val finalOrdinal = finalAdmissionOrdinal ?: return false
	val expectedDesiredState = when (state) {
		LIFECYCLE_STOPPING -> "FINALIZED"
		LIFECYCLE_ACTIVE -> "ACTIVE"
		else -> return false
	}
	return run.matchesStoppingWindow(this, maximumAdmissionOrdinal, finalOrdinal) &&
		intent.matchesStoppingWindow(this, run, expectedDesiredState, cutoffElapsed, cutoffWall)
}

// Exact saved current run and final-admission correspondence for this stopping settlement.
@Suppress("ComplexCondition")
private fun SourceServiceRunEntity.matchesStoppingWindow(
	session: LogicalTrackingSessionEntity,
	maximumAdmissionOrdinal: Long,
	finalOrdinal: Long,
): Boolean = state == LIFECYCLE_STOPPING && serviceRunId == session.currentServiceRunId &&
	logicalTrackingId == session.logicalTrackingId && completedAtMs == null &&
	finalOrdinal >= maximumAdmissionOrdinal

// Exact immutable saved intent tuple; it does not perform a transition or consult current state.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun SessionLifecycleIntentVersionEntity?.matchesStoppingWindow(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	expectedDesiredState: String,
	cutoffElapsed: Long,
	cutoffWall: Long,
): Boolean = this != null && logicalTrackingId == session.logicalTrackingId &&
	intentRevision == session.currentIntentRevision && manifestRevision == session.currentManifestRevision &&
	desiredState == expectedDesiredState && requestBootId == run.bootId &&
	requestedElapsedRealtimeNanos == cutoffElapsed && requestedWallTimeMs == cutoffWall &&
	!stopReason.isNullOrBlank()

internal fun SourceSessionCompletenessEntity.isExactSettledActivityTail(
	maximumAdmissionOrdinal: Long,
	maximumSourceSequence: Long,
): Boolean = appDrainComplete &&
	stopStatus in SETTLED_STOP_STATUSES &&
	unresolvedSequenceStart == null &&
	unresolvedSequenceEnd == null &&
	lastAdmissionOrdinal?.let { it >= maximumAdmissionOrdinal } == true &&
	lastSourceSequence?.let { it >= maximumSourceSequence } == true

// Validate the complete immutable service-run row and its terminal completion correspondence together.
@Suppress("ComplexCondition", "CyclomaticComplexMethod")
internal fun SourceServiceRunEntity.hasValidLifecycleShape(): Boolean {
	if (state !in ALL_LIFECYCLE_STATES || serviceRunId.isBlank() || logicalTrackingId.isBlank() ||
		startedAtMs < 0L || startedElapsedNanos < 0L || leaseGeneration <= 0L ||
		bootId.isBlank() || runRevision <= 0L
	) {
		return false
	}
	val terminal = state in ACTIVITY_CAPTURED_TERMINAL_STATES
	return (completedAtMs != null) == terminal &&
		completedAtMs?.let { completed -> completed >= 0L } != false
}

internal fun SourceServiceRunEntity.hasValidRelationshipTo(
	session: LogicalTrackingSessionEntity,
): Boolean = when {
	logicalTrackingId != session.logicalTrackingId -> false
	state !in ACTIVITY_CAPTURED_TERMINAL_STATES ->
		session.currentServiceRunId == serviceRunId &&
			(session.state to state) in LIVE_SESSION_RUN_STATE_PAIRS
	session.state in ACTIVITY_CAPTURED_TERMINAL_STATES -> session.currentServiceRunId == null
	else -> true
}

internal fun SourceEventWalEntity.latestPossibleWallTimeMs(): Long? {
	val wall = wallTimeMs?.takeIf { it >= 0L } ?: return null
	val uncertainty = wallTimeUncertaintyMs?.takeIf { it >= 0L } ?: return null
	return if (uncertainty > Long.MAX_VALUE - wall) {
		Long.MAX_VALUE
	} else {
		wall + uncertainty
	}
}


private const val LIFECYCLE_STOPPING = "STOPPING"
private const val LIFECYCLE_ACTIVE = "ACTIVE"
private val SETTLED_STOP_STATUSES = setOf("COMPLETE", "PARTIAL_UNOBSERVABLE")
internal val ACTIVITY_CAPTURED_TERMINAL_STATES = setOf("FINALIZED", "CLOSED", "FAILED")
private val INTERRUPTED_COMPLETION_REASONS = setOf("FORCE_STOP_SUPPRESSED", "PREVIOUS_PROCESS_INTERRUPTED")
private val ALL_LIFECYCLE_STATES = ACTIVITY_CAPTURED_TERMINAL_STATES +
	setOf("STARTING", LIFECYCLE_ACTIVE, "RECONFIGURING", LIFECYCLE_STOPPING)
private val SETTLED_STOPPING_SESSION_STATES = setOf(LIFECYCLE_ACTIVE, LIFECYCLE_STOPPING)
private val LIVE_SESSION_RUN_STATE_PAIRS = setOf(
	"STARTING" to "STARTING",
	"STARTING" to "ACTIVE",
	"ACTIVE" to "STARTING",
	"ACTIVE" to "ACTIVE",
	"RECONFIGURING" to "STARTING",
	"RECONFIGURING" to "ACTIVE",
	"ACTIVE" to LIFECYCLE_STOPPING,
	LIFECYCLE_STOPPING to LIFECYCLE_STOPPING,
)
