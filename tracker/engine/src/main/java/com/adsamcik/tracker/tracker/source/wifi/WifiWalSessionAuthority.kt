package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.dao.SourceSessionDao
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.tracker.source.coordinator.EncodedSourcePlan
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.tracker.source.coordinator.SourcePlanCodec
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiPlan
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint

/** Authenticates the complete saved replacement of an older terminal Wi-Fi run. */
internal suspend fun AppDatabase.hasExactWifiWalCurrentReplacementBundle(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	expectedCollectedDataEpoch: Long,
	currentCollectedDataEpoch: Long,
	planCodec: SourcePlanCodec,
): Boolean {
	if (!hasExactWifiWalReplacementHead(session, run, expectedCollectedDataEpoch, currentCollectedDataEpoch)) {
		return false
	}
	val sessionDao = sourceSessionDao()
	val (manifest, wifiSource) = loadWifiWalReplacementManifest(session, run, sessionDao) ?: return false
	val plan = loadWifiWalReplacementPlan(manifest, planCodec)
	return plan != null && hasWifiWalReplacementConsent(manifest, wifiSource) &&
		hasWifiWalReplacementProvider(session, run, manifest, wifiSource, plan, expectedCollectedDataEpoch)
}

// Compare the complete immutable accepted-head tuple before any replacement query.
@Suppress("CyclomaticComplexMethod")
private fun hasExactWifiWalReplacementHead(
	session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity,
	expectedCollectedDataEpoch: Long, currentCollectedDataEpoch: Long,
): Boolean {
	if (expectedCollectedDataEpoch < 0L || currentCollectedDataEpoch != expectedCollectedDataEpoch) {
		return false
	}
	return run.hasValidWifiWalCurrentRelationshipTo(session) && session.currentIntentRevision?.let { it > 0L } == true &&
		run.preparedIntentRevision == session.currentIntentRevision && run.rolloutRevision == session.rolloutRevision &&
		run.runtimeAcknowledgement == "START_ACCEPTED" && run.runtimeFailureCode == null &&
		run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING &&
		run.presentationAcknowledgedAtMs == null
}

private suspend fun AppDatabase.loadWifiWalReplacementManifest(
	session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity, sessionDao: SourceSessionDao,
): Pair<SessionManifestVersionEntity, SessionManifestSourceEntity>? {
	val manifests = sessionDao.manifestsForServiceRun(run.serviceRunId, WIFI_QUAL_MAX_MANIFESTS + 1)
	if (manifests.size > WIFI_QUAL_MAX_MANIFESTS ||
		!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)
	) {
		return null
	}
	val currentRevision = session.currentManifestRevision ?: return null
	val manifest = manifests.lastOrNull()?.takeIf { it.manifestRevision == currentRevision } ?: return null
	return if (manifest.sessionMode != session.sessionMode || manifest.acquisitionPlanRevision !=
		session.desiredPlanRevision || manifest.effectiveBootId != session.lifecycleBootId
	) {
		null
	} else {
		loadWifiWalReplacementSource(session, run, manifest, manifests)?.let { manifest to it }
	}
}

private suspend fun AppDatabase.loadWifiWalReplacementSource(
	session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity,
	manifests: List<SessionManifestVersionEntity>,
): SessionManifestSourceEntity? {
	val allSources = trackingHistoryReadDao().manifestSources(
		listOf(run.serviceRunId), WIFI_QUAL_MAX_MANIFEST_SOURCES + 1,
	)
	if (allSources.size > WIFI_QUAL_MAX_MANIFEST_SOURCES || manifests.any { candidate ->
			!SessionManifestIntegrity.verify(candidate, allSources.filter { source ->
				source.logicalTrackingId == candidate.logicalTrackingId &&
					source.manifestRevision == candidate.manifestRevision
			})
		}
	) {
		return null
	}
	val manifestSources = allSources.filter { source ->
		source.logicalTrackingId == session.logicalTrackingId &&
			source.manifestRevision == manifest.manifestRevision
	}
	if (manifestSources.any { source ->
			SourceKind.entries.none { it.stableCode == source.sourceKind } ||
				source.purpose !in SessionManifestPurposeCode.ALL
		}
	) {
		return null
	}
	val wifiSource = manifestSources.singleOrNull { source ->
		source.sourceKind == WIFI_QUAL_SOURCE &&
			source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && source.persistenceEligible
	} ?: return null

	return wifiSource
}

