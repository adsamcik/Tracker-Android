package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.ADMISSION_RUN_STATES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.MAX_PLAN_PAYLOAD_BYTES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE

internal suspend fun WifiCapturedFactMaintenance.hasExactCurrentReplacementBundle(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	expectedCollectedDataEpoch: Long,
	currentCollectedDataEpoch: Long,
	limits: WifiCapturedMaintenanceLimits,
): Boolean {
	if (!run.hasCurrentWifiReplacementIntent(session, expectedCollectedDataEpoch, currentCollectedDataEpoch) ||
		!run.hasAcceptedWifiReplacementIntent(session)
	) {
		return false
	}
	val (manifests, manifest) = loadCurrentWifiReplacementManifest(session, run, limits) ?: return false
	val source = loadCurrentWifiReplacementSource(session, run, manifest, manifests, limits) ?: return false
	return hasCurrentWifiReplacementStart(session, run, manifest, source, expectedCollectedDataEpoch, limits)
}

private fun SourceServiceRunEntity.hasCurrentWifiReplacementIntent(
	session: LogicalTrackingSessionEntity,
	expectedCollectedDataEpoch: Long,
	currentCollectedDataEpoch: Long,
): Boolean = expectedCollectedDataEpoch >= 0L && currentCollectedDataEpoch == expectedCollectedDataEpoch &&
	hasValidCurrentRelationshipTo(session) && session.currentIntentRevision?.let { it > 0L } == true &&
	preparedIntentRevision == session.currentIntentRevision

private fun SourceServiceRunEntity.hasAcceptedWifiReplacementIntent(session: LogicalTrackingSessionEntity): Boolean =
	rolloutRevision == session.rolloutRevision && runtimeAcknowledgement == "START_ACCEPTED" &&
		runtimeFailureCode == null && presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING &&
		presentationAcknowledgedAtMs == null

private suspend fun WifiCapturedFactMaintenance.loadCurrentWifiReplacementManifest(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	limits: WifiCapturedMaintenanceLimits,
): Pair<List<SessionManifestVersionEntity>, SessionManifestVersionEntity>? {
	val manifests = database.sourceSessionDao().manifestsForServiceRun(
		run.serviceRunId,
		limits.maximumManifestsPerRun + 1,
	)
	if (manifests.size > limits.maximumManifestsPerRun ||
		!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
	) {
		return null
	}
	val currentRevision = session.currentManifestRevision ?: return null
	val manifest = manifests.lastOrNull()?.takeIf { candidate ->
		candidate.manifestRevision == currentRevision
	} ?: return null
	return manifest.takeIf { candidate ->
		candidate.sessionMode == session.sessionMode &&
			candidate.acquisitionPlanRevision == session.desiredPlanRevision &&
			candidate.effectiveBootId == session.lifecycleBootId
	}?.let { manifests to it }
}

private suspend fun WifiCapturedFactMaintenance.loadCurrentWifiReplacementSource(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	manifests: List<SessionManifestVersionEntity>,
	limits: WifiCapturedMaintenanceLimits,
): SessionManifestSourceEntity? {
	val allSources = database.trackingHistoryReadDao().manifestSources(
		listOf(run.serviceRunId),
		limits.maximumSourcesPerRun + 1,
	)
	if (!hasValidWifiReplacementSourceTimeline(allSources, manifests, limits)) {
		return null
	}
	val manifestSources = allSources.filter { source ->
		source.logicalTrackingId == session.logicalTrackingId &&
			source.manifestRevision == manifest.manifestRevision
	}
	if (manifestSources.size > limits.maximumSourcesPerManifest || manifestSources.any { source ->
			SourceKind.entries.none { kind -> kind.stableCode == source.sourceKind } ||
				source.purpose !in SessionManifestPurposeCode.ALL
		}
	) {
		return null
	}
	return manifestSources.singleOrNull { source ->
		source.sourceKind == WIFI_SOURCE && source.purpose == CAPTURE_PURPOSE && source.persistenceEligible
	}
}

