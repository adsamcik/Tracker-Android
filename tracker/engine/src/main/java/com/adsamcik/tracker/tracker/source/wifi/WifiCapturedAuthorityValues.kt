package com.adsamcik.tracker.tracker.source.wifi

import com.adsamcik.tracker.shared.base.database.data.LEGACY_WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.shared.base.database.data.LifecycleDesiredActionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceWriterGenerationContract
import com.adsamcik.tracker.shared.base.database.data.WIFI_WAL_PAYLOAD_VERSION
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.model.WifiAccessPointEvidence
import com.adsamcik.tracker.tracker.source.model.WifiResultSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.physicalConfigurationFingerprint
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.ADMISSION_RUN_STATES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.ADMISSION_SESSION_STATES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.ALL_SESSION_STATES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURED_PLAN_ATTRIBUTION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.CAPTURE_PURPOSE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.DESTINATION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.EMPTY_BYTES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.LOWERCASE_SHA_256
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.NANOS_PER_MILLISECOND
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.OWNER
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.PRODUCER_WALL_UNCERTAINTY_MS
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.TERMINAL_SESSION_STATES
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_PROVIDER_ITEM_ORDER
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WIFI_SOURCE
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WRITER_ID
import com.adsamcik.tracker.tracker.source.wifi.WifiCapturedFactMaintenance.Companion.WRITER_VERSION
import java.time.DateTimeException
import java.time.ZoneId

private fun SourceEventWalEntity.hasExactSharedProducerEnvelope(): Boolean =
	WifiMaintenanceProducerChecks.sharedEnvelope(this)

internal fun SourceEventWalEntity.hasExactCaptureProducerEnvelope(): Boolean =
	hasExactSharedProducerEnvelope() && planAttribution == CAPTURED_PLAN_ATTRIBUTION

internal fun SourceEventWalEntity.hasExactNoncaptureProducerEnvelope(): Boolean =
	WifiMaintenanceProducerChecks.noncaptureEnvelope(this)

internal fun WifiResultSnapshotPayload.hasExactProducerShape(wal: SourceEventWalEntity): Boolean =
	WifiMaintenanceProducerChecks.payloadShape(this, wal)

/** Original radio callback envelope and item ordering, independent of active maintenance mutations. */
private object WifiMaintenanceProducerChecks {
	fun sharedEnvelope(wal: SourceEventWalEntity): Boolean = sharedIdentity(wal) &&
		sharedElapsedTime(wal) && sharedWallTime(wal) && sharedQuality(wal)

	private fun sharedIdentity(wal: SourceEventWalEntity): Boolean = wal.admissionOrdinal > 0L &&
		wal.providerDedupKey == null && wal.deliveryIdentity != null && wal.deliveryUnitIndex == 0 &&
		wal.deliveryUnitCount == 1 && wal.sourceSequence >= 0L && wal.activityAutomationEpoch == null

	private fun sharedElapsedTime(wal: SourceEventWalEntity): Boolean {
		val start = wal.observedIntervalStartNanos ?: return false
		return start > 0L && wal.observedElapsedNanos >= start && wal.receivedElapsedNanos >= wal.observedElapsedNanos
	}

	private fun sharedWallTime(wal: SourceEventWalEntity): Boolean =
		wal.wallTimeMs?.let { wall -> wal.receivedWallTimeMs?.let { it >= wall } } == true &&
		requireNotNull(wal.wallTimeMs) >= 0L && wal.wallTimeUncertaintyMs == PRODUCER_WALL_UNCERTAINTY_MS &&
		wal.acquiredAtMs == wal.wallTimeMs

	private fun sharedQuality(wal: SourceEventWalEntity): Boolean =
		wal.createdAtMs >= 0L && wal.qualityFlags == 0L && wal.qualityConfidence == null

	fun noncaptureEnvelope(wal: SourceEventWalEntity): Boolean {
		val mask = wal.authorizationPurposeEligibilityMask and SourceBrokerPurpose.ALL_MASK
		return sharedEnvelope(wal) && noncapturePurpose(wal, mask) && emptyCaptureScope(wal) && providerAuthority(wal)
	}