private suspend fun AppDatabase.loadWifiWalReplacementPlan(
	manifest: SessionManifestVersionEntity, planCodec: SourcePlanCodec,
): WifiPlan? {
	val planHeader = sourcePlanStateDao().revision(manifest.acquisitionPlanRevision)
		?: return null
	val desiredPlan = sourcePlanStateDao().desiredPlan(
		manifest.acquisitionPlanRevision, WIFI_QUAL_SOURCE,
	) ?: return null
	if (desiredPlan.payload.size > WIFI_QUAL_MAX_PLAN_BYTES) {
		return null
	}
	return decodeWifiWalReplacementPlan(manifest, desiredPlan, planHeader.sourcePolicyRevision, planCodec)
}

private fun decodeWifiWalReplacementPlan(
	manifest: SessionManifestVersionEntity, desiredPlan: SourceDesiredPlanEntity,
	headerPolicyRevision: Long?, planCodec: SourcePlanCodec,
): WifiPlan? {
	val plan = runCatching { planCodec.decode(desiredPlan.payload) as? WifiPlan }.getOrNull() ?: return null
	val reencoded = runCatching { planCodec.encode(plan) }.getOrNull() ?: return null
	return plan.takeIf { matchesWifiWalReplacementEncoding(manifest, desiredPlan, headerPolicyRevision, it, reencoded) }
}

private fun matchesWifiWalReplacementEncoding(
	manifest: SessionManifestVersionEntity, desiredPlan: SourceDesiredPlanEntity, headerPolicyRevision: Long?,
	plan: WifiPlan, reencoded: EncodedSourcePlan,
): Boolean = headerPolicyRevision == manifest.sourcePolicyRevision &&
	desiredPlan.payloadVersion == WIFI_QUAL_PLAN_VERSION && desiredPlan.revision == manifest.acquisitionPlanRevision &&
	desiredPlan.sourceKind == WIFI_QUAL_SOURCE && plan.revision == manifest.acquisitionPlanRevision &&
	plan.hasSupportedWifiWalHistoricalShape() && reencoded.bytes.contentEquals(desiredPlan.payload) &&
	reencoded.checksum == desiredPlan.payloadChecksum

private suspend fun AppDatabase.hasWifiWalReplacementConsent(
	manifest: SessionManifestVersionEntity, wifiSource: SessionManifestSourceEntity,
): Boolean {
	val policy = sourcePolicyDao().policyAtRevision(manifest.sourcePolicyRevision, WIFI_QUAL_SOURCE)
		?: return false
	val consent = sourcePolicyDao().consentEpoch(
		WIFI_QUAL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, wifiSource.consentEpoch,
	) ?: return false
	return policy.matchesWifiWalReplacementPolicy(manifest, wifiSource) &&
		consent.matchesWifiWalReplacementConsent(manifest, policy)
}

private fun SourcePolicyEntity.matchesWifiWalReplacementPolicy(
	manifest: SessionManifestVersionEntity, wifiSource: SessionManifestSourceEntity,
): Boolean = enabled && capturePersistenceEligible && captureConsentEpoch == wifiSource.consentEpoch &&
	qosCode == wifiSource.qosCode &&
	(effectiveBootId != manifest.effectiveBootId ||
		effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos)

private fun SourceConsentEpochEntity.matchesWifiWalReplacementConsent(
	manifest: SessionManifestVersionEntity, policy: SourcePolicyEntity,
): Boolean = eligible && persistenceEligible && policyRevision <= policy.policyRevision &&
	(effectiveBootId != manifest.effectiveBootId ||
		effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos)

private suspend fun AppDatabase.hasWifiWalReplacementProvider(
	session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity, plan: WifiPlan, expectedCollectedDataEpoch: Long,
): Boolean {
	if (!hasWifiWalReplacementSegment(session, run)) {
		return false
	}
	val action = loadWifiWalReplacementAction(session, run, manifest, wifiSource) ?: return false
	val registrationGeneration = action.registrationGeneration
	val registration = registrationGeneration?.let { sourceBrokerDao().registration(WIFI_QUAL_SOURCE, it) }
	return registrationGeneration != null && registration != null &&
		registration.authenticatesWifiWalReplacement(action, run, manifest, plan,
			expectedCollectedDataEpoch, registrationGeneration)
}

