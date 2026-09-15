package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.PressureFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import java.time.DateTimeException
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Selects checksum-qualified Pressure history and composes only explicit replacement-run groups. */
@Suppress("LargeClass", "TooManyFunctions")
internal class PressureHistorySelector @Inject constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
) {
	internal suspend fun selectBySegmentId(segmentId: Long): PressurePhysicalHistory? =
		selectPhysicalBySegmentIds(listOf(segmentId))[segmentId]

	internal suspend fun selectPhysicalBySegmentIds(
		segmentIds: List<Long>,
	): Map<Long, PressurePhysicalHistory> = database.withTransaction {
		val distinctIds = segmentIds.distinct()
		if (distinctIds.isEmpty()) return@withTransaction emptyMap()
		require(distinctIds.size <= PRESSURE_HISTORY_SEGMENT_BATCH_CAP) {
			"At most $PRESSURE_HISTORY_SEGMENT_BATCH_CAP Pressure history rows may be selected"
		}
		val seeds = database.trackingHistoryReadDao().segments(distinctIds)
		selectManyInTransaction(seeds)
			.filter { it.segment.id in distinctIds }
			.associateBy { it.segment.id }
	}

	internal suspend fun selectLogicalBySegmentIds(
		segmentIds: List<Long>,
	): List<PressureLogicalHistoryEntry> = database.withTransaction {
		val distinctIds = segmentIds.distinct()
		if (distinctIds.isEmpty()) return@withTransaction emptyList()
		require(distinctIds.size <= PRESSURE_HISTORY_SEGMENT_BATCH_CAP) {
			"At most $PRESSURE_HISTORY_SEGMENT_BATCH_CAP Pressure history rows may be selected"
		}
		val seeds = database.trackingHistoryReadDao().segments(distinctIds)
		selectLogicalFromSeedsInTransaction(seeds)
	}

	/**
	 * Source-local ordinary discovery driven by Pressure facts and exact run/segment bindings.
	 * Generic [SessionSegment.sampleCount] is deliberately not consulted. Payload-free retention
	 * markers are exposed only by [discoverRecentPressureOnlyByPressureFacts], which authenticates
	 * their opaque scope after complete logical membership expansion.
	 */
	internal suspend fun discoverRecentLogicalByPressureFacts(
		limit: Int,
		beforeStartTimeMs: Long? = null,
		beforeSegmentId: Long? = null,
	): List<PressureLogicalHistoryEntry> = database.withTransaction {
		require(limit in 1..PRESSURE_HISTORY_SEGMENT_BATCH_CAP)
		val seeds = database.pressureFactRevisionDao().historySegmentCandidatePage(
			limit = limit,
			beforeStartTimeMs = beforeStartTimeMs,
			beforeSegmentId = beforeSegmentId,
		)
		selectLogicalFromSeedsInTransaction(seeds)
	}

	/**
	 * Finds a bounded Pressure-only page from retained facts or authenticated retention-loss markers.
	 * Newer mixed or invalid candidates do not consume the caller's result limit inside the explicit
	 * scan budget. All candidate pages and dependency batches share one Room snapshot; physical
	 * members remain internal to the returned logical entries.
	 */
	internal suspend fun discoverRecentPressureOnlyByPressureFacts(
		limit: Int,
	): List<PressureLogicalHistoryEntry> = database.withTransaction {
		discoverRecentPressureOnlyInTransaction(limit)
	}

	/** Caller owns the Room snapshot used to compose live and imported Pressure candidates. */
	internal suspend fun discoverRecentPressureOnlyInTransaction(
		limit: Int,
	): List<PressureLogicalHistoryEntry> {
		require(limit in 1..PRESSURE_HISTORY_SEGMENT_BATCH_CAP)
		val accepted = linkedMapOf<PressureHistoryEntryIdentity, PressureLogicalHistoryEntry>()
		var beforeLogicalRecencyStartMs: Long? = null
		var beforeLogicalRecencySegmentId: Long? = null
		var scannedCandidateCount = 0

		while (accepted.size < limit && scannedCandidateCount < PRESSURE_DISCOVERY_CANDIDATE_BUDGET) {
			currentCoroutineContext().ensureActive()
			val pageLimit = minOf(
				PRESSURE_DISCOVERY_CANDIDATE_PAGE_SIZE,
				PRESSURE_DISCOVERY_CANDIDATE_BUDGET - scannedCandidateCount,
			)
			val candidates = database.pressureFactRevisionDao().pressureLogicalHistoryCandidatePage(
				limit = pageLimit,
				beforeLogicalRecencyStartMs = beforeLogicalRecencyStartMs,
				beforeLogicalRecencySegmentId = beforeLogicalRecencySegmentId,
			)
			if (candidates.isEmpty()) break
			scannedCandidateCount += candidates.size
			val seeds = candidates.map { it.segment }

			val entriesByMemberId = buildMap {
				selectLogicalFromSeedsInTransaction(
					seeds = seeds,
					memberBudget = PRESSURE_DISCOVERY_MEMBER_BUDGET,
				).forEach { entry ->
					entry.physicalMembers.forEach { member -> put(member.segment.id, entry) }
				}
			}
			seeds.forEach { seed ->
				val entry = entriesByMemberId[seed.id] ?: return@forEach
				if (
					entry.identity is PressureHistoryEntryIdentity.Logical &&
					entry.hasExactPressureOnlyIntent &&
					entry.isOrdinarilyDiscoverable
				) {
					accepted.putIfAbsent(entry.identity, entry)
				}
			}

			val lastScanned = candidates.last()
			beforeLogicalRecencyStartMs = lastScanned.logicalRecencyStartMs
			beforeLogicalRecencySegmentId = lastScanned.logicalRecencySegmentId
			if (candidates.size < pageLimit) break
		}

		accepted.values.sortedWith(compareByDescending<PressureLogicalHistoryEntry> { entry ->
			entry.physicalMembers.maxOf { it.segment.startTimeMs }
		}.thenByDescending { entry ->
			entry.physicalMembers.maxOf { it.segment.id }
		}).take(limit)
	}

	private suspend fun selectLogicalFromSeedsInTransaction(
		seeds: List<SessionSegment>,
		memberBudget: Int = PRESSURE_HISTORY_SEGMENT_BATCH_CAP,
	): List<PressureLogicalHistoryEntry> {
		if (seeds.isEmpty()) return emptyList()
		val expansion = expandPressureLogicalMembership(database, seeds, memberBudget)
		val snapshot = loadPressureHistoryBatchSnapshot(
			database = database,
			segments = expansion.segments,
			logicalMembershipFailures = expansion.failures,
		)
		return PressureLogicalHistoryComposer.compose(
			selectManyWithSnapshot(expansion.segments, snapshot),
		)
	}

	/** Caller must already hold the Room transaction defining the source-local history snapshot. */
	internal suspend fun selectManyInTransaction(
		segments: List<SessionSegment>,
		memberBudget: Int = PRESSURE_HISTORY_SEGMENT_BATCH_CAP,
	): List<PressurePhysicalHistory> {
		if (segments.isEmpty()) return emptyList()
		val expansion = expandPressureLogicalMembership(database, segments, memberBudget)
		val snapshot = loadPressureHistoryBatchSnapshot(
			database = database,
			segments = expansion.segments,
			logicalMembershipFailures = expansion.failures,
		)
		return selectManyWithSnapshot(expansion.segments, snapshot)
	}

	/** Pure seam for correction, attribution, and replacement-run contract tests. */
	internal fun selectManyWithSnapshot(
		segments: List<SessionSegment>,
		snapshot: PressureHistoryBatchSnapshot,
	): List<PressurePhysicalHistory> {
		val validatedSnapshot = snapshot.withLogicalManifestMembershipValidation(segments)
		return segments.map { segment -> select(segment, validatedSnapshot) }
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "ReturnCount")
	private fun select(
		segment: SessionSegment,
		snapshot: PressureHistoryBatchSnapshot,
	): PressurePhysicalHistory {
		if (snapshot.terminalFailureOverflow || snapshot.factRevisionOverflow) {
			return unavailable(
				segment,
				HistoricalCaptureFailure.BATCH_DEPENDENCY_OVERFLOW,
				PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW,
			)
		}
		val logicalTrackingId = segment.logicalTrackingId
		val serviceRunId = segment.serviceRunId
		if (logicalTrackingId == null && serviceRunId == null) {
			return unavailable(
				segment,
				HistoricalCaptureFailure.LEGACY_UNATTRIBUTED,
				PressureHistoryReason.LEGACY_UNATTRIBUTED,
			)
		}
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
			return unavailable(
				segment,
				HistoricalCaptureFailure.SEGMENT_MEMBERSHIP_INCOMPLETE,
				PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE,
			)
		}
		val membershipFailure = snapshot.logicalMembershipFailures[logicalTrackingId]
		if (membershipFailure != null) {
			val captureFailure = when (membershipFailure) {
				PressureHistoryReason.BATCH_DEPENDENCY_OVERFLOW ->
					HistoricalCaptureFailure.BATCH_DEPENDENCY_OVERFLOW
				PressureHistoryReason.LOGICAL_MANIFEST_REVISION_UNION_INVALID,
				PressureHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH,
				PressureHistoryReason.MANIFEST_INTEGRITY_FAILED ->
					HistoricalCaptureFailure.MANIFEST_INTEGRITY_FAILED
				else -> HistoricalCaptureFailure.SEGMENT_MEMBERSHIP_INCOMPLETE
			}
			return unavailable(segment, captureFailure, membershipFailure)
		}
		val serviceRun = snapshot.serviceRuns[serviceRunId]
			?: return unavailable(
				segment,
				HistoricalCaptureFailure.SERVICE_RUN_MISSING,
				PressureHistoryReason.SERVICE_RUN_MISSING,
			)
		if (serviceRun.logicalTrackingId != logicalTrackingId) {
			return unavailable(
				segment,
				HistoricalCaptureFailure.SERVICE_RUN_MEMBERSHIP_MISMATCH,
				PressureHistoryReason.SERVICE_RUN_MEMBERSHIP_MISMATCH,
			)
		}
		if (
			serviceRun.presentationAcknowledgement ==
			SourceServiceRunEntity.PRESENTATION_LEGACY_UNVERIFIABLE
		) {
			return unavailable(
				segment,
				HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
				PressureHistoryReason.SERVICE_RUN_SEGMENT_BINDING_UNVERIFIABLE,
			)
		}
		if (serviceRun.sessionSegmentId != segment.id) {
			return unavailable(
				segment,
				HistoricalCaptureFailure.SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
				PressureHistoryReason.SERVICE_RUN_SEGMENT_BINDING_MISMATCH,
			)
		}

		val manifests = snapshot.manifestsByRun[serviceRunId].orEmpty()
			.sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (manifests.isEmpty()) {
			return unavailable(
				segment,
				HistoricalCaptureFailure.MANIFEST_MISSING,
				PressureHistoryReason.MANIFEST_MISSING,
			)
		}
		val sourcesByRevision = linkedMapOf<Long, List<SessionManifestSourceEntity>>()
		for (manifest in manifests) {
			if (manifest.logicalTrackingId != logicalTrackingId ||
				manifest.serviceRunId != serviceRunId
			) {
				return unavailable(
					segment,
					HistoricalCaptureFailure.MANIFEST_MEMBERSHIP_MISMATCH,
					PressureHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH,
				)
			}
			val sources = snapshot.sourcesByManifest[
				PressureManifestKey(logicalTrackingId, manifest.manifestRevision)
			].orEmpty()
			if (!SessionManifestIntegrity.verify(manifest, sources) || !hasValidZone(manifest.zoneId)) {
				return unavailable(
					segment,
					HistoricalCaptureFailure.MANIFEST_INTEGRITY_FAILED,
					PressureHistoryReason.MANIFEST_INTEGRITY_FAILED,
				)
			}
			sourcesByRevision[manifest.manifestRevision] = sources
		}
		if (!SessionManifestIntegrity.hasValidServiceRunTimeline(serviceRun, manifests)) {
			return unavailable(
				segment,
				HistoricalCaptureFailure.MANIFEST_INTEGRITY_FAILED,
				PressureHistoryReason.MANIFEST_INTEGRITY_FAILED,
			)
		}
		val captureAuthority = historicalCaptureAuthority(manifests, sourcesByRevision)
		val exactCaptureAuthority = captureAuthority as? HistoricalCaptureAuthority.Exact
		if (exactCaptureAuthority == null) {
			return PressurePhysicalHistory(
				segment = segment,
				captureAuthority = captureAuthority,
				windows = emptyList(),
				availability = PressureHistoryAvailability.UNAVAILABLE,
				evidence = PressureHistoryEvidence.NO_OBSERVATION,
				materialization = PressureHistoryMaterialization.FAILED,
				coverage = PressureHistoryCoverage.UNKNOWN,
				reasons = setOf(PressureHistoryReason.MANIFEST_INTEGRITY_FAILED),
			)
		}

		val scopeDigest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_PRESSURE,
			purpose = PressureFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		if (scopeDigest in snapshot.deletionFenceDigests) {
			return deleted(segment, exactCaptureAuthority)
		}

		val pressureBindings = linkedMapOf<Long, SessionManifestSourceEntity>()
		for ((revision, sources) in sourcesByRevision) {
			val matching = sources.filter { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
					source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}
			if (matching.size > 1) {
				return failed(
					segment,
					exactCaptureAuthority,
					PressureHistoryReason.PRESSURE_FACT_INTEGRITY_FAILED,
				)
			}
			matching.singleOrNull()?.let { pressureBindings[revision] = it }
		}
		val runFactRevisions = snapshot.factRevisionsByRun[serviceRunId].orEmpty()
		if (pressureBindings.isEmpty()) {
			if (runFactRevisions.isNotEmpty()) {
				return failed(
					segment,
					exactCaptureAuthority,
					PressureHistoryReason.PRESSURE_FACT_INTEGRITY_FAILED,
				)
			}
			val policies = manifests.map { snapshot.pressurePolicies[it.sourcePolicyRevision] }
			return if (policies.all { it != null && !it.enabled }) {
				disabled(segment, exactCaptureAuthority)
			} else {
				unavailable(segment, exactCaptureAuthority, PressureHistoryReason.SOURCE_NOT_CAPTURED)
			}
		}
		val manifestsByRevision = manifests.associateBy(SessionManifestVersionEntity::manifestRevision)
		if (pressureBindings.any { (revision, binding) ->
				val manifest = manifestsByRevision[revision]
				val policyRevision = manifest?.sourcePolicyRevision
				!hasValidPressureCaptureAuthority(
					policy = policyRevision?.let(snapshot.pressurePolicies::get),
					consent = snapshot.pressureCaptureConsents[binding.consentEpoch],
					manifestPolicyRevision = policyRevision ?: Long.MIN_VALUE,
					binding = binding,
				)
			}
		) {
			return failed(
				segment,
				exactCaptureAuthority,
				PressureHistoryReason.SOURCE_POLICY_ATTRIBUTION_INVALID,
			)
		}

		val writers = pressureBindings.values.map(::writerBinding).distinct()
		if (writers.size != 1) {
			return failed(
				segment,
				exactCaptureAuthority,
				PressureHistoryReason.MIXED_WRITER_WITHIN_SERVICE_RUN,
			)
		}
		val writer = writers.single()
		if (!writer.isPressureFactWriter) {
			return unavailable(segment, exactCaptureAuthority, PressureHistoryReason.UNKNOWN_WRITER)
		}
		val lane = snapshot.productLanes[PressureLaneKey(
			bindingGeneration = writer.bindingGeneration,
			projectionId = writer.projectionId,
			projectionVersion = writer.projectionVersion,
		)] ?: return failed(
			segment,
			exactCaptureAuthority,
			PressureHistoryReason.PRODUCT_LANE_MISSING,
		)
		val requiredModeMask = manifests.map(SessionManifestVersionEntity::sessionMode)
			.distinct().singleOrNull()?.let(::sessionCaptureModeMask)
		if (requiredModeMask == null || !laneExecutionAuthority.owns(lane) ||
			!isValidHistoricalLane(lane) || lane.captureModeMask and requiredModeMask == 0L
		) {
			return failed(
				segment,
				exactCaptureAuthority,
				PressureHistoryReason.PRODUCT_LANE_INVALID,
			)
		}
		return selectCandidate(
			segment = segment,
			serviceRun = serviceRun,
			captureAuthority = exactCaptureAuthority,
			manifests = manifests,
			pressureBindings = pressureBindings,
			lane = lane,
			captureCoveredWholeRun = pressureBindings.size == manifests.size,
			snapshot = snapshot,
		)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "ReturnCount")
	private fun selectCandidate(
		segment: SessionSegment,
		serviceRun: SourceServiceRunEntity,
		captureAuthority: HistoricalCaptureAuthority.Exact,
		manifests: List<SessionManifestVersionEntity>,
		pressureBindings: Map<Long, SessionManifestSourceEntity>,
		lane: SourceProductProjectionLaneEntity,
		captureCoveredWholeRun: Boolean,
		snapshot: PressureHistoryBatchSnapshot,
	): PressurePhysicalHistory {
		val logicalTrackingId = serviceRun.logicalTrackingId
		val serviceRunId = serviceRun.serviceRunId
		val evidenceState = snapshot.evidenceState ?: return failed(
			segment,
			captureAuthority,
			PressureHistoryReason.SOURCE_EVIDENCE_STATE_MISSING,
		)
		val markerState = retentionTruncationState(
			snapshot = snapshot,
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
			collectedDataEpoch = evidenceState.collectedDataEpoch,
		)
		if (markerState == PressureRetentionTruncationState.INVALID) {
			return failed(
				segment,
				captureAuthority,
				PressureHistoryReason.RETENTION_TRUNCATION_MARKER_INVALID,
			)
		}
		val retentionTruncated = markerState == PressureRetentionTruncationState.VALID
		if (!retentionTruncated &&
			evidenceState.retainedFromMs?.let { segment.endTimeMs < it } == true
		) {
			return unavailable(segment, captureAuthority, PressureHistoryReason.OUTSIDE_RETAINED_FLOOR)
		}
		val retentionCrossesSegment = evidenceState.retainedFromMs?.let { retainedFromMs ->
			segment.startTimeMs < retainedFromMs && segment.endTimeMs >= retainedFromMs
		} == true
		val completeness = snapshot.completenessByRun[serviceRunId].orEmpty()
			.filter { it.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE }
		if (!hasValidPressureCompleteness(completeness, logicalTrackingId, serviceRunId)) {
			return failed(segment, captureAuthority, PressureHistoryReason.COMPLETENESS_INVALID)
		}
		val targetOrdinal = completeness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal)
			.maxOrNull()
		val revisions = snapshot.factRevisionsByRun[serviceRunId].orEmpty()
		if (logicalTrackingId in snapshot.invalidFactScopeLogicalIds) {
			return failed(
				segment,
				captureAuthority,
				PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE,
			)
		}
		if (completeness.singleOrNull()?.registrationGeneration == 0L) {
			val retainedFromMs = evidenceState.retainedFromMs
			val hasRetainedFacts = revisions.any { fact ->
				retainedFromMs == null || fact.intervalEndTimeMs >= retainedFromMs
			}
			if (hasRetainedFacts) {
				return failed(
					segment,
					captureAuthority,
					PressureHistoryReason.UNAVAILABLE_SENTINEL_WITH_RETAINED_FACTS,
				)
			} else if (!retentionTruncated) {
				return providerUnavailable(segment, captureAuthority)
			}
		}
		if (revisions.isNotEmpty() && targetOrdinal == null ||
			targetOrdinal != null && revisions.any { fact ->
				fact.sourceAdmissionOrdinal < lane.activationOrdinal ||
					fact.sourceAdmissionOrdinal > targetOrdinal
			}
		) {
			return failed(segment, captureAuthority, PressureHistoryReason.COMPLETENESS_INVALID)
		}
		val manifestsByRevision = manifests.associateBy(SessionManifestVersionEntity::manifestRevision)
		val correctionFailure = correctionFailure(revisions, manifestsByRevision)
		if (correctionFailure != null) return failed(segment, captureAuthority, correctionFailure)

		val nextManifestByRevision = manifests.mapIndexed { index, manifest ->
			manifest.manifestRevision to manifests.getOrNull(index + 1)
		}.toMap()
		for (fact in revisions) {
			val manifest = manifestsByRevision[fact.manifestRevision]
			val binding = pressureBindings[fact.manifestRevision]
			val nextManifest = nextManifestByRevision[fact.manifestRevision]
			if (manifest == null || binding == null ||
				fact.logicalTrackingId != logicalTrackingId || fact.serviceRunId != serviceRunId ||
				fact.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
				fact.sourcePolicyRevision != manifest.sourcePolicyRevision ||
				fact.captureConsentEpoch != binding.consentEpoch ||
				fact.writerProjectionId != lane.projectionId ||
				fact.writerProjectionVersion != lane.projectionVersion ||
				fact.writerBindingGeneration != lane.bindingGeneration ||
				fact.clockDomainId != serviceRun.bootId || manifest.effectiveBootId != serviceRun.bootId ||
				fact.windowStartElapsedRealtimeNanos < serviceRun.startedElapsedNanos ||
				fact.windowStartElapsedRealtimeNanos < manifest.effectiveElapsedRealtimeNanos ||
				nextManifest?.effectiveElapsedRealtimeNanos?.let { nextEffective ->
					fact.windowStartElapsedRealtimeNanos >= nextEffective ||
						fact.windowEndElapsedRealtimeNanos > nextEffective
				} == true || lane.activatedRolloutRevision > manifest.rolloutRevision ||
				!hasValidPressureFactIdentity(fact, binding)
			) {
				return failed(
					segment,
					captureAuthority,
					PressureHistoryReason.PRESSURE_FACT_INTEGRITY_FAILED,
				)
			}
			if (fact.collectedDataEpoch != evidenceState.collectedDataEpoch) {
				return failed(
					segment,
					captureAuthority,
					PressureHistoryReason.STALE_COLLECTED_DATA_EPOCH,
				)
			}
		}
		val effectiveFacts = revisions.groupBy(PressureFactRevisionEntity::logicalFactId)
			.values.map { lineage -> lineage.maxBy(PressureFactRevisionEntity::semanticRevision) }
			.filter { fact ->
				evidenceState.retainedFromMs?.let { fact.intervalEndTimeMs >= it } != false
			}.sortedWith(compareBy(
				PressureFactRevisionEntity::windowStartElapsedRealtimeNanos,
				PressureFactRevisionEntity::windowEndElapsedRealtimeNanos,
				PressureFactRevisionEntity::logicalFactId,
			))
		val windows = effectiveFacts.map { fact -> fact.toHistoryWindow(
			zoneId = requireNotNull(manifestsByRevision[fact.manifestRevision]).zoneId,
		) }

		val serviceRunCompleted = serviceRun.completedAtMs != null &&
			serviceRun.state in TERMINAL_SERVICE_RUN_STATES
		val reasons = linkedSetOf<PressureHistoryReason>()
		if (!captureCoveredWholeRun) reasons += PressureHistoryReason.CAPTURE_NOT_ENABLED_FOR_WHOLE_RUN
		if (!serviceRunCompleted) reasons += PressureHistoryReason.SERVICE_RUN_ACTIVE
		if (retentionCrossesSegment) reasons += PressureHistoryReason.RETENTION_CROSSES_SEGMENT
		if (retentionTruncated) reasons += PressureHistoryReason.RETENTION_TRUNCATED
		if (completeness.isEmpty()) reasons += PressureHistoryReason.COMPLETENESS_MISSING
		if (completeness.any { !it.appDrainComplete }) {
			reasons += PressureHistoryReason.APP_DRAIN_INCOMPLETE
		}
		if (completeness.any { it.stopStatus != COMPLETE_STOP_STATUS }) {
			reasons += PressureHistoryReason.STOP_INCOMPLETE
		}
		if (completeness.any { it.unresolvedSequenceStart != null }) {
			reasons += PressureHistoryReason.UNRESOLVED_PROVIDER_SEQUENCE
		}
		if (completeness.any { it.providerCoverage != COMPLETE_PROVIDER_COVERAGE }) {
			reasons += PressureHistoryReason.PROVIDER_COMPLETENESS_UNOBSERVABLE
		}
		if (effectiveFacts.any {
			it.qualification == PressureFactRevisionEntity.QUALIFICATION_PARTIAL
		}) reasons += PressureHistoryReason.PARTIAL_FACT
		if (targetOrdinal != null && revisions.isEmpty() &&
			!retentionCrossesSegment && !retentionTruncated
		) {
			reasons += PressureHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN
		}

		val laneCutoffOrdinal = lane.captureAdmissionCutoffOrdinal
		val materialization = when {
			targetOrdinal != null && targetOrdinal < lane.activationOrdinal -> {
				reasons += PressureHistoryReason.TARGET_BEFORE_LANE_ACTIVATION
				PressureHistoryMaterialization.FAILED
			}
			targetOrdinal != null && snapshot.terminalFailures.any { failure ->
				failure.projectionId == lane.projectionId &&
					failure.projectionVersion == lane.projectionVersion &&
					failure.admissionOrdinal >= lane.activationOrdinal &&
					failure.admissionOrdinal <= targetOrdinal
			} -> {
				reasons += PressureHistoryReason.TERMINAL_PROJECTION_FAILURE
				PressureHistoryMaterialization.FAILED
			}
			targetOrdinal != null && laneCutoffOrdinal != null && targetOrdinal > laneCutoffOrdinal -> {
				reasons += PressureHistoryReason.PRODUCT_LANE_CUTOFF_BEFORE_TARGET
				PressureHistoryMaterialization.FAILED
			}
			targetOrdinal != null &&
				lane.status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
				lane.contiguousAdmissionOrdinal < targetOrdinal -> {
				reasons += PressureHistoryReason.PRODUCT_LANE_RETIRED_BEFORE_TARGET
				PressureHistoryMaterialization.FAILED
			}
			targetOrdinal != null && lane.contiguousAdmissionOrdinal < targetOrdinal -> {
				reasons += PressureHistoryReason.PRODUCT_LANE_BEHIND
				PressureHistoryMaterialization.MATERIALIZING
			}
			!serviceRunCompleted -> PressureHistoryMaterialization.MATERIALIZING
			completeness.isEmpty() -> PressureHistoryMaterialization.FAILED
			PressureHistoryReason.FACTS_MISSING_FOR_ADMITTED_RUN in reasons ->
				PressureHistoryMaterialization.FAILED
			else -> PressureHistoryMaterialization.READY
		}
		val acquisitionComplete = completeness.isNotEmpty() && completeness.all { row ->
			row.appDrainComplete && row.stopStatus == COMPLETE_STOP_STATUS &&
				row.unresolvedSequenceStart == null && row.unresolvedSequenceEnd == null &&
				row.providerCoverage == COMPLETE_PROVIDER_COVERAGE
		}
		val coverage = when {
			retentionTruncated -> PressureHistoryCoverage.PARTIAL
			windows.isEmpty() -> PressureHistoryCoverage.NONE
			materialization == PressureHistoryMaterialization.READY && acquisitionComplete &&
				captureCoveredWholeRun && !retentionCrossesSegment &&
				PressureHistoryReason.PARTIAL_FACT !in reasons -> PressureHistoryCoverage.COMPLETE
			else -> PressureHistoryCoverage.PARTIAL
		}
		return PressurePhysicalHistory(
			segment = segment,
			captureAuthority = captureAuthority,
			windows = windows,
			availability = PressureHistoryAvailability.AVAILABLE,
			evidence = if (windows.isEmpty()) {
				PressureHistoryEvidence.NO_OBSERVATION
			} else {
				PressureHistoryEvidence.RECORDED
			},
			materialization = materialization,
			coverage = coverage,
			reasons = reasons,
		)
	}

	private fun retentionTruncationState(
		snapshot: PressureHistoryBatchSnapshot,
		logicalTrackingId: String,
		serviceRunId: String,
		collectedDataEpoch: Long,
	): PressureRetentionTruncationState {
		val digest = PressureFactRevisionIntegrity.retentionTruncationIdentity(
			logicalTrackingId = logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		val marker = snapshot.retentionTruncationFencesByDigest[digest]
			?: return PressureRetentionTruncationState.ABSENT
		return if (PressureFactRevisionIntegrity.isRetentionTruncationFence(
				fence = marker,
				logicalTrackingId = logicalTrackingId,
				serviceRunId = serviceRunId,
				collectedDataEpoch = collectedDataEpoch,
			)
		) {
			PressureRetentionTruncationState.VALID
		} else {
			PressureRetentionTruncationState.INVALID
		}
	}

	private fun correctionFailure(
		revisions: List<PressureFactRevisionEntity>,
		manifestsByRevision: Map<Long, SessionManifestVersionEntity>,
	): PressureHistoryReason? {
		if (revisions.map(PressureFactRevisionEntity::sourceAdmissionOrdinal).distinct().size !=
			revisions.size
		) return PressureHistoryReason.PRESSURE_FACT_INTEGRITY_FAILED
		for (lineage in revisions.groupBy(PressureFactRevisionEntity::logicalFactId).values) {
			val ordered = lineage.sortedBy(PressureFactRevisionEntity::semanticRevision)
			if (ordered.first().semanticRevision != FIRST_SEMANTIC_REVISION) {
				return PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE
			}
			for ((prior, current) in ordered.zipWithNext()) {
				val expectedRevision = try {
					Math.addExact(prior.semanticRevision, 1L)
				} catch (_: ArithmeticException) {
					return PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE
				}
				val priorAuthority = prior.correctionAuthority(manifestsByRevision)
				val currentAuthority = current.correctionAuthority(manifestsByRevision)
				if (current.semanticRevision != expectedRevision ||
					current.sourceAdmissionOrdinal <= prior.sourceAdmissionOrdinal ||
					current.sourceEventId != prior.sourceEventId ||
					current.writerBindingGeneration != prior.writerBindingGeneration ||
					current.logicalTrackingId != prior.logicalTrackingId ||
					current.serviceRunId != prior.serviceRunId || current.purpose != prior.purpose ||
					current.collectedDataEpoch != prior.collectedDataEpoch ||
					priorAuthority == null || currentAuthority != priorAuthority
				) return PressureHistoryReason.PRESSURE_FACT_CORRECTION_INCOMPLETE
			}
		}
		return null
	}

	private fun PressureFactRevisionEntity.correctionAuthority(
		manifestsByRevision: Map<Long, SessionManifestVersionEntity>,
	): PressureCorrectionAuthority? {
		val manifest = manifestsByRevision[manifestRevision] ?: return null
		return PressureCorrectionAuthority(
			manifestRevision = manifestRevision,
			sourcePolicyRevision = sourcePolicyRevision,
			captureConsentEpoch = captureConsentEpoch,
			clockDomainId = clockDomainId,
			wallTimeUncertaintyMs = wallTimeUncertaintyMs,
			manifestClockDomainId = manifest.effectiveBootId,
			zoneId = manifest.zoneId,
		)
	}

	@Suppress("ComplexCondition")
	private fun hasValidPressureCaptureAuthority(
		policy: SourcePolicyEntity?,
		consent: SourceConsentEpochEntity?,
		manifestPolicyRevision: Long,
		binding: SessionManifestSourceEntity,
	): Boolean = policy != null && policy.policyRevision == manifestPolicyRevision &&
		policy.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE && policy.enabled &&
		policy.capturePersistenceEligible && policy.captureConsentEpoch == binding.consentEpoch &&
		policy.qosCode == binding.qosCode &&
		binding.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
		binding.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		binding.persistenceEligible && binding.qosCode in MIN_CAPTURE_QOS..MAX_CAPTURE_QOS &&
		consent != null && consent.sourceKind == SourceDestinationOwnerEntity.SOURCE_PRESSURE &&
		consent.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		consent.epoch == binding.consentEpoch && consent.eligible && consent.persistenceEligible &&
		consent.policyRevision <= manifestPolicyRevision

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
	private fun hasValidPressureCompleteness(
		rows: List<SourceSessionCompletenessEntity>,
		logicalTrackingId: String,
		serviceRunId: String,
	): Boolean {
		if (rows.any { it.registrationGeneration == 0L } && rows.size != 1) return false
		if (rows.map(SourceSessionCompletenessEntity::registrationGeneration).distinct().size !=
			rows.size
		) return false
		for (row in rows) {
			val unresolvedStart = row.unresolvedSequenceStart
			val unresolvedEnd = row.unresolvedSequenceEnd
			if (row.logicalTrackingId != logicalTrackingId || row.serviceRunId != serviceRunId ||
				row.sourceKind != SourceDestinationOwnerEntity.SOURCE_PRESSURE ||
				row.sourceInstanceId.isBlank() || row.registrationGeneration < 0L ||
				row.lastAdmissionOrdinal?.let { it <= 0L } == true ||
				row.lastSourceSequence?.let { it < 0L } == true ||
				(row.lastAdmissionOrdinal == null) != (row.lastSourceSequence == null) ||
				(unresolvedStart == null) != (unresolvedEnd == null) ||
				unresolvedStart?.let { it <= 0L || it > requireNotNull(unresolvedEnd) } == true ||
				row.providerCoverage !in PROVIDER_COVERAGE_VALUES ||
				row.stopStatus !in STOP_STATUS_VALUES ||
				row.stopStatus == COMPLETE_STOP_STATUS && !row.appDrainComplete || row.updatedAtMs < 0L
			) return false
			if (row.registrationGeneration == 0L &&
				(row.sourceInstanceId != UNAVAILABLE_PRESSURE_INSTANCE ||
					row.lastAdmissionOrdinal != null || row.lastSourceSequence != null ||
					unresolvedStart != null || unresolvedEnd != null || !row.appDrainComplete ||
					row.providerCoverage != UNOBSERVABLE_PROVIDER_COVERAGE ||
					row.stopStatus !in UNAVAILABLE_PRESSURE_STOP_STATUSES)
			) return false
			if (row.registrationGeneration > 0L && row.sourceInstanceId == UNAVAILABLE_PRESSURE_INSTANCE) {
				return false
			}
		}
		val orderedHighWaters = rows.mapNotNull { row ->
			row.lastAdmissionOrdinal?.let { row.registrationGeneration to it }
		}.sortedBy { it.first }
		return orderedHighWaters.zipWithNext().all { (prior, current) ->
			current.second > prior.second
		}
	}

	private fun hasValidPressureFactIdentity(
		fact: PressureFactRevisionEntity,
		binding: SessionManifestSourceEntity,
	): Boolean {
		val expectedLogicalFactId =
			"${SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID}:${fact.sourceEventId}"
		return fact.logicalFactId == expectedLogicalFactId &&
			fact.mutationId == "$expectedLogicalFactId:${fact.semanticRevision}" &&
			PressureFactRevisionIntegrity.hasValidEffectChecksum(fact, binding)
	}

	private fun writerBinding(source: SessionManifestSourceEntity) = PressureWriterBinding(
		owner = source.writerOwner.orEmpty(),
		ownerGeneration = source.writerOwnerGeneration ?: Long.MIN_VALUE,
		projectionId = source.writerProjectionId.orEmpty(),
		projectionVersion = source.writerProjectionVersion ?: Int.MIN_VALUE,
		bindingGeneration = source.writerBindingGeneration ?: Long.MIN_VALUE,
	)

	private fun sessionCaptureModeMask(sessionMode: String): Long? = when (sessionMode) {
		"MANUAL" -> MANUAL_SESSION_CAPTURE_MASK
		"AUTOMATIC" -> AUTOMATIC_SESSION_CAPTURE_MASK
		else -> null
	}

	@Suppress("ComplexCondition")
	private fun isValidHistoricalLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.sourceKind != SourceDestinationOwnerEntity.SOURCE_PRESSURE ||
			lane.captureModeMask <= 0L || lane.productStage !in VALID_PRODUCT_STAGES ||
			lane.activatedRolloutRevision <= 0L || lane.activationOrdinal <= 0L ||
			lane.installedAtMs < 0L || lane.updatedAtMs < lane.installedAtMs
		) return false
		val minimumCursor = lane.activationOrdinal - 1L
		val cutoff = lane.captureAdmissionCutoffOrdinal
		if (lane.contiguousAdmissionOrdinal < minimumCursor ||
			cutoff != null && (cutoff < minimumCursor || lane.contiguousAdmissionOrdinal > cutoff)
		) return false
		if ((lane.terminalDisposition == null) != (lane.terminalAtMs == null)) return false
		return when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE ->
				lane.retentionRequired && lane.terminalDisposition == null
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> {
				if (lane.retentionRequired) return false
				if (lane.terminalDisposition == null) return cutoff == null
				val terminalAtMs = lane.terminalAtMs ?: return false
				lane.terminalDisposition ==
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
					cutoff != null && lane.contiguousAdmissionOrdinal == cutoff &&
					terminalAtMs >= lane.installedAtMs && lane.updatedAtMs >= terminalAtMs
			}
			else -> false
		}
	}

	private fun PressureFactRevisionEntity.toHistoryWindow(zoneId: String) = PressureHistoryWindow(
		logicalFactId = logicalFactId,
		semanticRevision = semanticRevision,
		sourceAdmissionOrdinal = sourceAdmissionOrdinal,
		logicalTrackingId = logicalTrackingId,
		serviceRunId = serviceRunId,
		manifestRevision = manifestRevision,
		zoneId = zoneId,
		writerProjectionId = writerProjectionId,
		writerProjectionVersion = writerProjectionVersion,
		writerBindingGeneration = writerBindingGeneration,
		intervalStartTimeMs = intervalStartTimeMs,
		intervalEndTimeMs = intervalEndTimeMs,
		wallTimeUncertaintyMs = wallTimeUncertaintyMs,
		windowStartElapsedRealtimeNanos = windowStartElapsedRealtimeNanos,
		windowEndElapsedRealtimeNanos = windowEndElapsedRealtimeNanos,
		sampleCount = sampleCount,
		meanHectopascals = meanHectopascals,
		sumSquaredDeviations = sumSquaredDeviations,
		minimumHectopascals = minimumHectopascals,
		maximumHectopascals = maximumHectopascals,
		firstHectopascals = firstHectopascals,
		lastHectopascals = lastHectopascals,
		slopeHectopascalsPerSecond = slopeHectopascalsPerSecond,
		rSquared = rSquared,
		sensorAccuracy = sensorAccuracy,
		effectiveSamplePeriodMicros = effectiveSamplePeriodMicros,
		effectiveMaximumReportLatencyMicros = effectiveMaximumReportLatencyMicros,
		targetWindowDurationNanos = targetWindowDurationNanos,
		expectedSampleCount = expectedSampleCount,
		maximumInterSampleGapNanos = maximumInterSampleGapNanos,
		closureKind = closureKind,
		qualification = qualification,
		sourceQualityFlags = sourceQualityFlags,
		sourceQualityConfidence = sourceQualityConfidence,
	)

	private fun unavailable(
		segment: SessionSegment,
		failure: HistoricalCaptureFailure,
		reason: PressureHistoryReason,
	) = unavailable(
		segment,
		HistoricalCaptureAuthority.Unverifiable(failure),
		reason,
	)

	private fun unavailable(
		segment: SessionSegment,
		captureAuthority: HistoricalCaptureAuthority,
		reason: PressureHistoryReason,
	) = PressurePhysicalHistory(
		segment = segment,
		captureAuthority = captureAuthority,
		windows = emptyList(),
		availability = PressureHistoryAvailability.UNAVAILABLE,
		evidence = PressureHistoryEvidence.NO_OBSERVATION,
		materialization = PressureHistoryMaterialization.FAILED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		reasons = setOf(reason),
	)

	private fun failed(
		segment: SessionSegment,
		captureAuthority: HistoricalCaptureAuthority,
		reason: PressureHistoryReason,
	) = PressurePhysicalHistory(
		segment = segment,
		captureAuthority = captureAuthority,
		windows = emptyList(),
		availability = PressureHistoryAvailability.AVAILABLE,
		evidence = PressureHistoryEvidence.NO_OBSERVATION,
		materialization = PressureHistoryMaterialization.FAILED,
		coverage = PressureHistoryCoverage.UNKNOWN,
		reasons = setOf(reason),
	)

	private fun disabled(
		segment: SessionSegment,
		captureAuthority: HistoricalCaptureAuthority,
	) = PressurePhysicalHistory(
		segment = segment,
		captureAuthority = captureAuthority,
		windows = emptyList(),
		availability = PressureHistoryAvailability.DISABLED,
		evidence = PressureHistoryEvidence.NO_OBSERVATION,
		materialization = PressureHistoryMaterialization.NOT_APPLICABLE,
		coverage = PressureHistoryCoverage.NONE,
		reasons = setOf(PressureHistoryReason.SOURCE_NOT_CAPTURED),
	)

	private fun deleted(
		segment: SessionSegment,
		captureAuthority: HistoricalCaptureAuthority,
	) = PressurePhysicalHistory(
		segment = segment,
		captureAuthority = captureAuthority,
		windows = emptyList(),
		availability = PressureHistoryAvailability.DELETED,
		evidence = PressureHistoryEvidence.NO_OBSERVATION,
		materialization = PressureHistoryMaterialization.READY,
		coverage = PressureHistoryCoverage.NONE,
		reasons = setOf(PressureHistoryReason.DELETED_FACTS),
	)

	private fun providerUnavailable(
		segment: SessionSegment,
		captureAuthority: HistoricalCaptureAuthority,
	) = PressurePhysicalHistory(
		segment = segment,
		captureAuthority = captureAuthority,
		windows = emptyList(),
		availability = PressureHistoryAvailability.UNAVAILABLE,
		evidence = PressureHistoryEvidence.NO_OBSERVATION,
		materialization = PressureHistoryMaterialization.NOT_APPLICABLE,
		coverage = PressureHistoryCoverage.NONE,
		reasons = setOf(PressureHistoryReason.PROVIDER_UNAVAILABLE),
	)

	private fun hasValidZone(zoneId: String): Boolean = try {
		ZoneId.of(zoneId)
		true
	} catch (_: DateTimeException) {
		false
	}

	private fun PressureHistoryBatchSnapshot.withLogicalManifestMembershipValidation(
		segments: List<SessionSegment>,
	): PressureHistoryBatchSnapshot {
		val failures = logicalMembershipFailures.toMutableMap()
		segments.groupBy(SessionSegment::logicalTrackingId).forEach { (logicalTrackingId, members) ->
			if (logicalTrackingId.isNullOrBlank() || logicalTrackingId in failures) return@forEach
			val serviceRunIds = members.mapNotNull(SessionSegment::serviceRunId)
			if (serviceRunIds.size != members.size || serviceRunIds.distinct().size != members.size) {
				failures[logicalTrackingId] = PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE
				return@forEach
			}
			val completeRunIds = serviceRuns.values.asSequence()
				.filter { run -> run.logicalTrackingId == logicalTrackingId }
				.map(SourceServiceRunEntity::serviceRunId)
				.toSet()
			if (completeRunIds != serviceRunIds.toSet()) {
				failures[logicalTrackingId] = PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE
				return@forEach
			}
			val revisionsByRun = serviceRunIds.map { serviceRunId ->
				manifestsByRun[serviceRunId].orEmpty()
					.map(SessionManifestVersionEntity::manifestRevision)
			}
			if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(revisionsByRun)) {
				failures[logicalTrackingId] =
					PressureHistoryReason.LOGICAL_MANIFEST_REVISION_UNION_INVALID
				return@forEach
			}
			val manifestFailure = serviceRunIds.firstNotNullOfOrNull { serviceRunId ->
				val run = serviceRuns[serviceRunId]
				val manifests = manifestsByRun[serviceRunId].orEmpty()
				when {
					run == null || run.logicalTrackingId != logicalTrackingId ->
						PressureHistoryReason.SEGMENT_MEMBERSHIP_INCOMPLETE
					manifests.any { manifest ->
						manifest.logicalTrackingId != logicalTrackingId ||
							manifest.serviceRunId != serviceRunId
					} -> PressureHistoryReason.MANIFEST_MEMBERSHIP_MISMATCH
					manifests.any { manifest ->
						val sources = sourcesByManifest[
							PressureManifestKey(logicalTrackingId, manifest.manifestRevision)
						].orEmpty()
						!SessionManifestIntegrity.verify(manifest, sources) ||
							!hasValidZone(manifest.zoneId)
					} -> PressureHistoryReason.MANIFEST_INTEGRITY_FAILED
					!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests) ->
						PressureHistoryReason.MANIFEST_INTEGRITY_FAILED
					else -> null
				}
			}
			if (manifestFailure != null) {
				failures[logicalTrackingId] = manifestFailure
			}
		}
		return if (failures == logicalMembershipFailures) this else copy(
			logicalMembershipFailures = failures,
		)
	}

	private data class PressureCorrectionAuthority(
		val manifestRevision: Long,
		val sourcePolicyRevision: Long,
		val captureConsentEpoch: Long,
		val clockDomainId: String,
		val wallTimeUncertaintyMs: Long,
		val manifestClockDomainId: String,
		val zoneId: String,
	)

	private enum class PressureRetentionTruncationState { ABSENT, VALID, INVALID }

	private data class PressureWriterBinding(
		val owner: String,
		val ownerGeneration: Long,
		val projectionId: String,
		val projectionVersion: Int,
		val bindingGeneration: Long,
	) {
		val isPressureFactWriter: Boolean
			get() = owner == SourceDestinationOwnerEntity.OWNER_PRESSURE_SESSION_FACTS &&
				ownerGeneration == SourceDestinationOwnerEntity.FIRST_CANDIDATE_GENERATION &&
				projectionId == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_ID &&
				projectionVersion == SourceDestinationOwnerEntity.PRESSURE_FACT_PROJECTION_VERSION &&
				bindingGeneration == SourceDestinationOwnerEntity.PRESSURE_FACT_BINDING_GENERATION
	}

	private companion object {
		const val FIRST_SEMANTIC_REVISION = 1L
		const val MIN_CAPTURE_QOS = 1
		const val MAX_CAPTURE_QOS = 3
		const val COMPLETE_STOP_STATUS = "COMPLETE"
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
		const val UNOBSERVABLE_PROVIDER_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"
		const val UNAVAILABLE_PRESSURE_INSTANCE = "unavailable-pressure"
		const val MANUAL_SESSION_CAPTURE_MASK = 1L shl 0
		const val AUTOMATIC_SESSION_CAPTURE_MASK = 1L shl 1
		val TERMINAL_SERVICE_RUN_STATES = setOf("FINALIZED", "FAILED", "CLOSED")
		val PROVIDER_COVERAGE_VALUES = setOf(
			COMPLETE_PROVIDER_COVERAGE,
			UNOBSERVABLE_PROVIDER_COVERAGE,
		)
		val STOP_STATUS_VALUES = setOf(
			COMPLETE_STOP_STATUS,
			"TIMED_OUT",
			"PERMISSION_LOST",
			"PROVIDER_FAILED",
			"PROCESS_RESTARTED",
		)
		val UNAVAILABLE_PRESSURE_STOP_STATUSES = setOf(COMPLETE_STOP_STATUS, "PROVIDER_FAILED")
		val VALID_PRODUCT_STAGES = setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
	}
}

private const val PRESSURE_HISTORY_SEGMENT_BATCH_CAP = 64
private const val PRESSURE_DISCOVERY_CANDIDATE_PAGE_SIZE = 32
private const val PRESSURE_DISCOVERY_CANDIDATE_BUDGET = 256
private const val PRESSURE_DISCOVERY_MEMBER_BUDGET = 256