	private fun noncapturePurpose(wal: SourceEventWalEntity, mask: Long): Boolean =
		wal.planAttribution == RECEIVE_TIME_ONLY_PLAN_ATTRIBUTION && wal.authorizationPurposeEligibilityMask == mask &&
		(wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION || mask != 0L) &&
			mask and SourceBrokerPurpose.MASK_SESSION_CAPTURE == 0L

	private fun emptyCaptureScope(wal: SourceEventWalEntity): Boolean = wal.logicalTrackingId == null &&
		wal.serviceRunId == null && wal.sourcePolicyRevision == null && wal.captureConsentEpoch == null &&
		wal.sessionManifestRevision == null && wal.lifecycleLeaseGeneration == null &&
		(if (wal.payloadVersion == WIFI_WAL_PAYLOAD_VERSION) {
			wal.configRevision?.let { it > 0L } != false
		} else {
			wal.configRevision == null
		})

	private fun providerAuthority(wal: SourceEventWalEntity): Boolean =
		wal.physicalConfigurationFingerprint?.matches(LOWERCASE_SHA_256) == true &&
		wal.authorizationRevision?.let { it > 0L } == true &&
			wal.authorizationFingerprint?.matches(LOWERCASE_SHA_256) == true

	fun payloadShape(payload: WifiResultSnapshotPayload, wal: SourceEventWalEntity): Boolean {
		if (payload.accessPoints.size > WifiIdentityFreeResultContract.ANDROID_SCAN_RESULTS_V1.maximumAccessPointCount ||
			payload.resultAgeMs != null || payload.accessPoints.any { !itemShape(it, wal.payloadVersion) }) { return false }
		if (payload.accessPoints.isEmpty()) {
			return payload.platformTimestampMs == null && wal.observedIntervalStartNanos == wal.observedElapsedNanos
		}
		if (payload.accessPoints != payload.accessPoints.sortedWith(WIFI_PROVIDER_ITEM_ORDER)) { return false }
		val times = payload.accessPoints.map { requireNotNull(it.providerTimestampNanos) }
		val latest = requireNotNull(times.maxOrNull())
		return times.minOrNull() == wal.observedIntervalStartNanos && latest == wal.observedElapsedNanos &&
			payload.platformTimestampMs == latest / NANOS_PER_MILLISECOND
	}

	private fun itemShape(point: WifiAccessPointEvidence, payloadVersion: Int): Boolean =
		(payloadVersion != LEGACY_WIFI_WAL_PAYLOAD_VERSION ||
			(point.identifierToken.isEmpty() && point.networkName == null)) &&
		point.providerTimestampNanos?.let { it > 0L } == true
}

internal fun SourceEventWalEntity.exactlyMatches(other: SourceEventWalEntity): Boolean =
	copy(payload = EMPTY_BYTES) == other.copy(payload = EMPTY_BYTES) && payload.contentEquals(other.payload)

internal fun ProviderRegistrationGenerationEntity.hasValidHistoricalShape(): Boolean =
	WifiMaintenanceProviderChecks.historicalProvider(this)

private object WifiMaintenanceProviderChecks {
	fun historicalProvider(provider: ProviderRegistrationGenerationEntity): Boolean {
		val acceptedWall = provider.acceptedAtMs ?: return false
		val acceptedElapsed = provider.acceptedElapsedRealtimeNanos ?: return false
		return statusMatches(provider) && timesMatch(provider, acceptedWall, acceptedElapsed)
	}

	private fun statusMatches(provider: ProviderRegistrationGenerationEntity): Boolean = when (provider.status) {
		ProviderRegistrationGenerationEntity.STATUS_ACTIVE -> activeStatus(provider)
		ProviderRegistrationGenerationEntity.STATUS_RETIRING -> retiredFields(provider) &&
			!provider.failureCode.isNullOrBlank()
		ProviderRegistrationGenerationEntity.STATUS_RETIRED -> retiredFields(provider) &&
			provider.failureCode?.isNotBlank() != false
		else -> false
	}

	private fun activeStatus(provider: ProviderRegistrationGenerationEntity): Boolean =
		provider.retiredAtMs == null && provider.retiredElapsedRealtimeNanos == null && provider.failureCode == null

	private fun retiredFields(provider: ProviderRegistrationGenerationEntity): Boolean =
		provider.retiredAtMs != null && provider.retiredElapsedRealtimeNanos != null