private suspend fun AppDatabase.hasWifiWalReplacementSegment(
	session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity,
): Boolean {
	val segmentId = run.sessionSegmentId ?: return false
	val segment = sessionSegmentDao().getById(segmentId) ?: return false
	if (segment.logicalTrackingId != session.logicalTrackingId || segment.serviceRunId != run.serviceRunId) {
		return false
	}
	return true
}

private suspend fun AppDatabase.loadWifiWalReplacementAction(
	session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity,
): LifecycleDesiredActionEntity? {
	val actions = sourceSessionDao().sourceStartActionsForManifestBounded(
		session.logicalTrackingId, run.serviceRunId, manifest.manifestRevision, WIFI_QUAL_SOURCE,
		WIFI_QUAL_MAX_PLAN_ACTIONS + 1,
	)
	if (actions.size > WIFI_QUAL_MAX_PLAN_ACTIONS) {
		return null
	}
	val action = actions.singleOrNull()?.takeIf {
		it.authenticatesWifiWalCurrentReplacement(session, run, manifest, wifiSource)
	} ?: return null
	return action
}

// One immutable provider row must match the accepted action, plan, clocks and finite lifecycle shape.
@Suppress("CyclomaticComplexMethod")
private fun ProviderRegistrationGenerationEntity.authenticatesWifiWalReplacement(
	action: LifecycleDesiredActionEntity, run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity,
	plan: WifiPlan, expectedCollectedDataEpoch: Long, registrationGeneration: Long,
): Boolean {
	val acceptedWall = acceptedAtMs
	val acceptedElapsed = acceptedElapsedRealtimeNanos
	val acknowledgedWall = action.acknowledgedAtMs
	val acknowledgedElapsed = action.acknowledgedElapsedRealtimeNanos
	if (acceptedWall == null || acceptedElapsed == null || acknowledgedWall == null || acknowledgedElapsed == null) {
		return false
	}
	val hasValidStatusShape = when (status) {
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE ->
			retiredAtMs == null && retiredElapsedRealtimeNanos == null &&
				failureCode == null && run.state in WIFI_WAL_ADMISSION_RUN_STATES
		ProviderRegistrationGenerationEntity.STATUS_RETIRING ->
			retiredAtMs != null && retiredElapsedRealtimeNanos != null &&
				!failureCode.isNullOrBlank() && run.state == "STOPPING" &&
				retiredAtMs?.let { it >= acknowledgedWall } == true &&
				retiredElapsedRealtimeNanos?.let { it >= acknowledgedElapsed } == true
		else -> false
	}
	if (!hasValidStatusShape) {
		return false
	}
	val retiredWall = retiredAtMs
	val retiredElapsed = retiredElapsedRealtimeNanos
	return sourceKind == WIFI_QUAL_SOURCE &&
		this.registrationGeneration == registrationGeneration &&
		sourceInstanceId == action.sourceInstanceId &&
		ownerScope == WIFI_QUAL_OWNER_SCOPE && clockDomainId == run.bootId &&
		providerResidency == ProviderRegistrationGenerationEntity.RESIDENCY_PROCESS_BOUND &&
		collectedDataEpoch == expectedCollectedDataEpoch &&
		physicalConfigurationFingerprint == plan.physicalConfigurationFingerprint() &&
		reservedAtMs >= manifest.effectiveWallTimeMs &&
		reservedElapsedRealtimeNanos >= manifest.effectiveElapsedRealtimeNanos &&
		reservedAtMs <= acceptedWall &&
		reservedElapsedRealtimeNanos <= acceptedElapsed &&
		acceptedWall <= acknowledgedWall && acceptedElapsed <= acknowledgedElapsed &&
		retiredWall?.let { it >= acceptedWall } != false &&
		retiredElapsed?.let { it >= acceptedElapsed } != false
}