private fun hasValidWifiReplacementSourceTimeline(
	allSources: List<SessionManifestSourceEntity>,
	manifests: List<SessionManifestVersionEntity>,
	limits: WifiCapturedMaintenanceLimits,
): Boolean = allSources.size <= limits.maximumSourcesPerRun && manifests.none { candidate ->
	!SessionManifestIntegrity.verify(candidate, allSources.filter { source ->
		source.logicalTrackingId == candidate.logicalTrackingId &&
			source.manifestRevision == candidate.manifestRevision
	})
}

private suspend fun WifiCapturedFactMaintenance.hasCurrentWifiReplacementStart(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity,
	expectedCollectedDataEpoch: Long,
	limits: WifiCapturedMaintenanceLimits,
): Boolean {
	val plan = loadCurrentWifiReplacementPlan(manifest) ?: return false
	if (!hasCurrentWifiReplacementConsent(manifest, wifiSource) || !hasCurrentWifiReplacementSegment(session, run)) {
		return false
	}
	val action = loadCurrentWifiReplacementAction(session, run, manifest, wifiSource, limits) ?: return false
	return hasCurrentWifiReplacementRegistration(action, run, manifest, plan, expectedCollectedDataEpoch)
}

private suspend fun WifiCapturedFactMaintenance.loadCurrentWifiReplacementPlan(
	manifest: SessionManifestVersionEntity,
): WifiPlan? {
	val planHeader = database.sourcePlanStateDao().revision(manifest.acquisitionPlanRevision) ?: return null
	val desiredPlan = database.sourcePlanStateDao().desiredPlan(manifest.acquisitionPlanRevision, WIFI_SOURCE)
		?: return null
	if (desiredPlan.payload.size > MAX_PLAN_PAYLOAD_BYTES) {
		return null
	}
	return decodeCurrentWifiReplacementPlan(planHeader, desiredPlan, manifest)
}

private fun WifiCapturedFactMaintenance.decodeCurrentWifiReplacementPlan(
	planHeader: AcquisitionPlanRevisionEntity,
	desiredPlan: SourceDesiredPlanEntity,
	manifest: SessionManifestVersionEntity,
): WifiPlan? {
	val plan = runCatching { planCodec.decode(desiredPlan.payload) as? WifiPlan }.getOrNull() ?: return null
	val reencoded = runCatching { planCodec.encode(plan) }.getOrNull() ?: return null
	return plan.takeIf { candidate ->
		desiredPlan.hasCurrentWifiReplacementPlanIdentity(planHeader, manifest, candidate) &&
			candidate.hasSupportedHistoricalShape() && reencoded.bytes.contentEquals(desiredPlan.payload) &&
			reencoded.checksum == desiredPlan.payloadChecksum
	}
}

private suspend fun WifiCapturedFactMaintenance.hasCurrentWifiReplacementConsent(
	manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity,
): Boolean {
	val policy = database.sourcePolicyDao().policyAtRevision(manifest.sourcePolicyRevision, WIFI_SOURCE)
		?: return false
	val consent = database.sourcePolicyDao().consentEpoch(WIFI_SOURCE, CAPTURE_PURPOSE, wifiSource.consentEpoch)
		?: return false
	return policy.authorizesWifiReplacement(manifest, wifiSource) && consent.authorizesWifiReplacement(policy, manifest)
}

private suspend fun WifiCapturedFactMaintenance.hasCurrentWifiReplacementSegment(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
): Boolean {
	val segmentId = run.sessionSegmentId ?: return false
	val segment = database.sessionSegmentDao().getById(segmentId) ?: return false
	return segment.logicalTrackingId == session.logicalTrackingId && segment.serviceRunId == run.serviceRunId
}

private suspend fun WifiCapturedFactMaintenance.loadCurrentWifiReplacementAction(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity,
	limits: WifiCapturedMaintenanceLimits,
): LifecycleDesiredActionEntity? {
	val actions = database.sourceSessionDao().sourceStartActionsForManifestBounded(
		session.logicalTrackingId,
		run.serviceRunId,
		manifest.manifestRevision,
		WIFI_SOURCE,
		limits.maximumPlanApplicationRows + 1,
	)
	if (actions.size > limits.maximumPlanApplicationRows) {
		return null
	}
	return actions.singleOrNull()?.takeIf { candidate ->
		candidate.authenticatesCurrentReplacement(session, run, manifest, wifiSource)
	}
}