	private fun timesMatch(
		provider: ProviderRegistrationGenerationEntity,
		acceptedWall: Long,
		acceptedElapsed: Long,
	): Boolean =
		provider.reservedAtMs <= acceptedWall && provider.reservedElapsedRealtimeNanos <= acceptedElapsed &&
		provider.retiredAtMs?.let { it >= acceptedWall } != false &&
		provider.retiredElapsedRealtimeNanos?.let { it >= acceptedElapsed } != false

	fun writerMatches(source: SessionManifestSourceEntity, consentEpoch: Long, sessionMode: String): Boolean =
		writerHeaderMatches(source, consentEpoch) && writerProfileMatches(source) &&
			(sessionMode == "MANUAL" || sessionMode == "AUTOMATIC" &&
				source.writerBindingGeneration == SourceWriterGenerationContract.FINAL_CAPTURE_BINDING_GENERATION)

	private fun writerHeaderMatches(source: SessionManifestSourceEntity, consentEpoch: Long): Boolean =
		source.sourceKind == WIFI_SOURCE && source.purpose == CAPTURE_PURPOSE && source.persistenceEligible &&
		source.consentEpoch == consentEpoch && source.outputDestination == DESTINATION && source.writerOwner == OWNER

	private fun writerProfileMatches(source: SessionManifestSourceEntity): Boolean {
		val binding = source.writerBindingGeneration ?: return false
		return SourceWriterGenerationContract.captureModeMaskForKnownBinding(binding) != null &&
			source.writerOwnerGeneration == SourceWriterGenerationContract.canonicalOwnerGeneration(binding) &&
			source.writerProjectionId == WRITER_ID && source.writerProjectionVersion == WRITER_VERSION
	}
}

internal fun LifecycleDesiredActionEntity.authenticates(
	wal: SourceEventWalEntity, run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity,
	configurationRevision: Long, appliedAtElapsedNanos: Long,
	firstQualifiedNanos: Long = wal.observedIntervalStartNanos ?: Long.MIN_VALUE,
	purposeMember: SourceAuthorizationEntity? = null,
): Boolean = WifiMaintenanceActionChecks.matches(this, wal, run, manifest,
	WifiMaintenanceActionTiming(configurationRevision, appliedAtElapsedNanos, firstQualifiedNanos), purposeMember)

internal fun LifecycleDesiredActionEntity.authenticatesCurrentReplacement(
	session: LogicalTrackingSessionEntity, run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity, wifiSource: SessionManifestSourceEntity,
): Boolean = WifiMaintenanceReplacementChecks.matches(this, session, run, manifest, wifiSource)

private data class WifiMaintenanceActionTiming(
	val configurationRevision: Long, val appliedAtElapsedNanos: Long, val firstQualifiedNanos: Long,
)

private object WifiMaintenanceActionChecks {
	fun matches(action: LifecycleDesiredActionEntity, wal: SourceEventWalEntity, run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity, timing: WifiMaintenanceActionTiming,
		member: SourceAuthorizationEntity?): Boolean {
		val acknowledgedWall = action.acknowledgedAtMs ?: return false
		val acknowledgedElapsed = action.acknowledgedElapsedRealtimeNanos ?: return false
		return accepted(action) && scopeMatches(action, wal, timing.configurationRevision, member) &&
			policyMatches(action, wal, run, manifest, member) && providerMatches(action, wal, member) &&
			timingMatches(action, manifest, timing, acknowledgedWall, acknowledgedElapsed)
	}

	fun accepted(action: LifecycleDesiredActionEntity): Boolean = action.actionRevision > 0L &&
		action.actionFamily == "SOURCE_RUNTIME" && action.sourceKind == WIFI_SOURCE && action.desiredState == "STARTED" &&
		action.status == "START_ACCEPTED" && action.attemptCount > 0 && action.failureCode == null &&
			action.retryTrigger == null

	private fun scopeMatches(action: LifecycleDesiredActionEntity, wal: SourceEventWalEntity,
		configurationRevision: Long, member: SourceAuthorizationEntity?): Boolean =
		action.logicalTrackingId == (member?.logicalTrackingId ?: wal.logicalTrackingId) &&
		action.serviceRunId == (member?.serviceRunId ?: wal.serviceRunId) &&
		action.manifestRevision == (member?.manifestRevision ?: wal.sessionManifestRevision) &&
		action.desiredPlanRevision == configurationRevision