// Keep the complete immutable saved action, scope and acknowledgement tuple visible.
@Suppress("CyclomaticComplexMethod")
internal fun LifecycleDesiredActionEntity.authenticatesWifiWalPlanApplication(
	wal: SourceEventWalEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	configurationRevision: Long,
	appliedAtElapsedNanos: Long,
	firstQualifiedNanos: Long,
	purposeMember: SourceAuthorizationEntity,
): Boolean {
	val acknowledgedAt = acknowledgedAtMs ?: return false
	val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
	return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" &&
		sourceKind == SourceKind.WIFI.stableCode && desiredState == "STARTED" &&
		status == "START_ACCEPTED" && attemptCount > 0 && failureCode == null && retryTrigger == null &&
		logicalTrackingId == purposeMember.logicalTrackingId && serviceRunId == purposeMember.serviceRunId &&
		manifestRevision == purposeMember.manifestRevision && desiredPlanRevision == configurationRevision &&
		sourcePolicyRevision == purposeMember.sourcePolicyRevision && consentEpoch == purposeMember.consentEpoch &&
		startOrigin == manifest.startOrigin &&
		(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin) &&
		bootId == wal.clockDomainId && leaseGeneration == purposeMember.lifecycleLeaseGeneration &&
		sourceInstanceId == wal.sourceInstanceId && registrationGeneration == wal.registrationGeneration &&
		requestedAtMs == manifest.effectiveWallTimeMs && acknowledgedAt >= requestedAtMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedElapsedRealtimeNanos <= appliedAtElapsedNanos &&
		appliedAtElapsedNanos <= acknowledgedElapsed &&
		appliedAtElapsedNanos <= firstQualifiedNanos
}

// Keep the complete immutable replacement action and clock tuple visible.
@Suppress("ReturnCount", "CyclomaticComplexMethod")
private fun LifecycleDesiredActionEntity.authenticatesWifiWalCurrentReplacement(
	session: LogicalTrackingSessionEntity,
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	wifiSource: SessionManifestSourceEntity,
): Boolean {
	val acknowledgedAt = acknowledgedAtMs ?: return false
	val acknowledgedElapsed = acknowledgedElapsedRealtimeNanos ?: return false
	val sourceInstance = sourceInstanceId ?: return false
	val registration = registrationGeneration ?: return false
	return actionRevision > 0L && actionFamily == "SOURCE_RUNTIME" && sourceKind == SourceKind.WIFI.stableCode &&
		desiredState == "STARTED" && status == "START_ACCEPTED" && attemptCount > 0 &&
		failureCode == null && retryTrigger == null && sourceInstance.isNotBlank() && registration > 0L &&
		logicalTrackingId == session.logicalTrackingId && serviceRunId == run.serviceRunId &&
		manifestRevision == manifest.manifestRevision && desiredPlanRevision == manifest.acquisitionPlanRevision &&
		sourcePolicyRevision == manifest.sourcePolicyRevision && consentEpoch == wifiSource.consentEpoch &&
		startOrigin == manifest.startOrigin &&
		(manifest.manifestRevision != run.preparedManifestRevision || startOrigin == run.startOrigin) &&
            bootId == run.bootId &&
		bootId == session.lifecycleBootId && leaseGeneration == run.leaseGeneration &&
		leaseGeneration == session.lifecycleLeaseGeneration && requestedAtMs == manifest.effectiveWallTimeMs &&
		requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		requestedAtMs >= run.startedAtMs && requestedElapsedRealtimeNanos >= run.startedElapsedNanos &&
		acknowledgedAt >= requestedAtMs && acknowledgedElapsed >= requestedElapsedRealtimeNanos
}

// Validate one immutable lifecycle row, including its terminal-only fields and supplied admission bound.
@Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
internal fun LogicalTrackingSessionEntity.hasValidWifiWalLifecycleShape(
	admissionOrdinal: Long, perReading: Boolean,
): Boolean {
	if (state !in WIFI_WAL_ALL_SESSION_STATES || lifecycleRevision <= 0L || desiredPlanRevision <= 0L ||
		startedAtMs < 0L || startedElapsedNanos < 0L || lifecycleLeaseGeneration <= 0L ||
		lifecycleBootId.isNullOrBlank() || currentManifestRevision?.let { it <= 0L } != false
	) {
		return false
	}
	val terminal = state in WIFI_WAL_TERMINAL_SESSION_STATES
	val interrupted = hasInterruptedWifiWalEnd()
	if ((completedAtMs != null) != terminal) {
		return false
	}
	if (completedAtMs?.let { it < startedAtMs } == true) {
		return false
	}
	if (!interrupted && (cutoffAtMs == null) != (cutoffElapsedNanos == null)) {
		return false
	}
	if (terminal || state == "STOPPING") {
		if (cutoffAtMs == null || !interrupted && cutoffElapsedNanos == null ||
			cutoffAtMs?.let { it < startedAtMs } != false ||
			cutoffElapsedNanos?.let { it < startedElapsedNanos } == true
		) {
			return false
		}
	} else if (cutoffAtMs != null || cutoffElapsedNanos != null) {
		return false
	}
	return if (terminal) {
		currentServiceRunId == null && (interrupted && finalAdmissionOrdinal == null ||
			finalAdmissionOrdinal?.let { it >= 0L && (perReading || it >= admissionOrdinal) } == true)
	} else {
		!currentServiceRunId.isNullOrBlank() && hasValidWifiWalLiveHighWater(admissionOrdinal, perReading)
	}
}

