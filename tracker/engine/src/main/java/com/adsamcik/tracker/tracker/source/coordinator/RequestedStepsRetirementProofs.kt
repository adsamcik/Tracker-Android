package com.adsamcik.tracker.tracker.source.coordinator

import com.adsamcik.tracker.shared.base.database.StepsTerminalCompletenessAuthentication
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProviderPurposeScope
import com.adsamcik.tracker.shared.base.database.data.SourceRunRetirementEntity
import com.adsamcik.tracker.shared.base.database.data.SourceRuntimeStateEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.tracker.source.model.AcquisitionPlanRevision
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.StepsPlan
import com.adsamcik.tracker.tracker.source.runtime.RuntimeAdmissionSnapshot
import com.adsamcik.tracker.tracker.source.runtime.RuntimeCheckpointLifecycle
import com.adsamcik.tracker.tracker.source.runtime.SENSOR_RUNTIME_CHECKPOINT_VERSION
import com.adsamcik.tracker.tracker.source.runtime.SensorRuntimeCheckpoint
import com.adsamcik.tracker.tracker.source.runtime.SourceProviderKey
import com.adsamcik.tracker.tracker.source.runtime.SourceRuntimeClaim
import com.adsamcik.tracker.tracker.source.runtime.SourceStopStatus

internal fun SourceRunRetirementEntity.matchesRequestedStepsRetirementOwner(
	source: SourceKind,
	serviceRunId: String,
	provider: SourceProviderKey,
	claim: SourceRuntimeClaim,
	receipt: SourceRunRetirementEntity,
): Boolean = this == receipt && source == SourceKind.STEPS && serviceRunId == this.serviceRunId &&
	provider.sourceInstanceId.value == sourceInstanceId && provider.registrationGeneration == registrationGeneration &&
	claim.matchesRequestedStepsRetirementLease(this)

private fun SourceRuntimeClaim.matchesRequestedStepsRetirementLease(receipt: SourceRunRetirementEntity): Boolean =
	actionId == receipt.actionId && attemptCount == receipt.attemptCount && leaseGeneration == receipt.leaseGeneration &&
		logicalTrackingId == receipt.logicalTrackingId && serviceRunId == receipt.serviceRunId

internal fun ProviderRegistrationGenerationEntity.matchesRecoveredStepsRegistration(
	current: SourceRunRetirementEntity,
	authenticated: StepsTerminalCompletenessAuthentication.Authenticated?,
): Boolean = sourceInstanceId == current.sourceInstanceId &&
	status == ProviderRegistrationGenerationEntity.STATUS_RETIRED &&
	authenticated?.countDomainCollectedDataEpoch?.let { it != collectedDataEpoch } != true &&
	SourceProviderPurposeScope.isCanonicalOwnerScope(SourceKind.STEPS.stableCode, ownerScope)

internal fun SourceRuntimeStateEntity.matchesRecoveredStepsCheckpointOwner(
	current: SourceRunRetirementEntity,
	registration: ProviderRegistrationGenerationEntity,
): Boolean = stateVersion == SENSOR_RUNTIME_CHECKPOINT_VERSION && sourceInstanceId == current.sourceInstanceId &&
	registrationGeneration == current.registrationGeneration && clockDomainId == registration.clockDomainId &&
	lastProviderSequence >= 0L

internal fun SensorRuntimeCheckpoint.matchesRecoveredStepsCompleteness(
	state: SourceRuntimeStateEntity,
	completeness: SourceSessionCompletenessEntity,
): Boolean {
	val expectedLifecycle = if (completeness.appDrainComplete) {
		RuntimeCheckpointLifecycle.QUIESCED
	} else {
		RuntimeCheckpointLifecycle.TIMED_OUT
	}
	val metrics = metrics
	val sequenceHighWater = listOfNotNull(
		metrics.lastDurablyAdmittedSequence,
		metrics.unresolvedSequenceEndInclusive,
	).maxOrNull() ?: 0L
	return lifecycle == expectedLifecycle && metrics.matchesRecoveredStepsCompleteness(completeness) &&
		metrics.hasValidRecoveredStepsSequences() && state.lastProviderSequence >= sequenceHighWater &&
		completeness.stopStatus in setOf(
			SourceStopStatus.COMPLETE.name,
			SourceStopStatus.PARTIAL_UNOBSERVABLE.name,
			SourceStopStatus.PROCESS_RESTARTED.name,
		)
}