	private fun policyMatches(action: LifecycleDesiredActionEntity, wal: SourceEventWalEntity,
		run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity, member: SourceAuthorizationEntity?): Boolean =
		action.sourcePolicyRevision == (member?.sourcePolicyRevision ?: wal.sourcePolicyRevision) &&
		action.consentEpoch == (member?.consentEpoch ?: wal.captureConsentEpoch) && originMatches(action, run, manifest)

	fun originMatches(action: LifecycleDesiredActionEntity, run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity): Boolean = action.startOrigin == manifest.startOrigin &&
			(manifest.manifestRevision != run.preparedManifestRevision || action.startOrigin == run.startOrigin)

	private fun providerMatches(action: LifecycleDesiredActionEntity, wal: SourceEventWalEntity,
		member: SourceAuthorizationEntity?): Boolean = action.bootId == wal.clockDomainId &&
			action.leaseGeneration == (member?.lifecycleLeaseGeneration ?: wal.lifecycleLeaseGeneration) &&
			action.sourceInstanceId == wal.sourceInstanceId && action.registrationGeneration == wal.registrationGeneration

	private fun timingMatches(action: LifecycleDesiredActionEntity, manifest: SessionManifestVersionEntity,
		timing: WifiMaintenanceActionTiming, acknowledgedWall: Long, acknowledgedElapsed: Long): Boolean =
		action.requestedAtMs == manifest.effectiveWallTimeMs && acknowledgedWall >= action.requestedAtMs &&
		action.requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		action.requestedElapsedRealtimeNanos <= timing.appliedAtElapsedNanos &&
		timing.appliedAtElapsedNanos <= acknowledgedElapsed && timing.appliedAtElapsedNanos <= timing.firstQualifiedNanos
}

private object WifiMaintenanceReplacementChecks {
	fun matches(action: LifecycleDesiredActionEntity, session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity,
		source: SessionManifestSourceEntity): Boolean {
		val acknowledgedWall = action.acknowledgedAtMs ?: return false
		val acknowledgedElapsed = action.acknowledgedElapsedRealtimeNanos ?: return false
		val provider = currentProvider(action) ?: return false
		return WifiMaintenanceActionChecks.accepted(action) && provider.first.isNotBlank() && provider.second > 0L &&
			scopeMatches(action, session, run, manifest, source) &&
			WifiMaintenanceActionChecks.originMatches(action, run, manifest) && clockMatches(action, session, run) &&
			timingMatches(action, run, manifest, acknowledgedWall, acknowledgedElapsed)
	}

	private fun currentProvider(action: LifecycleDesiredActionEntity): Pair<String, Long>? {
		val instance = action.sourceInstanceId ?: return null
		val registration = action.registrationGeneration ?: return null
		return instance to registration
	}

	private fun scopeMatches(action: LifecycleDesiredActionEntity, session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity, manifest: SessionManifestVersionEntity, source: SessionManifestSourceEntity): Boolean =
		action.logicalTrackingId == session.logicalTrackingId && action.serviceRunId == run.serviceRunId &&
		action.manifestRevision == manifest.manifestRevision &&
			action.desiredPlanRevision == manifest.acquisitionPlanRevision &&
		action.sourcePolicyRevision == manifest.sourcePolicyRevision && action.consentEpoch == source.consentEpoch

	private fun clockMatches(action: LifecycleDesiredActionEntity, session: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity): Boolean = action.bootId == run.bootId && action.bootId == session.lifecycleBootId &&
			action.leaseGeneration == run.leaseGeneration && action.leaseGeneration == session.lifecycleLeaseGeneration

	private fun timingMatches(action: LifecycleDesiredActionEntity, run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity, acknowledgedWall: Long, acknowledgedElapsed: Long): Boolean =
		action.requestedAtMs == manifest.effectiveWallTimeMs &&
			action.requestedElapsedRealtimeNanos == manifest.effectiveElapsedRealtimeNanos &&
		action.requestedAtMs >= run.startedAtMs && action.requestedElapsedRealtimeNanos >= run.startedElapsedNanos &&
		acknowledgedWall >= action.requestedAtMs && acknowledgedElapsed >= action.requestedElapsedRealtimeNanos
}

