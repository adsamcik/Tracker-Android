package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.CanonicalRetentionBounds
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import java.time.DateTimeException
import java.time.ZoneId

internal fun collectPressureRetentionBindings(
	runs: List<SourceServiceRunEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	sourcesByManifest: Map<PressureRetentionManifestKey, List<SessionManifestSourceEntity>>,
): Map<PressureRetentionManifestKey, SessionManifestSourceEntity> {
	val pressureBindings = mutableMapOf<PressureRetentionManifestKey, SessionManifestSourceEntity>()
	for (run in runs) {
		val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
		check(runManifests.size <= PRESSURE_RETENTION_MAX_MANIFESTS_PER_RUN &&
			SessionManifestIntegrity.hasValidServiceRunTimeline(run, runManifests)
		) { "Pressure retention service-run manifest timeline is invalid" }
		for (manifest in runManifests) {
			val key = PressureRetentionManifestKey(
				manifest.logicalTrackingId,
				manifest.manifestRevision,
			)
			val membership = sourcesByManifest[key].orEmpty()
			check(hasPressureRetentionManifestAttribution(manifest, run, membership)) {
				"Pressure retention manifest attribution is invalid"
			}
			val capture = membership.singleOrNull { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
					source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
			}
			if (capture?.persistenceEligible == true) {
				pressureBindings[key] = capture
			}
		}
	}
	return pressureBindings
}

private fun hasPressureRetentionManifestAttribution(
	manifest: SessionManifestVersionEntity,
	run: SourceServiceRunEntity,
	membership: List<SessionManifestSourceEntity>,
): Boolean = manifest.logicalTrackingId == run.logicalTrackingId &&
	manifest.serviceRunId == run.serviceRunId && membership.size <= PRESSURE_RETENTION_MAX_SOURCES_PER_MANIFEST &&
	SessionManifestIntegrity.verify(manifest, membership) && hasValidZone(manifest.zoneId)

// All already loaded authority tables and original writer/epoch remain explicit for one complete run mapping.
@Suppress("LongParameterList")
internal fun createPressureRetentionAuthorities(
	runs: List<SourceServiceRunEntity>,
	manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
	pressureBindings: Map<PressureRetentionManifestKey, SessionManifestSourceEntity>,
	policiesByRevision: Map<Long, SourcePolicyEntity>,
	consentsByEpoch: Map<Long, SourceConsentEpochEntity>,
	collectedDataEpoch: Long,
	expectedWriter: PressureCandidateWriterAuthority,
): Map<String, PressureRetentionAuthority> {
	return runs.associate { run ->
		val runManifests = manifestsByRun.getValue(run.serviceRunId)
		val manifestsByRevision = runManifests.associateBy(SessionManifestVersionEntity::manifestRevision)
		check(manifestsByRevision.size == runManifests.size) {
			"Pressure retention manifest revisions are ambiguous"
		}
		val runBindings = pressureBindings.filterKeys { key ->
			key.logicalTrackingId == run.logicalTrackingId &&
				key.manifestRevision in manifestsByRevision
		}.mapKeys { entry -> entry.key.manifestRevision }
		for ((revision, binding) in runBindings) {
			val manifest = manifestsByRevision.getValue(revision)
			check(hasValidPressureCaptureAuthority(
				policy = policiesByRevision[manifest.sourcePolicyRevision],
				consent = consentsByEpoch[binding.consentEpoch],
				manifestPolicyRevision = manifest.sourcePolicyRevision,
				binding = binding,
				expectedWriter = expectedWriter,
			)) { "Pressure retention capture authority is invalid" }
		}
		run.serviceRunId to PressureRetentionAuthority(
			run = run,
			manifests = manifestsByRevision,
			bindings = runBindings,
			collectedDataEpoch = collectedDataEpoch,
		)
	}
}

@Suppress("ComplexCondition", "CyclomaticComplexMethod")
private fun hasValidPressureCaptureAuthority(
	policy: SourcePolicyEntity?,
	consent: SourceConsentEpochEntity?,
	manifestPolicyRevision: Long,
	binding: SessionManifestSourceEntity,
	expectedWriter: PressureCandidateWriterAuthority = DEFAULT_PRESSURE_CANDIDATE_WRITER,
): Boolean = policy != null && policy.policyRevision == manifestPolicyRevision &&
	policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE && policy.enabled &&
	policy.capturePersistenceEligible && policy.captureConsentEpoch == binding.consentEpoch &&
	policy.qosCode == binding.qosCode && binding.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
	binding.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && binding.persistenceEligible &&
	binding.qosCode in MIN_CAPTURE_QOS..MAX_CAPTURE_QOS &&
	binding.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_PRESSURE &&
	binding.writerOwner == SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS &&
	binding.writerOwnerGeneration == expectedWriter.ownerGeneration &&
	binding.writerProjectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
	binding.writerProjectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
	binding.writerBindingGeneration == expectedWriter.bindingGeneration &&
	consent != null && consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
	consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE && consent.epoch == binding.consentEpoch &&
	consent.eligible && consent.persistenceEligible && consent.policyRevision <= manifestPolicyRevision

