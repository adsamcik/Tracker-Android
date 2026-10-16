package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.StepsCountDomainOwnerLookupKey
import com.adsamcik.tracker.shared.base.database.dao.StepsFactCandidateIdentity
import com.adsamcik.tracker.shared.base.database.dao.loadSelectedStepsFactCandidateStatesByRun
import com.adsamcik.tracker.shared.base.database.dao.StepsFactCandidateState
import com.adsamcik.tracker.shared.base.database.dao.hasValidStepsFactCandidateState
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsRecordingLifetime
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainOwnerRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepsCountDomainReceiptIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsWalCarrierAuthenticator
import com.adsamcik.tracker.shared.base.database.data.StepsSessionCompletenessIntegrity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import javax.inject.Inject

/**
 * Selects one session segment's Steps history from its immutable service-run writer provenance.
 *
 * This is intentionally Steps-specific. The permanent destination owner is write authority for
 * the present; it must never reinterpret an older segment after cutover or rollback. Acquisition
 * completeness and materializer progress also remain independent axes so a number cannot silently
 * turn missing, baseline-only, partial, or still-processing evidence into a verified zero.
 */
@Suppress("LargeClass", "TooManyFunctions")
internal class StepsSegmentHistorySelector @Inject constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
) {
	/** Production lookup keeps segment membership and all dependent reads in one Room snapshot. */
	internal suspend fun selectBySegmentId(segmentId: Long): StepsSegmentHistoryResult? =
		selectEvidenceBySegmentIds(listOf(segmentId))[segmentId]?.steps

	/** Batch production lookup used by recent/list composition without one query cascade per row. */
	internal suspend fun selectEvidenceBySegmentIds(
		segmentIds: List<Long>,
	): Map<Long, HistoricalSegmentEvidence> = database.withTransaction {
		val distinctIds = segmentIds.distinct()
		if (distinctIds.isEmpty()) {
			return@withTransaction emptyMap()
		}
		require(distinctIds.size <= HISTORY_SEGMENT_BATCH_CAP) {
			"At most $HISTORY_SEGMENT_BATCH_CAP distinct history rows may be selected per batch"
		}
		val segments = database.trackingHistoryReadDao().segments(distinctIds)
		selectManyInTransaction(segments).associateBy { it.segment.id }
	}

	/** Test seam for exercising historical selection without persisting a presentation segment. */
	internal suspend fun select(segment: SessionSegment): StepsSegmentHistoryResult =
		database.withTransaction { selectManyInTransaction(listOf(segment)).single().steps }

	/** Test seam that also exposes the exact revisioned capture authority. */
	internal suspend fun selectEvidence(segment: SessionSegment): HistoricalSegmentEvidence =
		database.withTransaction { selectManyInTransaction(listOf(segment)).single() }

	/** Caller must already hold the Room transaction that defines the logical-entry snapshot. */
	internal suspend fun selectManyInTransaction(
		segments: List<SessionSegment>,
	): List<HistoricalSegmentEvidence> {
		if (segments.isEmpty()) {
			return emptyList()
		}
		val snapshot = loadStepsHistoryBatchSnapshot(database, segments)
		return selectManyWithSnapshot(segments, snapshot)
	}


	/** Each original count window is checked independently; complete total readers remain unchanged. */
	internal suspend fun selectMeasuredFactsInTransaction(
		segmentIds: List<Long>, identities: List<StepsFactCandidateIdentity>,
	): Map<StepsFactCandidateIdentity, StepsMeasuredFactRead> {
		require(segmentIds.isBoundedUniqueMeasuredStepsSelection())
		require(identities.isBoundedUniqueMeasuredStepsSelection())
		if (segmentIds.isEmpty() || identities.isEmpty()) {
			return emptyMap()
		}
		val segments = database.trackingHistoryReadDao().segments(segmentIds)
		if (segments.isEmpty()) {
			return emptyMap()
		}
		val snapshot = loadStepsHistoryBatchSnapshot(database, segments, selectedFactStatesByRun = emptyMap())
		val states = database.trackingHistoryReadDao().loadSelectedStepsFactCandidateStatesByRun(
			snapshot.serviceRuns.values.toList(), identities)
		// Healthy pages share one bounded carrier read. A damaged batch keeps the existing
		// per-fact reads so unrelated measurements remain independently usable.
		val batchCarrier = loadStepsCarrierAuthority(database, states.mapValues { it.value.values.toList() })
			.takeIf { it.state == StepsCarrierAuthorityState.READY }
		val failures = loadMeasuredTerminalFailures(states)
		val results = linkedMapOf<StepsFactCandidateIdentity, StepsMeasuredFactRead>()
		for ((runId, selected) in states) {
			val segment = segments.singleOrNull { it.serviceRunId == runId } ?: continue
			for ((identity, state) in selected) {
				val evidence = snapshot.selectMeasuredFact(segment, runId, identity, state, batchCarrier, failures)
				val prior = results.put(identity, StepsMeasuredFactRead(segment, state.scopeCarrier, evidence.steps))
				if (prior != null) {
					results[identity] = StepsMeasuredFactRead(segment, null,
					failed(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED))
				}
			}
		}
		return results
	}

	/** Qualify one original fact without making a damaged neighbour hide the rest of the page. */
	private suspend fun StepsHistoryBatchSnapshot.selectMeasuredFact(
		segment: SessionSegment,
		runId: String,
		identity: StepsFactCandidateIdentity,
		state: StepsFactCandidateState,
		batchCarrier: StepsCarrierAuthorityLoad?,
		failures: Map<Triple<String, Int, Long>, SourceProjectionFailureEntity>,
	): HistoricalSegmentEvidence {
		val byRun = mapOf(runId to listOf(state))
		val carrier = batchCarrier ?: loadStepsCarrierAuthority(database, byRun)
		val originalOrdinal = state.scopeCarrier?.sourceAdmissionOrdinal
		val terminalFailures = originalOrdinal?.let { ordinal ->
			failures[Triple(identity.writerProjectionId, identity.writerProjectionVersion.toInt(), ordinal)]
		}?.let(::listOf).orEmpty()
		val leaf = copy(factStatesByRun = byRun, walByEventId = carrier.walByEventId,
			countDomainOwners = carrier.countDomainOwners, carrierAuthorityState = carrier.state,
			terminalFailures = terminalFailures)
		return selectWithSnapshot(segment, leaf, measuredOnly = true)
	}

	private suspend fun loadMeasuredTerminalFailures(
		states: Map<String, Map<StepsFactCandidateIdentity, StepsFactCandidateState>>,
	): Map<Triple<String, Int, Long>, SourceProjectionFailureEntity> {
		val failureKeys = states.values.flatMap { selected ->
			selected.mapNotNull { (identity, state) ->
				state.scopeCarrier?.sourceAdmissionOrdinal?.let { ordinal ->
					Triple(identity.writerProjectionId, identity.writerProjectionVersion.toInt(), ordinal)
				}
			}
		}.distinct()
		return failureKeys.groupBy { it.first to it.second }.flatMap { (writer, keys) ->
			database.sourceProjectionStateDao().terminalFailuresAt(writer.first, writer.second, keys.map { it.third })
		}.associateBy { Triple(it.projectionId, it.projectionVersion, it.admissionOrdinal) }
	}

	/** Pure reuse seam for readers that already assembled one bounded Room snapshot. */
	internal fun selectManyWithSnapshot(
		segments: List<SessionSegment>,
		snapshot: StepsHistoryBatchSnapshot,
		measuredOnly: Boolean = false,
	): List<HistoricalSegmentEvidence> =
		segments.map { segment -> selectWithSnapshot(segment, snapshot, measuredOnly) }

	// This fail-closed tree is pure over one fixed-count batch snapshot.
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun selectWithSnapshot(
		segment: SessionSegment,
		snapshot: StepsHistoryBatchSnapshot,
		measuredOnly: Boolean = false,
	): HistoricalSegmentEvidence {
		val dependencyOverflow = snapshot.dependencyOverflow
		if (
			dependencyOverflow != null &&
			segment.serviceRunId?.let(dependencyOverflow.affectedServiceRunIds::contains) == true
		) {
			return when (dependencyOverflow.dependency) {
				StepsHistoryBatchDependency.TERMINAL_PROJECTION_FAILURES -> unavailableEvidence(
					segment,
					HistoricalCaptureFailure.BATCH_DEPENDENCY_OVERFLOW,
					StepsHistoryReason.BATCH_DEPENDENCY_OVERFLOW,
				)
			}
		}
		val logicalTrackingId = segment.logicalTrackingId
		val serviceRunId = segment.serviceRunId
		if (logicalTrackingId == null && serviceRunId == null) {
			return HistoricalSegmentEvidence(
				segment = segment,
				captureAuthority = HistoricalCaptureAuthority.Unverifiable(
					HistoricalCaptureFailure.LEGACY_UNATTRIBUTED,
				),
				steps = unattributedLegacy(segment.steps),
			)
		}
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
			return unavailableEvidence(
				segment,
				HistoricalCaptureFailure.SEGMENT_MEMBERSHIP_INCOMPLETE,
				StepsHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE,
			)
		}

		val serviceRun = snapshot.serviceRuns[serviceRunId]
		if (serviceRun == null) {
			return unavailableEvidence(
				segment,
				HistoricalCaptureFailure.SERVICE_RUN_MISSING,
				StepsHistoryReason.SERVICE_RUN_MISSING,
			)
		}
		if (serviceRun.logicalTrackingId != logicalTrackingId) {
			return unavailableEvidence(
				segment,
				HistoricalCaptureFailure.SERVICE_RUN_MEMBERSHIP_MISMATCH,
				StepsHistoryReason.SERVICE_RUN_MEMBERSHIP_MISMATCH,
			)
		}
		if (
			serviceRun.presentationAcknowledgement ==
			SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
		) {
			return unavailableEvidence(
				segment,
				HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
				StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
			)
		}
		if (serviceRun.sessionSegmentId != segment.id) {
			return unavailableEvidence(
				segment,
				HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
				StepsHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
			)
		}

		val manifests = snapshot.manifestsByRun[serviceRunId].orEmpty()
		if (manifests.isEmpty()) {
			return unavailableEvidence(
				segment,
				HistoricalCaptureFailure.MANIFEST_MISSING,
				StepsHistoryReason.MANIFEST_MISSING,
			)
		}
		val sourcesByRevision = linkedMapOf<Long, List<SessionManifestSourceEntity>>()
		for (manifest in manifests) {
			if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId) {
				return unavailableEvidence(
					segment,
					HistoricalCaptureFailure.MANIFEST_MEMBERSHIP_MISMATCH,
					StepsHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH,
				)
			}
			val sources = snapshot.sourcesByManifest[
				ManifestKey(logicalTrackingId, manifest.manifestRevision)
			].orEmpty()
			if (!SessionManifestIntegrity.verify(manifest, sources)) {
				return unavailableEvidence(
					segment,
					HistoricalCaptureFailure.MANIFEST_INTEGRITY_FAILED,
					StepsHistoryReason.MANIFEST_INTEGRITY_FAILED,
				)
			}
			sourcesByRevision[manifest.manifestRevision] = sources
		}
		if (!SessionManifestIntegrity.hasValidRetainedServiceRunTimeline(serviceRun, manifests)) {
			return unavailableEvidence(
				segment,
				HistoricalCaptureFailure.MANIFEST_INTEGRITY_FAILED,
				StepsHistoryReason.MANIFEST_INTEGRITY_FAILED,
			)
		}
		val captureAuthority = historicalCaptureAuthority(manifests, sourcesByRevision)
		val scopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		if (scopeDigest in snapshot.deletionFenceDigests) {
			return HistoricalSegmentEvidence(segment, captureAuthority, deleted())
		}
		val retentionTruncationDigest = StepFactRevisionIntegrity.retentionTruncationIdentity(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		val retentionTruncationFence =
			snapshot.retentionTruncationFencesByDigest[retentionTruncationDigest]
		if (retentionTruncationFence != null) {
			val evidenceState = snapshot.evidenceState
				?: return HistoricalSegmentEvidence(
					segment,
					captureAuthority,
					failed(StepsHistoryReason.SOURCE_EVIDENCE_STATE_MISSING),
				)
			if (!StepFactRevisionIntegrity.isRetentionTruncationFence(
					fence = retentionTruncationFence,
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
					collectedDataEpoch = evidenceState.collectedDataEpoch,
				)
			) {
				return HistoricalSegmentEvidence(
					segment,
					captureAuthority,
					failed(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED),
				)
			}
			if (!measuredOnly) {
				return HistoricalSegmentEvidence(
				segment,
				captureAuthority,
				unavailable(StepsHistoryReason.RETENTION_TRUNCATED_RUN),
			)
			}
		}

		val stepsBindings = sourcesByRevision.mapValues { (_, sources) ->
			sources.singleOrNull { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}
		}.filterValues { it != null }.mapValues { (_, source) -> requireNotNull(source) }
		if (stepsBindings.isEmpty()) {
			if (snapshot.factStatesByRun[serviceRunId].orEmpty().isNotEmpty()) {
				return HistoricalSegmentEvidence(
					segment,
					captureAuthority,
					failed(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED),
				)
			}
			val policies = manifests.map { manifest ->
				snapshot.stepPolicies[manifest.sourcePolicyRevision]
			}
			val disabledForWholeRun = policies.all { policy ->
				policy != null && !policy.enabled
			}
			val steps = if (disabledForWholeRun) {
				StepsSegmentHistoryResult(
					count = null,
					availability = StepsHistoryAvailability.DISABLED,
					evidence = StepsHistoryEvidence.NO_OBSERVATION,
					materialization = StepsHistoryMaterialization.NOT_APPLICABLE,
					coverage = StepsHistoryCoverage.NONE,
					reasons = setOf(StepsHistoryReason.SOURCE_NOT_CAPTURED),
				)
			} else {
				unavailable(StepsHistoryReason.SOURCE_NOT_CAPTURED)
			}
			return HistoricalSegmentEvidence(segment, captureAuthority, steps)
		}
		val manifestsByRevision = manifests.associateBy(SessionManifestVersionEntity::manifestRevision)
		if (stepsBindings.any { (revision, binding) ->
				val policyRevision = manifestsByRevision[revision]?.sourcePolicyRevision
				val policy = policyRevision?.let(snapshot.stepPolicies::get)
				!StepFactRevisionIntegrity.hasValidStepsCaptureAuthority(
					policy = policy,
					consent = snapshot.stepCaptureConsents[binding.consentEpoch],
					manifestPolicyRevision = policyRevision ?: Long.MIN_VALUE,
					binding = binding,
				)
			}
		) {
			return HistoricalSegmentEvidence(
				segment,
				captureAuthority,
				failed(StepsHistoryReason.SOURCE_POLICY_ATTRIBUTION_INVALID),
			)
		}

		val writers = stepsBindings.values.map(::writerBinding).distinct()
		if (writers.size != 1) {
			return HistoricalSegmentEvidence(
				segment,
				captureAuthority,
				failed(StepsHistoryReason.MIXED_WRITER_WITHIN_SERVICE_RUN),
			)
		}
		val writer = writers.single()
		val captureCoveredWholeRun = !measuredOnly && stepsBindings.size == manifests.size
		val orderedManifests = manifests.sortedBy(SessionManifestVersionEntity::manifestRevision)
		val steps = when (writer.owner) {
			SourceDestinationOwnerEntity.OWNER_LEGACY_STEP_INTERVAL -> legacy(
				steps = segment.steps,
				captureCoveredWholeRun = captureCoveredWholeRun,
			)
			SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS -> candidate(
				segment = segment,
				serviceRun = serviceRun,
				writer = writer,
				manifestsByRevision = manifestsByRevision,
				orderedManifests = orderedManifests,
				stepsBindings = stepsBindings,
				captureCoveredWholeRun = captureCoveredWholeRun,
				snapshot = snapshot,
				measuredOnly = measuredOnly,
			)
			else -> unavailable(StepsHistoryReason.UNKNOWN_WRITER)
		}
		return HistoricalSegmentEvidence(segment, captureAuthority, steps)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun candidate(
		segment: SessionSegment,
		serviceRun: SourceServiceRunEntity,
		writer: HistoricalStepsWriterBinding,
		manifestsByRevision: Map<Long, SessionManifestVersionEntity>,
		orderedManifests: List<SessionManifestVersionEntity>,
		stepsBindings: Map<Long, SessionManifestSourceEntity>,
		captureCoveredWholeRun: Boolean,
		snapshot: StepsHistoryBatchSnapshot,
		measuredOnly: Boolean = false,
	): StepsSegmentHistoryResult {
		val logicalTrackingId = serviceRun.logicalTrackingId
		val serviceRunId = serviceRun.serviceRunId
		val recordingIntervals = StepsRecordingLifetime.validatedIntervals(orderedManifests, stepsBindings)
			?: return failed(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED)
		val serviceRunCompleted = serviceRun.completedAtMs != null &&
			serviceRun.state in TERMINAL_SERVICE_RUN_STATES
		val projectionId = writer.projectionId
		val projectionVersion = writer.projectionVersion
		val bindingGeneration = writer.bindingGeneration
		if (projectionId == null || projectionVersion == null || bindingGeneration == null) {
			return unavailable(StepsHistoryReason.CANDIDATE_PROVENANCE_INCOMPLETE)
		}
		val lane = snapshot.productLanes[HistoricalLaneKey(
			bindingGeneration = bindingGeneration,
			projectionId = projectionId,
			projectionVersion = projectionVersion,
		)] ?: return failed(StepsHistoryReason.PRODUCT_LANE_MISSING)
		val requiredModeMask = manifestsByRevision.values.map(SessionManifestVersionEntity::sessionMode)
			.distinct()
			.singleOrNull()
			?.let(::sessionCaptureModeMask)
		if (requiredModeMask == null || !laneExecutionAuthority.owns(lane) ||
			!isValidHistoricalLane(lane) || lane.captureModeMask and requiredModeMask == 0L
		) {
			return failed(StepsHistoryReason.PRODUCT_LANE_INVALID)
		}

		val evidenceState = snapshot.evidenceState
			?: return failed(StepsHistoryReason.SOURCE_EVIDENCE_STATE_MISSING)
		if (evidenceState.retainedFromMs?.let { segment.endTimeMs < it } == true) {
			return unavailable(StepsHistoryReason.OUTSIDE_RETAINED_FLOOR)
		}
		val retentionCrossesSegment = evidenceState.retainedFromMs?.let { retainedFromMs ->
			segment.startTimeMs < retainedFromMs && segment.endTimeMs >= retainedFromMs
		} == true
		val completeness = snapshot.completenessByRun[serviceRunId].orEmpty()
			.filter { it.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS }
		if (!hasValidStepsCompleteness(completeness, logicalTrackingId, serviceRunId)) {
			return failed(StepsHistoryReason.COMPLETENESS_INVALID)
		}
		val targetOrdinal = completeness.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull()
		// The batch is provisionally anchored by either retained membership value. Validate each
		// complete carrier/state pair before trusting checksum-covered attribution for filtering.
		val rawFactStates = snapshot.factStatesByRun[serviceRunId].orEmpty()
		if (rawFactStates.isNotEmpty() &&
			snapshot.carrierAuthorityState == StepsCarrierAuthorityState.UNAVAILABLE
		) {
			return unavailable(StepsHistoryReason.CANDIDATE_PROVENANCE_INCOMPLETE)
		}
		if (rawFactStates.isNotEmpty() &&
			snapshot.carrierAuthorityState == StepsCarrierAuthorityState.UNVERIFIABLE
		) {
			return failed(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED)
		}
		if (rawFactStates.any { scoped -> !hasValidStepsFactCandidateState(scoped) } ||
			rawFactStates.any { scoped ->
				scoped.state?.operation == StepFactRevisionEntity.OPERATION_RETRACT
			}
		) {
			return failed(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED)
		}
		if (
			(rawFactStates.isNotEmpty() && targetOrdinal == null) ||
			(targetOrdinal != null && rawFactStates.any { scoped ->
				val admissionOrdinal = requireNotNull(scoped.scopeCarrier).sourceAdmissionOrdinal
				admissionOrdinal == null ||
					admissionOrdinal < lane.activationOrdinal || admissionOrdinal > targetOrdinal
			})
		) {
			return failed(StepsHistoryReason.COMPLETENESS_INVALID)
		}
		if (rawFactStates.any { scoped ->
			val scope = requireNotNull(scoped.scopeCarrier)
			val manifestRevision = scope.manifestRevision
			val manifest = manifestRevision?.let(manifestsByRevision::get)
			val binding = manifestRevision?.let(stepsBindings::get)
			val startElapsed = scope.intervalStartElapsedRealtimeNanos
			val endElapsed = scope.intervalEndElapsedRealtimeNanos
			val recordingInterval = manifestRevision?.let(recordingIntervals::get)
			scope.logicalTrackingId != logicalTrackingId ||
				scope.serviceRunId != serviceRunId ||
				manifest == null || binding == null ||
				scope.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
				scope.sourcePolicyRevision != manifest.sourcePolicyRevision ||
				scope.captureConsentEpoch != binding.consentEpoch ||
				scope.writerBindingGeneration != bindingGeneration ||
				scope.writerProjectionId != projectionId ||
				scope.writerProjectionVersion != projectionVersion ||
				!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(scope) ||
				scope.clockDomainId != manifest.effectiveBootId ||
				scope.bootClockDomainId != manifest.effectiveBootId ||
				startElapsed == null || endElapsed == null ||
				startElapsed < serviceRun.startedElapsedNanos ||
				recordingInterval == null || startElapsed !in recordingInterval ||
				endElapsed !in recordingInterval ||
				lane.activatedRolloutRevision > manifest.rolloutRevision ||
				!snapshot.authenticatesExactCarrier(scope)
		}) {
			return failed(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED)
		}
		val canonicalRunFacts = rawFactStates.map { scoped -> requireNotNull(scoped.scopeCarrier) }
		if (!measuredOnly && !StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
				canonicalRunFacts,
				logicalTrackingId,
				serviceRunId,
			)
		) {
			return failed(StepsHistoryReason.STEP_FACT_INTEGRITY_FAILED)
		}
		val factStates = rawFactStates
		val facts = factStates.filter {
			it.state?.operation == StepFactRevisionEntity.OPERATION_UPSERT
		}.mapNotNull(StepsFactCandidateState::scopeCarrier).filter { fact ->
			evidenceState.retainedFromMs?.let { retainedFromMs ->
				requireNotNull(fact.intervalEndTimeMs) >= retainedFromMs
			} != false
		}
		if (factStates.any {
			!evidenceState.acceptsRetainedEpoch(requireNotNull(it.state).collectedDataEpoch)
		}) {
			return failed(StepsHistoryReason.STALE_COLLECTED_DATA_EPOCH)
		}

		val reasons = linkedSetOf<StepsHistoryReason>()
		if (!captureCoveredWholeRun) {
			reasons += StepsHistoryReason.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN
		}
		if (!serviceRunCompleted) {
			reasons += StepsHistoryReason.SERVICE_RUN_ACTIVE
		}
		if (retentionCrossesSegment) {
			reasons += StepsHistoryReason.RETENTION_CROSSES_SEGMENT
		}
		if (completeness.isEmpty()) {
			reasons += StepsHistoryReason.COMPLETENESS_MISSING
		}
		if (completeness.any { !it.appDrainComplete }) {
			reasons += StepsHistoryReason.APP_DRAIN_INCOMPLETE
		}
		if (completeness.any { it.stopStatus != COMPLETE_STOP_STATUS }) {
			reasons += StepsHistoryReason.STOP_INCOMPLETE
		}
		if (completeness.any {
				(it.unresolvedSequenceStart == null) != (it.unresolvedSequenceEnd == null) ||
					it.unresolvedSequenceStart != null
			}
		) {
			reasons += StepsHistoryReason.UNRESOLVED_PROVIDER_SEQUENCE
		}
		if (completeness.any { it.providerCoverage != COMPLETE_PROVIDER_COVERAGE }) {
			reasons += StepsHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE
		}
		if (facts.any { it.coverageKind == StepFactRevisionEntity.COVERAGE_RESET_GAP }) {
			reasons += StepsHistoryReason.RESET_GAP
		}
		if (facts.any { it.coverageKind == StepFactRevisionEntity.COVERAGE_PARTIAL }) {
			reasons += StepsHistoryReason.PARTIAL_FACT
		}
		if (targetOrdinal != null && factStates.isEmpty() && !retentionCrossesSegment) {
			reasons += StepsHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN
		}
		val laneCutoffOrdinal = lane.captureAdmissionCutoffOrdinal
		val productTargetOrdinal = if (measuredOnly) {
			facts.mapNotNull(StepFactRevisionEntity::sourceAdmissionOrdinal)
			.maxOrNull()
		} else {
			targetOrdinal
		}

		val materialization = when {
			productTargetOrdinal != null && productTargetOrdinal < lane.activationOrdinal -> {
				reasons += StepsHistoryReason.TARGET_BEFORE_LANE_ACTIVATION
				StepsHistoryMaterialization.FAILED
			}
			productTargetOrdinal != null && snapshot.terminalFailures.any { failure ->
				failure.projectionId == projectionId &&
					failure.projectionVersion == projectionVersion &&
					failure.admissionOrdinal > lane.activationOrdinal - 1L &&
					failure.admissionOrdinal <= productTargetOrdinal
			} -> {
				reasons += StepsHistoryReason.TERMINAL_PROJECTION_FAILURE
				StepsHistoryMaterialization.FAILED
			}
			productTargetOrdinal != null && laneCutoffOrdinal != null &&
				productTargetOrdinal > laneCutoffOrdinal -> {
				reasons += StepsHistoryReason.PRODUCT_LANE_CUTOFF_BEFORE_TARGET
				StepsHistoryMaterialization.FAILED
			}
			productTargetOrdinal != null &&
				lane.status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
				lane.contiguousAdmissionOrdinal < productTargetOrdinal -> {
				reasons += StepsHistoryReason.PRODUCT_LANE_RETIRED_BEFORE_TARGET
				StepsHistoryMaterialization.FAILED
			}
			productTargetOrdinal != null && lane.contiguousAdmissionOrdinal < productTargetOrdinal -> {
				reasons += StepsHistoryReason.PRODUCT_LANE_BEHIND
				StepsHistoryMaterialization.MATERIALIZING
			}
			!serviceRunCompleted -> StepsHistoryMaterialization.MATERIALIZING
			completeness.isEmpty() -> StepsHistoryMaterialization.FAILED
			StepsHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN in reasons ->
				StepsHistoryMaterialization.FAILED
			else -> StepsHistoryMaterialization.READY
		}

		val coveredFacts = facts.filter {
			it.coverageKind == StepFactRevisionEntity.COVERAGE_COVERED
		}
		val count = try {
			coveredFacts.fold(0L) { total, fact ->
				Math.addExact(total, requireNotNull(fact.effectiveStepCount))
			}.takeIf { coveredFacts.isNotEmpty() }
		} catch (_: ArithmeticException) {
			return failed(StepsHistoryReason.COUNT_OVERFLOW)
		}
		val evidence = when {
			count != null && count > 0L -> StepsHistoryEvidence.RECORDED
			count != null -> StepsHistoryEvidence.COVERED_ZERO
			facts.any { it.coverageKind == StepFactRevisionEntity.COVERAGE_BASELINE } ->
				StepsHistoryEvidence.BASELINE
			else -> StepsHistoryEvidence.NO_OBSERVATION
		}
		val acquisitionComplete = completeness.isNotEmpty() && completeness.all {
			it.appDrainComplete && it.stopStatus == COMPLETE_STOP_STATUS &&
				it.unresolvedSequenceStart == null && it.unresolvedSequenceEnd == null &&
				it.providerCoverage == COMPLETE_PROVIDER_COVERAGE
		}
		val coverage = when {
			coveredFacts.isEmpty() -> StepsHistoryCoverage.NONE
			materialization == StepsHistoryMaterialization.READY &&
				acquisitionComplete && captureCoveredWholeRun &&
				StepsHistoryReason.RESET_GAP !in reasons &&
				StepsHistoryReason.PARTIAL_FACT !in reasons &&
				StepsHistoryReason.RETENTION_CROSSES_SEGMENT !in reasons &&
				StepsHistoryReason.SERVICE_RUN_ACTIVE !in reasons ->
				StepsHistoryCoverage.COMPLETE
			else -> StepsHistoryCoverage.PARTIAL
		}
		return StepsSegmentHistoryResult(
			count = count,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = evidence,
			materialization = materialization,
			coverage = coverage,
			reasons = reasons,
		)
	}

	@Suppress("ComplexCondition")
	private fun hasValidStepsCompleteness(
		rows: List<SourceSessionCompletenessEntity>,
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean = StepsSessionCompletenessIntegrity.hasValidTimeline(
		rows,
		logicalTrackingId,
		serviceRunId,
	)

	private fun unavailableEvidence(
		segment: SessionSegment,
		captureFailure: HistoricalCaptureFailure,
		stepsReason: StepsHistoryReason,
	) = HistoricalSegmentEvidence(
		segment = segment,
		captureAuthority = HistoricalCaptureAuthority.Unverifiable(captureFailure),
		steps = unavailable(stepsReason),
	)

	private fun writerBinding(source: SessionManifestSourceEntity) = HistoricalStepsWriterBinding(
		owner = requireNotNull(source.writerOwner),
		ownerGeneration = requireNotNull(source.writerOwnerGeneration),
		projectionId = source.writerProjectionId,
		projectionVersion = source.writerProjectionVersion,
		bindingGeneration = source.writerBindingGeneration,
	)

	private fun StepsHistoryBatchSnapshot.authenticatesExactCarrier(
		fact: StepFactRevisionEntity,
	): Boolean {
		val eventId = fact.sourceEventId ?: return false
		val wal = walByEventId[eventId] ?: return false
		val walKey = wal.stepsWalOwnerKey()
		val factKey = fact.stepsFactOwnerKey()
		return StepsWalCarrierAuthenticator.authenticates(
			fact,
			wal,
			countDomainOwners[walKey],
			countDomainOwners[factKey],
		)
	}

	private fun com.adsamcik.tracker.shared.base.database.data.SourceEventWalEntity
		.stepsWalOwnerKey() = StepsCountDomainOwnerLookupKey(
			ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_WAL,
			ownerIdentity = StepsCountDomainReceiptIntegrity.sessionWalOwnerIdentity(
				admissionOrdinal,
				eventId,
			),
			ownerRevision = 1L,
		)

	private fun StepFactRevisionEntity.stepsFactOwnerKey() = StepsCountDomainOwnerLookupKey(
		ownerKind = StepsCountDomainOwnerRevisionEntity.OWNER_SESSION_FACT,
		ownerIdentity = StepsCountDomainReceiptIntegrity.sessionFactOwnerIdentity(
			writerProjectionId,
			writerProjectionVersion,
			logicalFactId,
		),
		ownerRevision = semanticRevision,
	)

	private fun sessionCaptureModeMask(sessionMode: String): Long? = when (sessionMode) {
		"MANUAL" -> MANUAL_SESSION_CAPTURE_MASK
		"AUTOMATIC" -> AUTOMATIC_SESSION_CAPTURE_MASK
		else -> null
	}

	private fun isValidHistoricalLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.captureModeMask <= 0L ||
			lane.productStage !in setOf(
				SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
				SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
			) ||
			lane.activatedRolloutRevision <= 0L || lane.activationOrdinal <= 0L ||
			lane.installedAtMs < 0L || lane.updatedAtMs < lane.installedAtMs
		) {
			return false
		}

		val minimumCursor = lane.activationOrdinal - 1L
		val cutoff = lane.captureAdmissionCutoffOrdinal
		if (lane.contiguousAdmissionOrdinal < minimumCursor ||
			(cutoff != null && (cutoff < minimumCursor || lane.contiguousAdmissionOrdinal > cutoff))
		) {
			return false
		}
		val terminalDisposition = lane.terminalDisposition
		val terminalAtMs = lane.terminalAtMs
		if ((terminalDisposition == null) != (terminalAtMs == null)) {
			return false
		}

		return when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE ->
				lane.retentionRequired && terminalDisposition == null
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> {
				if (lane.retentionRequired) {
					return false
				}
				if (terminalDisposition == null) {
					return cutoff == null
				}
				val terminalAt = terminalAtMs ?: return false
				terminalDisposition ==
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
					cutoff != null && lane.contiguousAdmissionOrdinal == cutoff &&
					terminalAt >= lane.installedAtMs && lane.updatedAtMs >= terminalAt
			}
			else -> false
		}
	}

	private fun legacy(
		steps: Int?,
		captureCoveredWholeRun: Boolean,
	): StepsSegmentHistoryResult {
		val positive = steps?.takeIf { it > 0 }?.toLong()
		val reasons = linkedSetOf(StepsHistoryReason.LEGACY_REPLAY_UNVERIFIED)
		if (positive == null) {
			reasons += StepsHistoryReason.LEGACY_ZERO_UNVERIFIED
		}
		if (!captureCoveredWholeRun) {
			reasons += StepsHistoryReason.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN
		}
		return StepsSegmentHistoryResult(
			count = positive,
			availability = StepsHistoryAvailability.AVAILABLE,
			evidence = if (positive == null) {
				StepsHistoryEvidence.NO_OBSERVATION
			} else {
				StepsHistoryEvidence.LEGACY_RECORDED
			},
			materialization = StepsHistoryMaterialization.DEGRADED,
			coverage = StepsHistoryCoverage.UNKNOWN,
			reasons = reasons,
		)
	}

	private fun unattributedLegacy(steps: Int?): StepsSegmentHistoryResult =
		legacy(steps = steps, captureCoveredWholeRun = true).let { legacy ->
			legacy.copy(
				availability = StepsHistoryAvailability.UNAVAILABLE,
				reasons = legacy.reasons + StepsHistoryReason.LEGACY_UNATTRIBUTED,
			)
		}

	private fun unavailable(reason: StepsHistoryReason) = StepsSegmentHistoryResult(
		count = null,
		availability = StepsHistoryAvailability.UNAVAILABLE,
		evidence = StepsHistoryEvidence.NO_OBSERVATION,
		materialization = StepsHistoryMaterialization.FAILED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		reasons = setOf(reason),
	)

	private fun failed(reason: StepsHistoryReason) = StepsSegmentHistoryResult(
		count = null,
		availability = StepsHistoryAvailability.AVAILABLE,
		evidence = StepsHistoryEvidence.NO_OBSERVATION,
		materialization = StepsHistoryMaterialization.FAILED,
		coverage = StepsHistoryCoverage.UNKNOWN,
		reasons = setOf(reason),
	)

	private fun deleted() = StepsSegmentHistoryResult(
		count = null,
		availability = StepsHistoryAvailability.DELETED,
		evidence = StepsHistoryEvidence.NO_OBSERVATION,
		materialization = StepsHistoryMaterialization.READY,
		coverage = StepsHistoryCoverage.NONE,
		reasons = setOf(StepsHistoryReason.DELETED_FACTS),
	)

	private data class HistoricalStepsWriterBinding(
		val owner: String,
		val ownerGeneration: Long,
		val projectionId: String?,
		val projectionVersion: Int?,
		val bindingGeneration: Long?,
	)

	private companion object {
		const val COMPLETE_STOP_STATUS = "COMPLETE"
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
		const val MANUAL_SESSION_CAPTURE_MASK = 1L shl 0
		const val AUTOMATIC_SESSION_CAPTURE_MASK = 1L shl 1
		val TERMINAL_SERVICE_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
	}
}