internal fun SessionManifestSourceEntity.isExactWifiWriter(consentEpoch: Long, sessionMode: String): Boolean =
	WifiMaintenanceProviderChecks.writerMatches(this, consentEpoch, sessionMode)

internal fun Set<SourceKind>.toStableCodes(): String = map(SourceKind::stableCode).sorted().joinToString(",")

internal fun LogicalTrackingSessionEntity.hasValidLifecycleShape(
	admissionOrdinal: Long, perReading: Boolean, cutoffClockDomainId: String?,
): Boolean = WifiMaintenanceSessionChecks.matches(this, admissionOrdinal, perReading, cutoffClockDomainId)

internal fun SourceServiceRunEntity.hasValidLifecycleShape(): Boolean = WifiMaintenanceRunChecks.lifecycleMatches(this)

internal fun SourceServiceRunEntity.hasValidRelationshipTo(
	session: LogicalTrackingSessionEntity, currentRun: SourceServiceRunEntity?,
): Boolean = WifiMaintenanceRunChecks.relationshipMatches(this, session, currentRun)

internal fun SourceServiceRunEntity.hasValidCurrentRelationshipTo(session: LogicalTrackingSessionEntity): Boolean =
	WifiMaintenanceRunChecks.currentMatches(this, session)

/** Historical session state, including the existing interrupted-restore and cutoff-clock rules. */
private object WifiMaintenanceSessionChecks {
	fun matches(
		session: LogicalTrackingSessionEntity,
		ordinal: Long,
		perReading: Boolean,
		cutoffClock: String?,
	): Boolean {
		if (!identityMatches(session)) { return false }
		val restored = hasRestoredFailure(session) && session.completedAtMs == null
		val terminal = session.state in TERMINAL_SESSION_STATES && !restored
		if (!completionMatches(session, terminal)) { return false }
		if (!cutoffMatches(session, terminal, restored, cutoffClock)) { return false }
		return stateMatches(session, ordinal, perReading, terminal)
	}

	private fun identityMatches(session: LogicalTrackingSessionEntity): Boolean = session.state in ALL_SESSION_STATES &&
		session.lifecycleRevision > 0L && session.desiredPlanRevision > 0L && session.startedAtMs >= 0L &&
		session.startedElapsedNanos >= 0L && session.lifecycleLeaseGeneration > 0L && session.clockDomainId.isNotBlank() &&
		!session.lifecycleBootId.isNullOrBlank() && session.currentManifestRevision?.let { it > 0L } == true

	fun hasRestoredFailure(session: LogicalTrackingSessionEntity): Boolean =
		session.state == "FAILED" && session.failureCode == "RESTORED_INTERRUPTED_SESSION"

	private fun completionMatches(session: LogicalTrackingSessionEntity, terminal: Boolean): Boolean =
		(session.completedAtMs != null) == terminal && session.completedAtMs?.let { it >= session.startedAtMs } != false &&
		(session.hasInterruptedWifiWalEnd() || (session.cutoffAtMs == null) == (session.cutoffElapsedNanos == null))

	private fun cutoffMatches(session: LogicalTrackingSessionEntity, terminal: Boolean, restored: Boolean,
		cutoffClock: String?): Boolean {
		if (terminal || session.state == "STOPPING" || restored && session.cutoffAtMs != null) {
			return cutoffBoundsMatch(session, cutoffClock)
		}
		return restored || session.cutoffAtMs == null && session.cutoffElapsedNanos == null
	}

	private fun cutoffBoundsMatch(session: LogicalTrackingSessionEntity, cutoffClock: String?): Boolean =
		session.cutoffAtMs != null && (session.hasInterruptedWifiWalEnd() || session.cutoffElapsedNanos != null) &&
		session.cutoffAtMs?.let { it >= session.startedAtMs } == true &&
		(session.cutoffElapsedNanos == null || session.cutoffElapsedNanos?.let { it >= 0L &&
			(cutoffClock != session.clockDomainId || it >= session.startedElapsedNanos) } == true)