/** End freezes the shared admission bound before the source writer finishes its own rows. */
internal fun LogicalTrackingSessionEntity.hasValidWifiWalLiveHighWater(ordinal: Long, perReading: Boolean): Boolean =
	finalAdmissionOrdinal == null || state == "STOPPING" &&
		finalAdmissionOrdinal?.let { it >= 0L && (perReading || it >= ordinal) } == true

internal fun SourceServiceRunEntity.hasValidWifiWalLifecycleShape(): Boolean {
	// One saved run header has these required scalar fields.
	@Suppress("ComplexCondition")
	if (state !in WIFI_WAL_ALL_SESSION_STATES || desiredPlanRevision <= 0L || startedAtMs < 0L ||
		startedElapsedNanos < 0L || leaseGeneration <= 0L || bootId.isBlank()
	) {
		return false
	}
	val terminal = state in WIFI_WAL_TERMINAL_SESSION_STATES
	if ((completedAtMs != null) != terminal) {
		return false
	}
	return completedAtMs?.let { it >= startedAtMs } != false
}

internal fun SourceServiceRunEntity.hasValidWifiWalRelationshipTo(
	session: LogicalTrackingSessionEntity,
	currentRun: SourceServiceRunEntity?,
): Boolean = when {
	logicalTrackingId != session.logicalTrackingId -> false
	state !in WIFI_WAL_TERMINAL_SESSION_STATES -> hasValidWifiWalCurrentRelationshipTo(session)
	session.state in WIFI_WAL_TERMINAL_SESSION_STATES -> session.currentServiceRunId == null
	session.currentServiceRunId == serviceRunId -> false
	else -> currentRun?.hasValidWifiWalLifecycleShape() == true &&
		currentRun.hasValidWifiWalCurrentRelationshipTo(session)
}

// Keep this fixed saved-row relationship together; this performs no query or traversal.
@Suppress("CyclomaticComplexMethod")
private fun SourceServiceRunEntity.hasValidWifiWalCurrentRelationshipTo(
	session: LogicalTrackingSessionEntity,
): Boolean = logicalTrackingId == session.logicalTrackingId &&
	state !in WIFI_WAL_TERMINAL_SESSION_STATES && session.state !in WIFI_WAL_TERMINAL_SESSION_STATES &&
	session.currentServiceRunId == serviceRunId && bootId == session.lifecycleBootId &&
	leaseGeneration == session.lifecycleLeaseGeneration &&
	desiredPlanRevision == session.desiredPlanRevision &&
	preparedManifestRevision in 1L..(session.currentManifestRevision ?: 0L) &&
	startedAtMs >= session.startedAtMs && startedElapsedNanos >= session.startedElapsedNanos &&
	((session.state in WIFI_WAL_ADMISSION_SESSION_STATES && state in WIFI_WAL_ADMISSION_RUN_STATES) ||
		(session.state == "ACTIVE" && state == "STOPPING") ||
		(session.state == "STOPPING" && state == "STOPPING"))


internal val WIFI_WAL_TERMINAL_SESSION_STATES = setOf("FINALIZED", "CLOSED", "FAILED")
private val WIFI_WAL_ADMISSION_SESSION_STATES = setOf("STARTING", "ACTIVE", "RECONFIGURING")
private val WIFI_WAL_ADMISSION_RUN_STATES = setOf("STARTING", "ACTIVE")
private val WIFI_WAL_ALL_SESSION_STATES = WIFI_WAL_TERMINAL_SESSION_STATES +
	setOf("STARTING", "ACTIVE", "RECONFIGURING", "STOPPING")

