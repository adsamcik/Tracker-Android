package com.adsamcik.tracker.tracker.source.coordinator

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionLifecycleIntentVersionEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Accepted recording actions remain usable after a newer operation retires the same provider. */
internal suspend fun stepsProductActionIdentities(
	database: AppDatabase,
	logicalTrackingId: String,
	serviceRunId: String,
	retirementClaims: List<SourceDrainRetirementClaim>,
): Set<SourceDrainActionIdentity> = database.withTransaction {
	val productClaims = retirementClaims.filterNot(SourceDrainRetirementClaim::cleanupOnly)
	val exactRetirementActions = productClaims.mapTo(linkedSetOf()) { claim ->
		SourceDrainActionIdentity(claim.sourceInstanceId, claim.registrationGeneration, claim.leaseGeneration)
	}
	if (productClaims.isEmpty()) {
		return@withTransaction exactRetirementActions
	}
	val recordingActions = database.readAcceptedStepsRecordingActions(
		logicalTrackingId, serviceRunId, productClaims,
	) ?: return@withTransaction exactRetirementActions
	exactRetirementActions + recordingActions
}

private suspend fun AppDatabase.readAcceptedStepsRecordingActions(
	logicalTrackingId: String,
	serviceRunId: String,
	productClaims: List<SourceDrainRetirementClaim>,
): Set<SourceDrainActionIdentity>? {
	val rawActions = sourceSessionDao().rawLifecycleActionsForServiceRunBounded(
		serviceRunId, MAX_RUN_RETIREMENT_ACTIONS + 1,
	)
	if (rawActions.size > MAX_RUN_RETIREMENT_ACTIONS) {
		return null
	}
	val recordingActions = linkedSetOf<SourceDrainActionIdentity>()
	for (raw in rawActions) {
		currentCoroutineContext().ensureActive()
		val action = raw.validatedOrNull()?.takeIf {
			it.logicalTrackingId == logicalTrackingId && it.serviceRunId == serviceRunId
		} ?: return null
		if (!action.isAcceptedStepsRecordingAction()) {
			continue
		}
		val provider = productClaims.singleOrNull { it.matchesStepsRecordingProvider(action) } ?: continue
		if (!hasAcceptedStepsRecordingManifest(action)) {
			return null
		}
		recordingActions += SourceDrainActionIdentity(
			provider.sourceInstanceId, provider.registrationGeneration, action.leaseGeneration,
		)
	}
	return recordingActions
}

private fun SourceDrainRetirementClaim.matchesStepsRecordingProvider(action: LifecycleDesiredActionEntity): Boolean =
	source == SourceKind.STEPS && sourceInstanceId == action.sourceInstanceId &&
		registrationGeneration == action.registrationGeneration

// This fixed saved action shape checks only immutable scalar fields.
@Suppress("ComplexCondition")
private fun LifecycleDesiredActionEntity.isAcceptedStepsRecordingAction(): Boolean =
	sourceKind == SourceKind.STEPS.stableCode && actionFamily == LifecycleActionFamily.SOURCE_RUNTIME.name &&
		desiredState == "STARTED" && status == LifecycleActionStatus.START_ACCEPTED.name && attemptCount > 0

private suspend fun AppDatabase.hasAcceptedStepsRecordingManifest(action: LifecycleDesiredActionEntity): Boolean {
	val dao = sourceSessionDao()
	val manifest = dao.manifestByServiceRunRevision(action.serviceRunId, action.manifestRevision) ?: return false
	val rawBindings = dao.rawManifestSourcesForRevisions(
		action.logicalTrackingId, listOf(action.manifestRevision), MAX_RUN_RETIREMENT_MANIFEST_SOURCES + 1,
	)
	if (rawBindings.size > MAX_RUN_RETIREMENT_MANIFEST_SOURCES) {
		return false
	}
	val bindings = rawBindings.map { it.validatedOrNull() ?: return false }
	return SessionManifestIntegrity.verify(manifest, bindings) && bindings.singleOrNull {
		it.sourceKind == SourceKind.STEPS.stableCode &&
			it.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && it.persistenceEligible
	}?.let { binding ->
		action.hasAcceptedRecordingEnvelope(manifest, binding) &&
			hasCanonicalAcceptedRecordingAction(this, action, manifest)
	} == true
}