internal fun hasValidPressureFactIdentity(
	fact: PressureFactRevisionEntity,
	binding: SessionManifestSourceEntity,
	requiresOriginalLogicalFactId: Boolean,
	expectedWriter: PressureCandidateWriterAuthority = DEFAULT_PRESSURE_CANDIDATE_WRITER,
): Boolean {
	val expectedLogicalFactId =
		"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:${fact.sourceEventId}"
	return fact.writerProjectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
		fact.writerProjectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
		fact.writerBindingGeneration == expectedWriter.bindingGeneration &&
		(!requiresOriginalLogicalFactId ||
			(fact.semanticRevision == 1L && fact.logicalFactId == expectedLogicalFactId)) &&
		fact.mutationId == "${fact.logicalFactId}:${fact.semanticRevision}" &&
		PressureFactRevisionIntegrity.hasValidEffectChecksum(fact, binding)
}

internal fun authenticatePressureRetentionLineage(
	revisions: List<PressureFactRevisionEntity>,
	walByEventId: Map<String, SourceEventWalEntity>,
	bindingsByManifestRevision: Map<Long, SessionManifestSourceEntity>,
): Boolean =
	PressureFactProofAuthenticator.authenticateStoredLineage(
		revisions = revisions,
		walByEventId = walByEventId,
		bindingsByManifestRevision = bindingsByManifestRevision,
	) is PressureFactLineageProof.Authenticated

private fun hasValidZone(zoneId: String): Boolean = try {
	ZoneId.of(zoneId)
	true
} catch (_: DateTimeException) {
	false
}

internal fun earliestPossiblePressureWallTimeMs(
	wallTimeMs: Long,
	uncertaintyMs: Long,
): Long = requireNotNull(CanonicalRetentionBounds.pressure(wallTimeMs, uncertaintyMs))

// A retired saved lane must retain its complete contained-writer, cutoff and terminal-time shape.
@Suppress("CyclomaticComplexMethod")
internal fun SourceProductProjectionLaneEntity.hasInvalidContainedPressureEraseShape(
	cutoffOrdinal: Long?,
	terminalAtMs: Long?,
): Boolean =
	this.captureModeMask <= 0L ||
	this.productStage != SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL ||
	this.activatedRolloutRevision <= 0L ||
	this.activationOrdinal <= 0L ||
	this.status != SourceProductProjectionLaneEntity.STATUS_RETIRED ||
	this.retentionRequired ||
	this.terminalDisposition !=
		SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN ||
	terminalAtMs == null ||
	cutoffOrdinal == null ||
	cutoffOrdinal < this.activationOrdinal - 1L ||
	this.contiguousAdmissionOrdinal != cutoffOrdinal ||
	this.installedAtMs < 0L ||
	this.updatedAtMs < this.installedAtMs ||
	terminalAtMs < this.installedAtMs ||
	this.updatedAtMs < terminalAtMs

internal fun PressureSourceEraseLocalAudit.hasCanonicalPressurePayload(): Boolean =
	factRevisionCount > 0 || retentionAuthorityCount > 0 || retentionRevisionAuthorityCount > 0 || walEventCount > 0

internal fun authenticatePressureEraseWalScope(
	wal: SourceEventWalEntity,
	authority: PressureRetentionAuthority,
) {
	val manifest = authority.manifests[wal.sessionManifestRevision]
		?: failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
	val binding = authority.bindings[manifest.manifestRevision]
		?: failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
	val nextManifest = authority.manifests.values
		.filter { it.manifestRevision > manifest.manifestRevision }
		.minByOrNull { it.manifestRevision }
	if (!wal.matchesPressureEraseCaptureIdentity(authority.run, manifest, binding) ||
		requireNotNull(wal.observedIntervalStartNanos) < manifest.effectiveElapsedRealtimeNanos ||
		nextManifest?.let {
			wal.observedElapsedNanos > it.effectiveElapsedRealtimeNanos
		} == true
	) {
		failPressureErase(PressureSourceEraseLocalFailureReason.FACT_AUTHORITY_UNVERIFIABLE)
	}
}

private fun SourceEventWalEntity.matchesPressureEraseCaptureIdentity(
	run: SourceServiceRunEntity,
	manifest: SessionManifestVersionEntity,
	binding: SessionManifestSourceEntity,
): Boolean = logicalTrackingId == run.logicalTrackingId && serviceRunId == run.serviceRunId &&
	clockDomainId == run.bootId && sourcePolicyRevision == manifest.sourcePolicyRevision &&
	captureConsentEpoch == binding.consentEpoch

internal fun boundedPressureEraseInt(value: Long, maximum: Int): Int {
	if (value < 0L || value > maximum.toLong()) {
		failPressureErase(PressureSourceEraseLocalFailureReason.MAINTENANCE_BOUND_EXCEEDED)
	}
	return value.toInt()
}

internal fun failPressureErase(reason: PressureSourceEraseLocalFailureReason): Nothing =
	throw PressureSourceEraseLocalFailure(reason)

private const val MIN_CAPTURE_QOS = 1
private const val MAX_CAPTURE_QOS = 3