internal data class StepsSegmentHistoryResult(
	val count: Long?,
	val availability: StepsHistoryAvailability,
	val evidence: StepsHistoryEvidence,
	val materialization: StepsHistoryMaterialization,
	val coverage: StepsHistoryCoverage,
	val reasons: Set<StepsHistoryReason>,
) {
	init {
		require(count == null || count >= 0L)
		if (availability == StepsHistoryAvailability.DELETED) {
			require(count == null)
		}
		when (evidence) {
			StepsHistoryEvidence.COVERED_ZERO -> require(count == 0L)
			StepsHistoryEvidence.RECORDED,
			StepsHistoryEvidence.LEGACY_RECORDED -> require(count != null && count > 0L)
			StepsHistoryEvidence.NO_OBSERVATION,
			StepsHistoryEvidence.BASELINE -> require(count == null)
		}
	}
}

internal enum class StepsHistoryAvailability { DISABLED, AVAILABLE, DELETED, UNAVAILABLE }
internal enum class StepsHistoryEvidence {
	NO_OBSERVATION,
	BASELINE,
	COVERED_ZERO,
	RECORDED,
	LEGACY_RECORDED,
}
internal enum class StepsHistoryMaterialization { NOT_APPLICABLE, MATERIALIZING, READY, DEGRADED, FAILED }
internal enum class StepsHistoryCoverage { NONE, COMPLETE, PARTIAL, UNKNOWN }
internal enum class StepsHistoryReason {
	SOURCE_NOT_CAPTURED,
	SEGMENT_MEMBERSHIP_INCOMPLETE,
	SERVICE_RUN_MISSING,
	SERVICE_RUN_MEMBERSHIP_MISMATCH,
	SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
	SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
	MANIFEST_MISSING,
	MANIFEST_MEMBERSHIP_MISMATCH,
	MANIFEST_INTEGRITY_FAILED,
	SOURCE_POLICY_ATTRIBUTION_INVALID,
	STEP_FACT_INTEGRITY_FAILED,
	MIXED_WRITER_WITHIN_SERVICE_RUN,
	UNKNOWN_WRITER,
	CANDIDATE_PROVENANCE_INCOMPLETE,
	CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN,
	SERVICE_RUN_ACTIVE,
	LEGACY_UNATTRIBUTED,
	LEGACY_REPLAY_UNVERIFIED,
	LEGACY_ZERO_UNVERIFIED,
	PRODUCT_LANE_MISSING,
	PRODUCT_LANE_INVALID,
	PRODUCT_LANE_BEHIND,
	PRODUCT_LANE_CUTOFF_BEFORE_TARGET,
	PRODUCT_LANE_RETIRED_BEFORE_TARGET,
	TARGET_BEFORE_LANE_ACTIVATION,
	TERMINAL_PROJECTION_FAILURE,
	COMPLETENESS_MISSING,
	COMPLETENESS_INVALID,
	APP_DRAIN_INCOMPLETE,
	STOP_INCOMPLETE,
	UNRESOLVED_PROVIDER_SEQUENCE,
	PROVIDER_COMPLETENESS_UNOBSERVABLE,
	FACTS_MISSING_FOR_ADMITTED_RUN,
	DELETED_FACTS,
	RESET_GAP,
	PARTIAL_FACT,
	OUTSIDE_RETAINED_FLOOR,
	RETENTION_CROSSES_SEGMENT,
	RETENTION_TRUNCATED_RUN,
	SOURCE_EVIDENCE_STATE_MISSING,
	STALE_COLLECTED_DATA_EPOCH,
	BATCH_DEPENDENCY_OVERFLOW,
	COUNT_OVERFLOW,
}

internal data class StepsMeasuredFactRead(
    val segment: SessionSegment,
    val fact: StepFactRevisionEntity?,
    val steps: StepsSegmentHistoryResult,
)

private fun List<*>.isBoundedUniqueMeasuredStepsSelection(): Boolean =
	size <= MAX_MEASURED_STEPS_SELECTION && distinct().size == size

private const val MAX_MEASURED_STEPS_SELECTION = 100