// One saved action/manifest/binding stamp, including nullable acknowledgement bounds.
@Suppress("CyclomaticComplexMethod")
private fun LifecycleDesiredActionEntity.hasAcceptedRecordingEnvelope(
	manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity,
): Boolean = logicalTrackingId == manifest.logicalTrackingId && serviceRunId == manifest.serviceRunId &&
	desiredPlanRevision == manifest.acquisitionPlanRevision && sourcePolicyRevision == manifest.sourcePolicyRevision &&
	consentEpoch == binding.consentEpoch && startOrigin == manifest.startOrigin && bootId == manifest.effectiveBootId &&
	requestedAtMs == manifest.effectiveWallTimeMs &&
	requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
	failureCode == null && retryTrigger == null &&
	acknowledgedAtMs?.let { it >= requestedAtMs } == true &&
	acknowledgedElapsedRealtimeNanos?.let { it >= requestedElapsedRealtimeNanos } == true

/** Reuses the established Steps action-v2 and active-intent encodings; no new proof is stored. */
private suspend fun hasCanonicalAcceptedRecordingAction(
	database: AppDatabase,
	action: LifecycleDesiredActionEntity,
	manifest: SessionManifestVersionEntity,
): Boolean {
	val raw = database.sourceSessionDao().rawLifecycleIntentsForManifestBounded(action.logicalTrackingId,
		action.manifestRevision, MAX_RUN_RETIREMENT_INTENTS_PER_MANIFEST + 1)
	if (raw.size > MAX_RUN_RETIREMENT_INTENTS_PER_MANIFEST) {
		return false
	}
	val intents = raw.map { it.validatedOrNull() ?: return false }
	return intents.any { intent -> intent.hasAcceptedRecordingEnvelope(manifest) &&
		action.actionId == action.canonicalRecordingActionId(intent.intentRevision) }
}

private fun LifecycleDesiredActionEntity.canonicalRecordingActionId(intentRevision: Long): String =
	stableLifecycleChecksum("STEPS_ACTION_V2", intentRevision, logicalTrackingId, serviceRunId,
		manifestRevision, actionRevision, actionFamily, sourceKind, desiredState, desiredPlanRevision,
		sourcePolicyRevision, consentEpoch, startOrigin, bootId, leaseGeneration, requestedAtMs,
		requestedElapsedRealtimeNanos)

private fun SessionLifecycleIntentVersionEntity.hasAcceptedRecordingEnvelope(
	manifest: SessionManifestVersionEntity,
): Boolean = logicalTrackingId == manifest.logicalTrackingId && manifestRevision == manifest.manifestRevision &&
	desiredState == LifecycleDesiredState.ACTIVE.name && startOrigin == manifest.startOrigin &&
	requestBootId == manifest.effectiveBootId &&
	requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
	requestedWallTimeMs == manifest.effectiveWallTimeMs && automationEpoch == manifest.automationEpoch &&
	hasNoRecordingStopBoundary() &&
	intentChecksum == stableLifecycleChecksum(logicalTrackingId, intentRevision, manifestRevision,
		LifecycleDesiredState.ACTIVE, startOrigin, requestBootId, requestedElapsedRealtimeNanos,
		requestedWallTimeMs, triggerId, automationEpoch, triggerCollectedDataEpoch, sourceCallerAuthorityReference)

private fun SessionLifecycleIntentVersionEntity.hasNoRecordingStopBoundary(): Boolean =
	stopReason == null && stopDeadlineBootId == null && stopDeadlineElapsedRealtimeNanos == null
