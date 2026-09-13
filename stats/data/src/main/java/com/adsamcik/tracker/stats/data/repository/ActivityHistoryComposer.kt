package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFactIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedPlanIntegrity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedRegistrationPlanEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowCursorEntity
import com.adsamcik.tracker.shared.base.database.data.ActivityCapturedWindowRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceBrokerPurpose
import com.adsamcik.tracker.shared.base.database.data.SourceAuthorizationEntity
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.stats.api.repository.ActivityActiveTime
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCause
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryConfidence
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntry
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryFragment
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryGapReason
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryMechanism
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryProductState
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryType
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryWallTimeContinuity
import com.adsamcik.tracker.stats.api.value.EpochMs
import java.time.DateTimeException
import java.time.ZoneId

/** Pure fail-closed composition of one bounded, transactionally loaded Activity snapshot. */
@Suppress("LargeClass", "TooManyFunctions")
internal object ActivityHistoryComposer {
	fun composeSelected(
		seed: SessionSegment,
		snapshot: ActivityHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): ActivityHistoryEntry? {
		val logicalId = seed.logicalTrackingId?.takeIf(String::isNotBlank)
			?: return legacy(seed)
		if (seed.serviceRunId.isNullOrBlank()) return failed(logicalId, listOf(seed),
			ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		return composeGroup(logicalId, snapshot, laneExecutionAuthority)
	}

	fun composeRecent(
		snapshot: ActivityHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): List<ComposedActivityEntry> =
		snapshot.expansion.segments.asSequence()
			.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank)
			.distinct()
			.mapNotNull { logicalId ->
				val members = members(logicalId, snapshot)
				val entry = composeGroup(logicalId, snapshot, laneExecutionAuthority) ?: return@mapNotNull null
				ComposedActivityEntry(
					logicalTrackingId = logicalId,
					recencyStartTimeMs = members.maxOfOrNull(SessionSegment::startTimeMs) ?: 0L,
					recencySegmentId = members.maxOfOrNull(SessionSegment::id) ?: 0L,
					entry = entry,
				)
			}.toList()

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun composeGroup(
		logicalId: String,
		snapshot: ActivityHistorySnapshot,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): ActivityHistoryEntry? {
		val segments = members(logicalId, snapshot)
		if (segments.isEmpty()) return null
		if (snapshot.overflow) return failed(logicalId, segments, ActivityHistoryCause.READ_BUDGET_EXCEEDED)
		snapshot.expansion.failures[logicalId]?.let { return failed(logicalId, segments, it) }

		val runIds = segments.mapNotNull(SessionSegment::serviceRunId)
		if (runIds.size != segments.size || runIds.distinct().size != runIds.size) {
			return failed(logicalId, segments, ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val runs = runIds.mapNotNull(snapshot.runs::get)
		if (runs.size != runIds.size || runs.any { run -> !runOwnsExactSegment(run, segments, logicalId) }) {
			return failed(logicalId, segments, ActivityHistoryCause.PHYSICAL_MEMBERSHIP_INVALID)
		}
		val hasActiveRun = runs.any { it.completedAtMs == null || it.state !in TERMINAL_RUN_STATES }
		val session = snapshot.sessions[logicalId]
		if (session == null || session.logicalTrackingId != logicalId ||
			(session.state !in TERMINAL_RUN_STATES) != hasActiveRun ||
			session.lifecycleRevision <= 0L || session.desiredPlanRevision <= 0L ||
			session.rolloutRevision <= 0L || session.startedElapsedNanos < 0L ||
			session.cutoffElapsedNanos?.let { it < session.startedElapsedNanos } == true
		) return failed(logicalId, segments, ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		val manifestsByRun = runs.associate { run ->
			run.serviceRunId to snapshot.manifestsByRun[run.serviceRunId].orEmpty()
				.sortedBy(SessionManifestVersionEntity::manifestRevision)
		}
		if (!hasValidManifestHistory(logicalId, runs, manifestsByRun, snapshot)) {
			return failed(logicalId, segments, ActivityHistoryCause.MANIFEST_INTEGRITY_FAILED)
		}
		val manifests = manifestsByRun.values.flatten().associateBy(SessionManifestVersionEntity::manifestRevision)
		val zones = manifests.values.mapTo(linkedSetOf(), SessionManifestVersionEntity::zoneId)
		if (zones.any { !isValidZone(it) }) {
			return failed(logicalId, segments, ActivityHistoryCause.STORED_ZONE_INVALID)
		}
		val bindings = manifests.values.mapNotNull { manifest ->
			val matching = snapshot.sourcesByManifest[ActivityManifestKey(logicalId, manifest.manifestRevision)]
				.orEmpty().filter(::isActivityCaptureMembership)
			if (matching.size > 1) return failed(logicalId, segments,
				ActivityHistoryCause.MANIFEST_INTEGRITY_FAILED)
			matching.singleOrNull()?.let { manifest.manifestRevision to it }
		}.toMap()
		val relevantLineages = snapshot.revisions.groupBy(ActivityCapturedWindowRevisionEntity::logicalWindowId)
			.filterValues { lineage -> lineage.any { it.logicalTrackingId == logicalId || it.serviceRunId in runIds } }
		val allRevisions = relevantLineages.values.flatten()
		if (bindings.isEmpty()) {
			return if (allRevisions.isEmpty()) unavailable(logicalId, segments, zones,
				ActivityHistoryCause.SOURCE_NOT_CAPTURED) else failed(logicalId, segments,
				ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (!hasValidCaptureAuthority(manifests, bindings, snapshot)) {
			return failed(logicalId, segments, ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		val lane = snapshot.lanes.singleOrNull(::isActivityLane)
		val captureModeMask = manifests.values.fold(0L) { mask, manifest ->
			mask or when (manifest.sessionMode) {
				"MANUAL" -> MANUAL_SESSION_CAPTURE_MASK
				"AUTOMATIC" -> AUTOMATIC_SESSION_CAPTURE_MASK
				else -> 0L
			}
		}
		if (lane == null || !laneExecutionAuthority.owns(lane) || !isValidHistoricalLane(lane) ||
			captureModeMask == 0L || lane.captureModeMask and captureModeMask != captureModeMask ||
			manifests.values.any { lane.activatedRolloutRevision > it.rolloutRevision }) {
			return failed(logicalId, segments, ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		if (segments.any { segment -> deletionDigest(logicalId, requireNotNull(segment.serviceRunId)) in
				snapshot.deletionFenceDigests }) {
			return unavailable(logicalId, segments, zones, ActivityHistoryCause.RETENTION_LIMIT)
		}
		val evidenceState = snapshot.evidenceState
			?: return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		if (allRevisions.any { it.logicalTrackingId != logicalId || it.serviceRunId !in runIds }) {
			return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		}
		val active = hasActiveRun
		val completeness = runs.flatMap { snapshot.completenessByRun[it.serviceRunId].orEmpty() }
			.filter { it.sourceKind == ACTIVITY_SOURCE }
		if (!hasValidCompleteness(completeness, logicalId, runIds)) {
			return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (!hasValidCompletenessAuthority(completeness, snapshot)) {
			return failed(logicalId, segments, ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		}
		if (!active && completeness.mapTo(hashSetOf(), SourceSessionCompletenessEntity::serviceRunId) !=
			runIds.toSet()
		) return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		val targetByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId)
			.mapValues { (_, rows) -> rows.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).maxOrNull() }
		val laneTarget = targetByRun.values.filterNotNull().maxOrNull()
		if (!hasValidLogicalSessionSettlement(session, active, laneTarget) ||
			laneTarget?.let { it < lane.activationOrdinal ||
			lane.captureAdmissionCutoffOrdinal?.let { cutoff -> it > cutoff } == true } == true ||
			snapshot.terminalFailures.isNotEmpty()
		) return failed(logicalId, segments, ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
		val effective = mutableListOf<ActivityCapturedWindowRevisionEntity>()
		for ((_, lineage) in relevantLineages) {
			val ordered = lineage.sortedBy(ActivityCapturedWindowRevisionEntity::semanticRevision)
			val latest = validateLineage(ordered, snapshot)
				?: return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
			if (latest.collectedDataEpoch != evidenceState.collectedDataEpoch) {
				return unavailable(logicalId, segments, zones, ActivityHistoryCause.PRIVACY_EPOCH_MISMATCH)
			}
			val manifest = manifests[latest.manifestRevision]
			val binding = bindings[latest.manifestRevision]
			val run = snapshot.runs[latest.serviceRunId]
			val plan = snapshot.registrationPlans[latest.sourceInstanceId to latest.registrationGeneration]
			val desiredPlan = snapshot.desiredPlans[latest.configurationRevision]
			val provider = snapshot.providerRegistrations[latest.registrationGeneration]
			val authorizations = snapshot.authorizationsByRegistration[latest.registrationGeneration].orEmpty()
			val nextManifest = manifests.values.singleOrNull { candidate ->
				candidate.serviceRunId == latest.serviceRunId &&
					candidate.manifestRevision == latest.manifestRevision + 1L
			}
			val expectedSessionRunEnd = session.cutoffElapsedNanos
				.takeIf { session.lifecycleBootId == latest.clockDomainId } ?: Long.MAX_VALUE
			if (manifest == null || binding == null || run == null || plan == null || desiredPlan == null ||
				provider == null ||
				!factHasExactAuthority(
					latest, manifest, nextManifest, binding, run, plan, desiredPlan, provider,
					authorizations, expectedSessionRunEnd,
				)) {
				return failed(logicalId, segments, ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			val factEvidence = snapshot.evidenceByRevision[revisionKey(latest)].orEmpty()
			val targetOrdinal = targetByRun[latest.serviceRunId]
			if (targetOrdinal == null || factEvidence.any { row -> row.sourceAdmissionOrdinal < lane.activationOrdinal ||
				row.sourceAdmissionOrdinal > targetOrdinal }) {
				return failed(logicalId, segments, ActivityHistoryCause.WRITER_PROVENANCE_INVALID)
			}
			effective += latest
		}
		val retainedFrom = evidenceState.retainedFromMs
		val crossesRetention = try {
			retainedFrom != null && effective.any { revision ->
				snapshot.fragmentsByRevision[revisionKey(revision)].orEmpty().any { fragment ->
					fragment.fragmentKind == ActivityCapturedFragmentEntity.KIND_BAND &&
						minimumPossibleWallTime(fragment) < retainedFrom
				}
			}
		} catch (_: ArithmeticException) {
			return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (crossesRetention) {
			return unavailable(logicalId, segments, zones, ActivityHistoryCause.RETENTION_LIMIT)
		}
		if (effective.isNotEmpty() && completeness.any { it.registrationGeneration == 0L }) {
			return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		}
		if (effective.isEmpty()) {
			return when {
				active -> materializing(logicalId, segments, zones)
				completeness.any { it.stopStatus in PROVIDER_UNAVAILABLE_STATUSES } ->
					unavailable(logicalId, segments, zones, ActivityHistoryCause.PROVIDER_UNAVAILABLE)
				else -> unavailable(logicalId, segments, zones, ActivityHistoryCause.NO_QUALIFIED_FACTS)
			}
		}
		val ordered = effective.sortedWith(compareBy(
			{ snapshot.runs[it.serviceRunId]?.startedAtMs ?: Long.MAX_VALUE },
			ActivityCapturedWindowRevisionEntity::windowStartElapsedRealtimeNanos,
			ActivityCapturedWindowRevisionEntity::logicalWindowId,
		))
		if (ordered.groupBy(ActivityCapturedWindowRevisionEntity::serviceRunId).values.any { revisions ->
			revisions.zipWithNext().any { (left, right) ->
				left.windowEndElapsedRealtimeNanos > right.windowStartElapsedRealtimeNanos
			}
		}) return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		val timeline = runCatching {
			composeCaptureTimeline(ordered, manifests.values.sortedBy { it.manifestRevision }, bindings, session,
				snapshot, active)
		}.getOrNull() ?: return failed(logicalId, segments, ActivityHistoryCause.FACT_INTEGRITY_FAILED)
		val fragments = timeline.fragments
		val activeTime = try {
			ActivityActiveTime(
				ordered.sumExact(ActivityCapturedWindowRevisionEntity::knownActiveDurationNanos),
				ordered.sumExact(ActivityCapturedWindowRevisionEntity::knownInactiveDurationNanos),
				ordered.sumExact(ActivityCapturedWindowRevisionEntity::unknownActivityDurationNanos),
				Math.addExact(
					ordered.sumExact(ActivityCapturedWindowRevisionEntity::unobservedDurationNanos),
					timeline.externalGapDurationNanos,
				),
			)
		} catch (_: ArithmeticException) {
			return failed(logicalId, segments, ActivityHistoryCause.VALUE_OVERFLOW)
		}
		val causes = linkedSetOf<ActivityHistoryCause>()
		if (bindings.size != manifests.size) causes += ActivityHistoryCause.ACQUISITION_INCOMPLETE
		if (completeness.isEmpty() || completeness.any { !it.appDrainComplete || it.stopStatus != COMPLETE_STOP }) {
			causes += ActivityHistoryCause.ACQUISITION_INCOMPLETE
		}
		if (fragments.any { it is ActivityHistoryFragment.Gap } ||
			completeness.any { it.providerCoverage == UNOBSERVABLE_COVERAGE }) {
			causes += ActivityHistoryCause.PROVIDER_GAP
		}
		val laneBehind = laneTarget != null && lane.contiguousAdmissionOrdinal < laneTarget
		if (laneBehind) causes += ActivityHistoryCause.MATERIALIZATION_BEHIND
		val coverage = if (ordered.all { it.coverage == "COMPLETE" } && timeline.externalGapDurationNanos == 0L &&
			ActivityHistoryCause.ACQUISITION_INCOMPLETE !in causes) {
			ActivityHistoryCoverage.COMPLETE
		} else ActivityHistoryCoverage.PARTIAL
		val state = when {
			active || laneBehind -> ActivityHistoryProductState.MATERIALIZING
			ActivityHistoryCause.ACQUISITION_INCOMPLETE in causes ||
				ActivityHistoryCause.PROVIDER_GAP in causes -> ActivityHistoryProductState.PARTIAL
			else -> ActivityHistoryProductState.READY
		}
		if (active) causes += ActivityHistoryCause.SESSION_ACTIVE
		return entry(logicalId, segments, zones, state, coverage, activeTime, fragments, causes)
	}

	private fun members(logicalId: String, snapshot: ActivityHistorySnapshot): List<SessionSegment> =
		snapshot.expansion.segments.filter { it.logicalTrackingId == logicalId }
			.sortedWith(compareBy(SessionSegment::startTimeMs, SessionSegment::id))

	private fun runOwnsExactSegment(
		run: SourceServiceRunEntity,
		segments: List<SessionSegment>,
		logicalId: String,
	): Boolean {
		val segment = segments.singleOrNull { it.id == run.sessionSegmentId } ?: return false
		return run.logicalTrackingId == logicalId && segment.serviceRunId == run.serviceRunId &&
			run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
	}

	private fun hasValidLogicalSessionSettlement(
		session: com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity,
		active: Boolean,
		targetOrdinal: Long?,
	): Boolean = if (active) {
		session.cutoffAtMs == null && session.cutoffElapsedNanos == null &&
			session.completedAtMs == null && session.finalAdmissionOrdinal == null &&
			!session.currentServiceRunId.isNullOrBlank()
	} else {
		session.cutoffAtMs != null && session.cutoffElapsedNanos != null &&
			session.completedAtMs != null && session.completedAtMs >= session.startedAtMs &&
		session.finalAdmissionOrdinal?.let { finalOrdinal ->
			targetOrdinal == null || targetOrdinal <= finalOrdinal
		} == true &&
			session.currentServiceRunId == null
	}

	private fun hasValidManifestHistory(
		logicalId: String,
		runs: List<SourceServiceRunEntity>,
		manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		snapshot: ActivityHistorySnapshot,
	): Boolean = SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
		manifestsByRun.values.map { it.map(SessionManifestVersionEntity::manifestRevision) },
	) && runs.all { run ->
		val manifests = manifestsByRun[run.serviceRunId].orEmpty()
		SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) && manifests.all { manifest ->
			manifest.logicalTrackingId == logicalId && manifest.serviceRunId == run.serviceRunId &&
				SessionManifestIntegrity.verify(
					manifest,
					snapshot.sourcesByManifest[ActivityManifestKey(logicalId, manifest.manifestRevision)].orEmpty(),
				)
		}
	}

	private fun hasValidCaptureAuthority(
		manifests: Map<Long, SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
		snapshot: ActivityHistorySnapshot,
	): Boolean = bindings.all { (revision, binding) ->
		val manifest = manifests[revision] ?: return@all false
		val policy = snapshot.policies[manifest.sourcePolicyRevision]
		val consent = snapshot.consents[binding.consentEpoch]
		val planHeader = snapshot.acquisitionPlanRevisions[manifest.acquisitionPlanRevision]
		val desiredPlan = snapshot.desiredPlans[manifest.acquisitionPlanRevision]
		val decodedPlan = desiredPlan?.let(ActivityCapturedPlanIntegrity::decode)
		isActivityWriter(binding) && policy != null && consent != null && planHeader != null &&
			desiredPlan != null && decodedPlan != null && decodedPlan.enabled &&
			planHeader.revision == manifest.acquisitionPlanRevision &&
			planHeader.sourcePolicyRevision == manifest.sourcePolicyRevision &&
			policy.policyRevision == manifest.sourcePolicyRevision && policy.sourceKind == ACTIVITY_SOURCE &&
			policy.enabled && policy.capturePersistenceEligible && policy.qosCode == binding.qosCode &&
			policy.captureConsentLiteral() == binding.consentEpoch &&
			policy.effectiveBootId == manifest.effectiveBootId &&
			policy.effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos &&
			consent.sourceKind == ACTIVITY_SOURCE && consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			consent.epoch == binding.consentEpoch && consent.policyRevision <= policy.policyRevision &&
			consent.eligible && consent.persistenceEligible &&
			consent.effectiveBootId == manifest.effectiveBootId &&
			consent.effectiveElapsedRealtimeNanos <= manifest.effectiveElapsedRealtimeNanos
	}

	private fun SourcePolicyEntity.captureConsentLiteral(): Long? = captureConsentEpoch

	private fun isActivityWriter(binding: SessionManifestSourceEntity): Boolean =
		binding.sourceKind == ACTIVITY_SOURCE && binding.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
			binding.persistenceEligible &&
			binding.outputDestination == SourceDestinationOwnerEntity.DESTINATION_SESSION_ACTIVITY &&
			binding.writerOwner == SourceDestinationOwnerEntity.OWNER_ACTIVITY_SESSION_FACTS &&
			binding.writerOwnerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
			binding.writerProjectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID &&
			binding.writerProjectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION &&
			binding.writerBindingGeneration == SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION

	private fun validateLineage(
		ordered: List<ActivityCapturedWindowRevisionEntity>,
		snapshot: ActivityHistorySnapshot,
	): ActivityCapturedWindowRevisionEntity? {
		if (ordered.isEmpty() || ordered.map(ActivityCapturedWindowRevisionEntity::semanticRevision) !=
			(1L..ordered.size.toLong()).toList() || ordered.any { !sameCorrectionAuthority(ordered.first(), it) }) {
			return null
		}
		if (ordered.any { revision ->
			!ActivityCapturedFactIntegrity.verify(
				revision,
				snapshot.fragmentsByRevision[revisionKey(revision)].orEmpty(),
				snapshot.evidenceByRevision[revisionKey(revision)].orEmpty(),
			)
		}) return null
		val latest = ordered.last()
		val matchingCursors = snapshot.cursors.filter { cursor ->
			cursor.writerProjectionId == latest.writerProjectionId &&
				cursor.writerProjectionVersion == latest.writerProjectionVersion &&
				cursor.logicalWindowId == latest.logicalWindowId
		}
		return latest.takeIf { matchingCursors.singleOrNull()?.matches(latest) == true }
	}

	@Suppress("ComplexCondition", "LongMethod")
	private fun sameCorrectionAuthority(
		first: ActivityCapturedWindowRevisionEntity,
		candidate: ActivityCapturedWindowRevisionEntity,
	): Boolean = first.writerProjectionId == candidate.writerProjectionId &&
		first.writerProjectionVersion == candidate.writerProjectionVersion &&
		first.writerBindingGeneration == candidate.writerBindingGeneration &&
		first.writerOwnerGeneration == candidate.writerOwnerGeneration &&
		first.logicalWindowId == candidate.logicalWindowId &&
		first.logicalTrackingId == candidate.logicalTrackingId &&
		first.serviceRunId == candidate.serviceRunId && first.sessionSegmentId == candidate.sessionSegmentId &&
		first.purpose == candidate.purpose && first.sourceInstanceId == candidate.sourceInstanceId &&
		first.registrationGeneration == candidate.registrationGeneration &&
		first.configurationRevision == candidate.configurationRevision &&
		first.physicalConfigurationFingerprint == candidate.physicalConfigurationFingerprint &&
		first.authorizationRevision == candidate.authorizationRevision &&
		first.authorizationFingerprint == candidate.authorizationFingerprint &&
		first.purposeEligibilityMask == candidate.purposeEligibilityMask &&
		first.sourcePolicyRevision == candidate.sourcePolicyRevision &&
		first.captureConsentEpoch == candidate.captureConsentEpoch &&
		first.manifestRevision == candidate.manifestRevision &&
		first.lifecycleLeaseGeneration == candidate.lifecycleLeaseGeneration &&
		first.collectedDataEpoch == candidate.collectedDataEpoch &&
		first.clockDomainId == candidate.clockDomainId && first.storedZoneId == candidate.storedZoneId &&
		first.providerAcceptanceStartNanos == candidate.providerAcceptanceStartNanos &&
		first.providerAcceptanceEndNanos == candidate.providerAcceptanceEndNanos &&
		first.authorizationEffectStartNanos == candidate.authorizationEffectStartNanos &&
		first.authorizationEffectEndNanos == candidate.authorizationEffectEndNanos &&
		first.sessionRunEffectStartNanos == candidate.sessionRunEffectStartNanos &&
		first.sessionRunEffectEndNanos == candidate.sessionRunEffectEndNanos &&
		first.windowStartElapsedRealtimeNanos == candidate.windowStartElapsedRealtimeNanos &&
		first.windowEndElapsedRealtimeNanos == candidate.windowEndElapsedRealtimeNanos &&
		first.scopeDeletionGeneration == candidate.scopeDeletionGeneration

	private fun ActivityCapturedWindowCursorEntity.matches(
		revision: ActivityCapturedWindowRevisionEntity,
	): Boolean = logicalTrackingId == revision.logicalTrackingId && serviceRunId == revision.serviceRunId &&
		sessionSegmentId == revision.sessionSegmentId &&
		writerOwnerGeneration == revision.writerOwnerGeneration &&
		latestSemanticRevision == revision.semanticRevision && latestMutationId == revision.mutationId &&
		latestEffectChecksum == revision.effectChecksum && cursorRevision == revision.semanticRevision &&
		collectedDataEpoch == revision.collectedDataEpoch && updatedAtMs == revision.appliedAtMs

	@Suppress("ComplexCondition")
	private fun factHasExactAuthority(
		fact: ActivityCapturedWindowRevisionEntity,
		manifest: SessionManifestVersionEntity,
		nextManifest: SessionManifestVersionEntity?,
		binding: SessionManifestSourceEntity,
		run: SourceServiceRunEntity,
		plan: ActivityCapturedRegistrationPlanEntity,
		desiredPlan: SourceDesiredPlanEntity,
		provider: ProviderRegistrationGenerationEntity,
		authorizations: List<SourceAuthorizationEntity>,
		expectedSessionRunEndNanos: Long,
	): Boolean = fact.sessionSegmentId == run.sessionSegmentId && fact.logicalTrackingId == run.logicalTrackingId &&
		fact.serviceRunId == run.serviceRunId && fact.purpose == SourceBrokerPurpose.SESSION_CAPTURE &&
		fact.manifestRevision == manifest.manifestRevision && fact.sourcePolicyRevision == manifest.sourcePolicyRevision &&
		fact.captureConsentEpoch == binding.consentEpoch && fact.lifecycleLeaseGeneration == run.leaseGeneration &&
		fact.clockDomainId == run.bootId && manifest.effectiveBootId == run.bootId &&
		fact.storedZoneId == manifest.zoneId && fact.windowStartElapsedRealtimeNanos >= run.startedElapsedNanos &&
		fact.sessionRunEffectStartNanos == run.startedElapsedNanos &&
		fact.sessionRunEffectEndNanos == expectedSessionRunEndNanos &&
		fact.windowStartElapsedRealtimeNanos >= manifest.effectiveElapsedRealtimeNanos &&
		(nextManifest == null || fact.windowEndElapsedRealtimeNanos <=
			nextManifest.effectiveElapsedRealtimeNanos) &&
		fact.sourceInstanceId == plan.sourceInstanceId && fact.registrationGeneration == plan.registrationGeneration &&
		fact.configurationRevision == plan.configurationRevision &&
		plan.configurationRevision == manifest.acquisitionPlanRevision &&
		plan.desiredPlanPayloadVersion == desiredPlan.payloadVersion &&
		plan.desiredPlanPayloadChecksum == desiredPlan.payloadChecksum &&
		fact.physicalConfigurationFingerprint == plan.physicalConfigurationFingerprint &&
		ActivityCapturedPlanIntegrity.decode(desiredPlan)?.physicalConfigurationFingerprint ==
			plan.physicalConfigurationFingerprint &&
		plan.appliedAtElapsedRealtimeNanos <= fact.windowStartElapsedRealtimeNanos &&
		provider.sourceKind == ACTIVITY_SOURCE && provider.registrationGeneration == fact.registrationGeneration &&
		provider.sourceInstanceId == fact.sourceInstanceId && provider.clockDomainId == fact.clockDomainId &&
		provider.physicalConfigurationFingerprint == fact.physicalConfigurationFingerprint &&
		provider.collectedDataEpoch == fact.collectedDataEpoch &&
		provider.acceptedElapsedRealtimeNanos == fact.providerAcceptanceStartNanos &&
		(provider.retiredElapsedRealtimeNanos ?: Long.MAX_VALUE) == fact.providerAcceptanceEndNanos &&
		hasExactAuthorization(fact, authorizations) && isActivityWriter(binding)

	private fun hasExactAuthorization(
		fact: ActivityCapturedWindowRevisionEntity,
		rows: List<SourceAuthorizationEntity>,
	): Boolean {
		val exact = rows.filter { it.authorizationRevision == fact.authorizationRevision }
		if (exact.isEmpty() || exact.any { row ->
			row.sourceKind != ACTIVITY_SOURCE || row.registrationGeneration != fact.registrationGeneration ||
				row.authorizationFingerprint != fact.authorizationFingerprint ||
				row.purposeEligibilityMask != fact.purposeEligibilityMask ||
				row.effectiveBootId != fact.clockDomainId ||
				row.effectiveElapsedRealtimeNanos != fact.authorizationEffectStartNanos
		}) return false
		val memberMatches = exact.any { row ->
			!row.isDenyAll && row.purpose == SourceBrokerPurpose.SESSION_CAPTURE && row.persistenceEligible &&
				row.logicalTrackingId == fact.logicalTrackingId && row.serviceRunId == fact.serviceRunId &&
				row.manifestRevision == fact.manifestRevision &&
				row.lifecycleLeaseGeneration == fact.lifecycleLeaseGeneration &&
				row.sourcePolicyRevision == fact.sourcePolicyRevision && row.consentEpoch == fact.captureConsentEpoch
		}
		val nextBoundary = rows.asSequence().filter { row ->
			row.effectiveBootId == fact.clockDomainId &&
				(row.effectiveElapsedRealtimeNanos > fact.authorizationEffectStartNanos ||
					row.effectiveElapsedRealtimeNanos == fact.authorizationEffectStartNanos &&
						row.authorizationRevision > fact.authorizationRevision)
		}.minWithOrNull(compareBy(SourceAuthorizationEntity::effectiveElapsedRealtimeNanos,
			SourceAuthorizationEntity::authorizationRevision))?.effectiveElapsedRealtimeNanos ?: Long.MAX_VALUE
		return memberMatches && nextBoundary == fact.authorizationEffectEndNanos
	}

	private fun hasValidCompleteness(
		rows: List<SourceSessionCompletenessEntity>,
		logicalId: String,
		runIds: List<String>,
	): Boolean = rows.groupBy(SourceSessionCompletenessEntity::serviceRunId).all { (runId, runRows) ->
		runId in runIds && runRows.map(SourceSessionCompletenessEntity::registrationGeneration).distinct().size ==
			runRows.size && runRows.sortedBy(SourceSessionCompletenessEntity::registrationGeneration)
				.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal).zipWithNext()
				.all { (left, right) -> right > left } && runRows.all { row ->
				row.logicalTrackingId == logicalId && row.sourceInstanceId.isNotBlank() &&
					row.registrationGeneration >= 0L && row.lastAdmissionOrdinal?.let { it > 0L } != false &&
					row.lastSourceSequence?.let { it >= 0L } != false &&
					(row.unresolvedSequenceStart == null) == (row.unresolvedSequenceEnd == null) &&
					row.unresolvedSequenceStart?.let { it > 0L && it <= requireNotNull(row.unresolvedSequenceEnd) } != false &&
					row.providerCoverage in PROVIDER_COVERAGES && row.stopStatus in STOP_STATUSES &&
					(row.stopStatus != COMPLETE_STOP || row.appDrainComplete) && row.hasValidGenerationShape() &&
					row.updatedAtMs >= 0L
			}
	}

	private fun SourceSessionCompletenessEntity.hasValidGenerationShape(): Boolean {
		if (registrationGeneration > 0L) return sourceInstanceId !in SYNTHETIC_ACTIVITY_INSTANCES
		if (lastAdmissionOrdinal != null || lastSourceSequence != null || unresolvedSequenceStart != null ||
			unresolvedSequenceEnd != null || providerCoverage != UNOBSERVABLE_COVERAGE
		) return false
		return when (sourceInstanceId) {
			"not-owned-activity" -> stopStatus == COMPLETE_STOP && appDrainComplete
			"unresolved-activity" -> stopStatus in PROVIDER_UNAVAILABLE_STATUSES && !appDrainComplete
			"activity-unregistered" -> stopStatus in setOf(COMPLETE_STOP, "PROVIDER_FAILED") && appDrainComplete
			else -> false
		}
	}

	private fun hasValidCompletenessAuthority(
		rows: List<SourceSessionCompletenessEntity>,
		snapshot: ActivityHistorySnapshot,
	): Boolean = rows.filter { it.registrationGeneration > 0L }.all { row ->
		val plan = snapshot.registrationPlans[row.sourceInstanceId to row.registrationGeneration]
			?: return@all false
		val desired = snapshot.desiredPlans[plan.configurationRevision] ?: return@all false
		val decoded = ActivityCapturedPlanIntegrity.decode(desired) ?: return@all false
		val provider = snapshot.providerRegistrations[row.registrationGeneration] ?: return@all false
		plan.sourceInstanceId == row.sourceInstanceId &&
			plan.registrationGeneration == row.registrationGeneration &&
			plan.desiredPlanPayloadVersion == desired.payloadVersion &&
			plan.desiredPlanPayloadChecksum == desired.payloadChecksum && decoded.enabled &&
			plan.physicalConfigurationFingerprint == decoded.physicalConfigurationFingerprint &&
			provider.sourceKind == ACTIVITY_SOURCE && provider.sourceInstanceId == row.sourceInstanceId &&
			provider.registrationGeneration == row.registrationGeneration &&
			provider.physicalConfigurationFingerprint == plan.physicalConfigurationFingerprint &&
			provider.collectedDataEpoch == snapshot.evidenceState?.collectedDataEpoch
	}

	private fun composeCaptureTimeline(
		revisions: List<ActivityCapturedWindowRevisionEntity>,
		manifests: List<SessionManifestVersionEntity>,
		bindings: Map<Long, SessionManifestSourceEntity>,
		session: com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity,
		snapshot: ActivityHistorySnapshot,
		active: Boolean,
	): ActivityCaptureTimeline {
		val public = mutableListOf<ActivityHistoryFragment>()
		var externalGapNanos = 0L
		manifests.forEachIndexed { index, manifest ->
			if (bindings[manifest.manifestRevision] == null) return@forEachIndexed
			val next = manifests.getOrNull(index + 1)
			val exactEnd = when {
				next?.effectiveBootId == manifest.effectiveBootId -> next.effectiveElapsedRealtimeNanos
				session.lifecycleBootId == manifest.effectiveBootId -> session.cutoffElapsedNanos
				else -> null
			}
			val manifestRevisions = revisions.filter { it.manifestRevision == manifest.manifestRevision }
				.sortedBy(ActivityCapturedWindowRevisionEntity::windowStartElapsedRealtimeNanos)
			val end = exactEnd ?: if (active) {
				manifestRevisions.maxOfOrNull(ActivityCapturedWindowRevisionEntity::windowEndElapsedRealtimeNanos)
			} else null
			require(end != null && end >= manifest.effectiveElapsedRealtimeNanos)
			var cursor = manifest.effectiveElapsedRealtimeNanos
			manifestRevisions.forEach { revision ->
				require(revision.windowStartElapsedRealtimeNanos >= cursor)
				require(revision.windowEndElapsedRealtimeNanos <= end)
				if (revision.windowStartElapsedRealtimeNanos > cursor) {
					val duration = Math.subtractExact(revision.windowStartElapsedRealtimeNanos, cursor)
					externalGapNanos = Math.addExact(externalGapNanos, duration)
					public += ActivityHistoryFragment.Gap(
						storedZoneId = manifest.zoneId,
						reason = ActivityHistoryGapReason.NO_QUALIFIED_EVIDENCE,
						durationNanos = duration,
					)
				}
				public += toPublicFragments(revision, snapshot)
				cursor = revision.windowEndElapsedRealtimeNanos
			}
			if (end > cursor) {
				val duration = Math.subtractExact(end, cursor)
				externalGapNanos = Math.addExact(externalGapNanos, duration)
				public += ActivityHistoryFragment.Gap(
					storedZoneId = manifest.zoneId,
					reason = ActivityHistoryGapReason.NO_QUALIFIED_EVIDENCE,
					durationNanos = duration,
				)
			}
		}
		return ActivityCaptureTimeline(public, externalGapNanos)
	}

	private fun toPublicFragments(
		revision: ActivityCapturedWindowRevisionEntity,
		snapshot: ActivityHistorySnapshot,
	): List<ActivityHistoryFragment> = snapshot.fragmentsByRevision[revisionKey(revision)].orEmpty()
		.sortedBy(ActivityCapturedFragmentEntity::fragmentOrdinal).map { fragment ->
			val duration = Math.subtractExact(
				fragment.intervalEndElapsedRealtimeNanos,
				fragment.intervalStartElapsedRealtimeNanos,
			)
			when (fragment.fragmentKind) {
				ActivityCapturedFragmentEntity.KIND_GAP -> ActivityHistoryFragment.Gap(
					storedZoneId = revision.storedZoneId,
					reason = ActivityHistoryGapReason.valueOf(requireNotNull(fragment.gapReason)),
					durationNanos = duration,
				)
				ActivityCapturedFragmentEntity.KIND_BAND -> ActivityHistoryFragment.Band(
					storedZoneId = revision.storedZoneId,
					startTime = EpochMs(requireNotNull(fragment.startWallTimeMs)),
					endTime = EpochMs(requireNotNull(fragment.endWallTimeMs)),
					startUncertaintyMs = requireNotNull(fragment.startWallTimeUncertaintyMs),
					endUncertaintyMs = requireNotNull(fragment.endWallTimeUncertaintyMs),
					activity = ActivityHistoryType.valueOf(requireNotNull(fragment.activity)),
					mechanism = ActivityHistoryMechanism.valueOf(requireNotNull(fragment.mechanism)),
					refinedTransitionActivity = fragment.refinedTransitionActivity?.let(ActivityHistoryType::valueOf),
					confidence = fragment.toPublicConfidence(),
					wallTimeContinuity = ActivityHistoryWallTimeContinuity.valueOf(
						requireNotNull(fragment.wallTimeContinuity),
					),
					durationNanos = duration,
				)
				else -> error("Unknown Activity fragment kind")
			}
		}

	private fun ActivityCapturedFragmentEntity.toPublicConfidence(): ActivityHistoryConfidence = when (
		confidenceKind
	) {
		ActivityCapturedFragmentEntity.CONFIDENCE_TRANSITION -> ActivityHistoryConfidence.TransitionSignal
		ActivityCapturedFragmentEntity.CONFIDENCE_SAMPLED -> ActivityHistoryConfidence.Sampled(
			requireNotNull(confidenceMinimumPercent), requireNotNull(confidenceMaximumPercent),
			requireNotNull(confidenceObservationCount),
		)
		else -> error("Unknown Activity confidence kind")
	}

	private fun minimumPossibleWallTime(fragment: ActivityCapturedFragmentEntity): Long {
		val start = Math.subtractExact(requireNotNull(fragment.startWallTimeMs),
			requireNotNull(fragment.startWallTimeUncertaintyMs))
		val end = Math.subtractExact(requireNotNull(fragment.endWallTimeMs),
			requireNotNull(fragment.endWallTimeUncertaintyMs))
		return minOf(start, end)
	}

	private fun List<ActivityCapturedWindowRevisionEntity>.sumExact(
		selector: (ActivityCapturedWindowRevisionEntity) -> Long,
	): Long = fold(0L) { sum, revision -> Math.addExact(sum, selector(revision)) }

	private fun entry(
		logicalId: String,
		segments: List<SessionSegment>,
		zones: Set<String>,
		state: ActivityHistoryProductState,
		coverage: ActivityHistoryCoverage,
		activeTime: ActivityActiveTime?,
		fragments: List<ActivityHistoryFragment>,
		causes: Set<ActivityHistoryCause>,
	): ActivityHistoryEntry {
		val start = segments.minOf(SessionSegment::startTimeMs).coerceAtLeast(0L)
		val end = segments.maxOf(SessionSegment::endTimeMs).coerceAtLeast(start)
		return ActivityHistoryEntry(ActivityHistoryEntryKey("activity-logical:$logicalId"), EpochMs(start),
			EpochMs(end), zones, state, coverage, activeTime, fragments, causes)
	}

	private fun failed(logicalId: String, segments: List<SessionSegment>, cause: ActivityHistoryCause) =
		entry(logicalId, segments, emptySet(), ActivityHistoryProductState.FAILED,
			ActivityHistoryCoverage.NONE, null, emptyList(), setOf(cause))

	private fun unavailable(
		logicalId: String,
		segments: List<SessionSegment>,
		zones: Set<String>,
		cause: ActivityHistoryCause,
	) = entry(logicalId, segments, zones, ActivityHistoryProductState.UNAVAILABLE,
		ActivityHistoryCoverage.NONE, null, emptyList(), setOf(cause))

	private fun materializing(logicalId: String, segments: List<SessionSegment>, zones: Set<String>) =
		entry(logicalId, segments, zones, ActivityHistoryProductState.MATERIALIZING,
			ActivityHistoryCoverage.NONE, null, emptyList(),
			setOf(ActivityHistoryCause.SESSION_ACTIVE, ActivityHistoryCause.MATERIALIZATION_BEHIND))

	private fun legacy(segment: SessionSegment): ActivityHistoryEntry =
		entry("legacy:${segment.id}", listOf(segment), emptySet(), ActivityHistoryProductState.UNAVAILABLE,
			ActivityHistoryCoverage.NONE, null, emptyList(), setOf(ActivityHistoryCause.LEGACY_UNVERIFIABLE))

	private fun deletionDigest(logicalId: String, runId: String): String =
		SourceDeletionFenceEntity.logicalServiceRunIdentity(
			ACTIVITY_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, logicalId, runId,
		)

	private fun isActivityLane(lane: SourceProductProjectionLaneEntity): Boolean =
		lane.sourceKind == ACTIVITY_SOURCE &&
			lane.projectionId == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_ID &&
			lane.projectionVersion == SourceDestinationOwnerEntity.ACTIVITY_FACT_PROJECTION_VERSION &&
			lane.bindingGeneration == SourceDestinationOwnerEntity.ACTIVITY_FACT_BINDING_GENERATION &&
			lane.productStage in setOf(SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL)

	private fun isValidHistoricalLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.captureModeMask <= 0L || lane.activatedRolloutRevision <= 0L ||
			lane.activationOrdinal <= 0L || lane.installedAtMs < 0L ||
			lane.updatedAtMs < lane.installedAtMs
		) return false
		val minimumCursor = lane.activationOrdinal - 1L
		val cutoff = lane.captureAdmissionCutoffOrdinal
		if (lane.contiguousAdmissionOrdinal < minimumCursor ||
			(cutoff != null && (cutoff < minimumCursor || lane.contiguousAdmissionOrdinal > cutoff))
		) return false
		if ((lane.terminalDisposition == null) != (lane.terminalAtMs == null)) return false
		return when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE ->
				lane.retentionRequired && lane.terminalDisposition == null && cutoff == null
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> {
				if (lane.retentionRequired) return false
				if (lane.terminalDisposition == null) return cutoff == null
				val terminalAt = lane.terminalAtMs ?: return false
				lane.terminalDisposition ==
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
					cutoff != null && lane.contiguousAdmissionOrdinal == cutoff &&
					terminalAt >= lane.installedAtMs && lane.updatedAtMs >= terminalAt
			}
			else -> false
		}
	}

	private fun isValidZone(value: String): Boolean = try {
		ZoneId.of(value)
		true
	} catch (_: DateTimeException) {
		false
	}

	private val TERMINAL_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
	private val PROVIDER_COVERAGES = setOf("CALLBACKS_ENTERED_BEFORE_BARRIER",
		"PROVIDER_COMPLETENESS_UNOBSERVABLE")
	private val STOP_STATUSES = setOf("COMPLETE", "TIMED_OUT", "PERMISSION_LOST", "PROVIDER_FAILED",
		"PROCESS_RESTARTED")
	private val PROVIDER_UNAVAILABLE_STATUSES = setOf("PERMISSION_LOST", "PROVIDER_FAILED", "TIMED_OUT")
	private val SYNTHETIC_ACTIVITY_INSTANCES = setOf(
		"not-owned-activity", "unresolved-activity", "activity-unregistered",
	)
	private const val COMPLETE_STOP = "COMPLETE"
	private const val UNOBSERVABLE_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"
	private const val MANUAL_SESSION_CAPTURE_MASK = 1L
	private const val AUTOMATIC_SESSION_CAPTURE_MASK = 2L
}

private data class ActivityCaptureTimeline(
	val fragments: List<ActivityHistoryFragment>,
	val externalGapDurationNanos: Long,
)