private suspend fun WifiCapturedFactMaintenance.hasCurrentWifiReplacementRegistration(
	action: LifecycleDesiredActionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	plan: WifiPlan,
	expectedCollectedDataEpoch: Long,
): Boolean {
	val generation = action.registrationGeneration ?: return false
	val registration = database.sourceBrokerDao().registration(WIFI_SOURCE, generation) ?: return false
	return registration.hasAcceptedWifiReplacement(action, run, manifest, plan, expectedCollectedDataEpoch)
}

private fun ProviderRegistrationGenerationEntity.hasAcceptedWifiReplacement(
	action: LifecycleDesiredActionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	plan: WifiPlan,
	expectedCollectedDataEpoch: Long,
): Boolean {
	val acceptedWall = acceptedAtMs
	val acceptedElapsed = acceptedElapsedRealtimeNanos
	val acknowledgedWall = action.acknowledgedAtMs
	val acknowledgedElapsed = action.acknowledgedElapsedRealtimeNanos
	if (acceptedWall == null || acceptedElapsed == null || acknowledgedWall == null || acknowledgedElapsed == null) {
		return false
	}
	val validStatus = hasAcceptedWifiReplacementStatus(run, acknowledgedWall, acknowledgedElapsed)
	return validStatus && hasCurrentWifiReplacementProvider(action, run, plan, expectedCollectedDataEpoch) &&
		hasCurrentWifiReplacementClocks(manifest, acceptedWall, acceptedElapsed, acknowledgedWall, acknowledgedElapsed)
}

private fun ProviderRegistrationGenerationEntity.hasAcceptedWifiReplacementStatus(
	run: SourceServiceRunEntity,
	acknowledgedWall: Long,
	acknowledgedElapsed: Long,
): Boolean = when (status) {
	ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
		retiredAtMs == null && retiredElapsedRealtimeNanos == null && failureCode == null && run.state in ADMISSION_RUN_STATES
	ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
		hasRetiringWifiReplacementStatus(run, acknowledgedWall, acknowledgedElapsed)
	else -> false
}

private fun ProviderRegistrationGenerationEntity.hasCurrentWifiReplacementProvider(
	action: LifecycleDesiredActionEntity,
	run: SourceServiceRunEntity,
	plan: WifiPlan,
	expectedCollectedDataEpoch: Long,
): Boolean = sourceInstanceId == action.sourceInstanceId && ownerScope == "source-broker:$WIFI_SOURCE" &&
	clockDomainId == run.bootId && providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
	collectedDataEpoch == expectedCollectedDataEpoch &&
	physicalConfigurationFingerprint == plan.physicalConfigurationFingerprint()

private fun ProviderRegistrationGenerationEntity.hasCurrentWifiReplacementClocks(
	manifest: SessionManifestVersionEntity,
	acceptedWall: Long,
	acceptedElapsed: Long,
	acknowledgedWall: Long,
	acknowledgedElapsed: Long,
): Boolean = reservedAtMs >= manifest.effectiveWallTimeMs &&
	reservedElapsedRealtimeNanos >= manifest.effectiveElapsedRealtimeNanos && reservedAtMs <= acceptedWall &&
	reservedElapsedRealtimeNanos <= acceptedElapsed && acceptedWall <= acknowledgedWall &&
	acceptedElapsed <= acknowledgedElapsed && retiredAtMs?.let { it >= acceptedWall } != false &&
	retiredElapsedRealtimeNanos?.let { it >= acceptedElapsed } != false

private fun ProviderRegistrationGenerationEntity.hasRetiringWifiReplacementStatus(
	run: SourceServiceRunEntity,
	acknowledgedWall: Long,
	acknowledgedElapsed: Long,
): Boolean = retiredAtMs != null && retiredElapsedRealtimeNanos != null && !failureCode.isNullOrBlank() &&
	run.state == "STOPPING" && retiredAtMs?.let { it >= acknowledgedWall } == true &&
	retiredElapsedRealtimeNanos?.let { it >= acknowledgedElapsed } == true