private fun RuntimeAdmissionSnapshot.matchesRecoveredStepsCompleteness(
	completeness: SourceSessionCompletenessEntity,
): Boolean = lastDurablyAdmittedSequence == completeness.lastSourceSequence &&
	lastAdmissionOrdinal == completeness.lastAdmissionOrdinal &&
	unresolvedSequenceStart == completeness.unresolvedSequenceStart &&
	unresolvedSequenceEndInclusive == completeness.unresolvedSequenceEnd

private fun RuntimeAdmissionSnapshot.hasValidRecoveredStepsSequences(): Boolean = failedAdmissionCount >= 0L &&
	(unresolvedSequenceStart == null) == (unresolvedSequenceEndInclusive == null) &&
	unresolvedSequenceStart?.let { start ->
		start > 0L && start <= requireNotNull(unresolvedSequenceEndInclusive)
	} != false

internal fun LifecycleDesiredActionEntity.matchesRequestedStepsRetirementIntent(
	manifest: SessionManifestVersionEntity,
	intent: SessionLifecycleIntentVersionEntity,
): Boolean = startOrigin == manifest.startOrigin && bootId == manifest.effectiveBootId &&
	requestedAtMs == manifest.effectiveWallTimeMs &&
	requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
	intent.logicalTrackingId == logicalTrackingId && intent.manifestRevision == manifestRevision

internal fun LifecycleDesiredActionEntity.matchesRequestedStepsRetirementAttempt(
	retirement: SourceRunRetirementEntity,
): Boolean = attemptCount == retirement.attemptCount && attemptCount > 0 &&
	leaseGeneration == retirement.leaseGeneration && leaseGeneration > 0L &&
	sourceInstanceId == retirement.sourceInstanceId && registrationGeneration == retirement.registrationGeneration &&
	status in setOf(
		LifecycleActionStatus.APPLYING.name,
		LifecycleActionStatus.START_ACCEPTED.name,
		LifecycleActionStatus.CLEANUP_REQUIRED.name,
	)

internal fun LogicalTrackingSessionEntity.matchesRequestedStepsRetirement(
	retirement: SourceRunRetirementEntity,
	action: LifecycleDesiredActionEntity,
): Boolean = logicalTrackingId == retirement.logicalTrackingId && currentServiceRunId == retirement.serviceRunId &&
	currentManifestRevision?.let { it < action.manifestRevision } == false &&
	state in setOf(SessionLifecycleState.STOPPING.name, SessionLifecycleState.ACTIVE.name) &&
	cutoffElapsedNanos == retirement.cutoffElapsedRealtimeNanos && cutoffAtMs == retirement.cutoffWallTimeMs

internal fun SourceServiceRunEntity.matchesRequestedStepsRetirement(
	retirement: SourceRunRetirementEntity,
	session: LogicalTrackingSessionEntity,
): Boolean = logicalTrackingId == retirement.logicalTrackingId && serviceRunId == retirement.serviceRunId &&
	state == SessionLifecycleState.STOPPING.name && completedAtMs == null &&
	desiredPlanRevision == session.desiredPlanRevision &&
	rolloutRevision == session.rolloutRevision && bootId == session.lifecycleBootId

internal fun SessionManifestVersionEntity.matchesRequestedStepsRetirement(
	retirement: SourceRunRetirementEntity,
	action: LifecycleDesiredActionEntity,
	run: SourceServiceRunEntity,
	session: LogicalTrackingSessionEntity,
): Boolean = logicalTrackingId == retirement.logicalTrackingId && serviceRunId == retirement.serviceRunId &&
	manifestRevision == action.manifestRevision && rolloutRevision == run.rolloutRevision &&
	sessionMode == session.sessionMode &&
	effectiveBootId == run.bootId && effectiveWallTimeMs >= run.startedAtMs &&
	effectiveElapsedRealtimeNanos >= run.startedElapsedNanos

internal fun AcquisitionPlanRevision.matchesRequestedStepsRetirement(
	manifest: SessionManifestVersionEntity,
	stepsPlan: StepsPlan,
	binding: SessionManifestSourceEntity,
): Boolean = revision == manifest.acquisitionPlanRevision && sourcePolicyRevision == manifest.sourcePolicyRevision &&
	stepsPlan.revision == manifest.acquisitionPlanRevision && stepsPlan.enabled && binding.persistenceEligible

internal fun SourceRunRetirementEntity.hasRequestedStepsRetirementLifecycle(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	action: LifecycleDesiredActionEntity,
): Boolean = session.matchesRequestedStepsRetirement(this, action) &&
	run.matchesRequestedStepsRetirement(this, session) &&
	manifest.matchesRequestedStepsRetirement(this, action, run, session)