	private fun stateMatches(session: LogicalTrackingSessionEntity, ordinal: Long, perReading: Boolean,
		terminal: Boolean): Boolean = if (terminal) {
		session.currentServiceRunId == null && (session.hasInterruptedWifiWalEnd() && session.finalAdmissionOrdinal == null ||
			session.finalAdmissionOrdinal?.let { it >= 0L && (perReading || it >= ordinal) } == true)
	} else { !session.currentServiceRunId.isNullOrBlank() && session.finalAdmissionOrdinal == null }
}

private object WifiMaintenanceRunChecks {
	fun lifecycleMatches(run: SourceServiceRunEntity): Boolean {
		if (!identityMatches(run)) { return false }
		val restored = run.state == "FAILED" && run.completionReason == "RESTORED_INTERRUPTED_SESSION" &&
			run.completedAtMs == null
		val terminal = run.state in TERMINAL_SESSION_STATES && !restored
		return (run.completedAtMs != null) == terminal && run.completedAtMs?.let { it >= run.startedAtMs } != false
	}

	private fun identityMatches(run: SourceServiceRunEntity): Boolean = run.state in ALL_SESSION_STATES &&
		run.desiredPlanRevision > 0L && run.startedAtMs >= 0L && run.startedElapsedNanos >= 0L &&
		run.leaseGeneration > 0L && run.bootId.isNotBlank()

	fun relationshipMatches(run: SourceServiceRunEntity, session: LogicalTrackingSessionEntity,
		currentRun: SourceServiceRunEntity?): Boolean = when {
		run.logicalTrackingId != session.logicalTrackingId -> false
		WifiMaintenanceSessionChecks.hasRestoredFailure(session) -> restoredRelationshipMatches(run, session)
		run.state !in TERMINAL_SESSION_STATES -> currentMatches(run, session)
		session.state in TERMINAL_SESSION_STATES -> session.currentServiceRunId == null
		session.currentServiceRunId == run.serviceRunId -> false
		else -> currentRun?.hasValidLifecycleShape() == true && currentMatches(currentRun, session)
	}

	private fun restoredRelationshipMatches(
		run: SourceServiceRunEntity,
		session: LogicalTrackingSessionEntity,
	): Boolean =
		run.state in TERMINAL_SESSION_STATES &&
			((run.completedAtMs != null && session.currentServiceRunId != run.serviceRunId) ||
				(run.completionReason == "RESTORED_INTERRUPTED_SESSION" && session.currentServiceRunId == run.serviceRunId &&
					run.bootId == session.lifecycleBootId && run.leaseGeneration == session.lifecycleLeaseGeneration))

	fun currentMatches(run: SourceServiceRunEntity, session: LogicalTrackingSessionEntity): Boolean =
		currentOwnerMatches(run, session) && currentPlanMatches(run, session) && currentStatesMatch(run, session)

	private fun currentOwnerMatches(run: SourceServiceRunEntity, session: LogicalTrackingSessionEntity): Boolean =
		run.logicalTrackingId == session.logicalTrackingId && run.state !in TERMINAL_SESSION_STATES &&
		session.state !in TERMINAL_SESSION_STATES && session.currentServiceRunId == run.serviceRunId &&
		run.bootId == session.lifecycleBootId && run.leaseGeneration == session.lifecycleLeaseGeneration

	private fun currentPlanMatches(run: SourceServiceRunEntity, session: LogicalTrackingSessionEntity): Boolean =
		run.desiredPlanRevision == session.desiredPlanRevision &&
			run.preparedManifestRevision == session.currentManifestRevision &&
		run.startedAtMs >= session.startedAtMs && run.startedElapsedNanos >= session.startedElapsedNanos

	private fun currentStatesMatch(run: SourceServiceRunEntity, session: LogicalTrackingSessionEntity): Boolean =
		(session.state in ADMISSION_SESSION_STATES && run.state in ADMISSION_RUN_STATES) ||
		(session.state == "ACTIVE" && run.state == "STOPPING") || (session.state == "STOPPING" && run.state == "STOPPING")
}

internal fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

internal fun Long.isKnownCapturedWriterOwnerGeneration(): Boolean =
	SourceWriterGenerationContract.bindingGenerationForCanonicalOwner(this)
		?.let(SourceWriterGenerationContract::captureModeMaskForKnownBinding) != null
