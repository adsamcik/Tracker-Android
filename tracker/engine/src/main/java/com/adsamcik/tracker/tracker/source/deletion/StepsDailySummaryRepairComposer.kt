package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals
import com.adsamcik.tracker.shared.base.database.dao.ScopedStepFactState
import com.adsamcik.tracker.shared.base.database.dao.TrackingHistoryReadDao
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.model.SourceKind
import java.math.BigInteger
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Bounded, source-aware composer for affected daily summaries and numeric Steps reads.
 *
 * It deliberately does not trust `sample_count` or the candidate presentation `steps` column.
 * A surviving v28 row contributes only after exact reverse binding, immutable manifest
 * attribution, durable logical identity, and settled candidate Steps facts all agree. Every
 * existing summary is recomposed under the exact ZoneId captured by its production materializer.
 * Anything that cannot be proven from those facts and row authority fails closed before deletion.
 */
@Suppress("LargeClass", "TooManyFunctions")
internal class StepsDailySummaryRepairComposer(
	private val database: AppDatabase,
) {
	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	suspend fun compose(
		epochDays: List<Long>,
		excludedSegmentId: Long,
	): StepsDayRepairPreflight {
		val sortedDays = epochDays.distinct().sorted()
		if (sortedDays.isEmpty()) {
			return unverifiable()
		}
		val requested = sortedDays.toSet()
		val summaries = database.dailySummaryDao()
			.getBetween(sortedDays.first(), sortedDays.last())
			.filter { summary -> summary.dateEpochDay in requested }
		return compose(epochDays, excludedSegmentId, summaries)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	suspend fun compose(
		epochDays: List<Long>,
		excludedSegmentId: Long,
		dailySummaries: List<DailySummaryEntity>,
	): StepsDayRepairPreflight {
		val sortedDays = epochDays.distinct().sorted()
		val sortedDaySet = sortedDays.toSet()
		if (sortedDays.isEmpty() || dailySummaries.any { it.dateEpochDay !in sortedDaySet } ||
			dailySummaries.map(DailySummaryEntity::dateEpochDay).distinct().size != dailySummaries.size
		) {
			return unverifiable()
		}
		val zoneByDay = linkedMapOf<Long, ZoneId>()
		for (summary in dailySummaries.sortedBy(DailySummaryEntity::dateEpochDay)) {
			val zoneId = try {
				ZoneId.of(summary.calendarZoneId ?: return unverifiable())
			} catch (_: DateTimeException) {
				return unverifiable()
			}
			zoneByDay[summary.dateEpochDay] = zoneId
		}
		val queryBounds = allZoneQueryBounds(sortedDays) ?: return unverifiable()
		return composeQualifiedDays(
			excludedSegmentId = excludedSegmentId,
			zoneByDay = zoneByDay,
			queryBounds = queryBounds,
			discoverSourceRuns = true,
			blockOnActiveUnboundNonSteps = true,
		)
	}

	/** Composes one production materialization key under its already-selected calendar authority. */
	suspend fun composeForMaterialization(
		epochDay: Long,
		zoneId: ZoneId,
	): StepsDayRepairPreflight {
		val fromMs = startOfDayMs(epochDay, zoneId) ?: return unverifiable()
		val toMs = startOfDayMs(epochDay + 1L, zoneId) ?: return unverifiable()
		return composeQualifiedDays(
			excludedSegmentId = null,
			zoneByDay = linkedMapOf(epochDay to zoneId),
			queryBounds = QueryBounds(fromMs = fromMs, toMs = toMs),
			discoverSourceRuns = true,
			blockOnActiveUnboundNonSteps = true,
		)
	}

	/**
	 * Composes one bounded numeric-read generation with an explicit authority for every day.
	 *
	 * The caller owns selection of persisted versus fallback calendar zones. This method performs
	 * the shared Room reads once for the whole generation and never mutates a derived summary.
	 */
	suspend fun composeForNumericRead(
		zoneByDay: Map<Long, ZoneId>,
	): StepsDayRepairPreflight {
		val orderedZones = zoneByDay.toSortedMap()
		val queryBounds = stepsNumericReadQueryBounds(orderedZones) ?: return unverifiable()
		return composeQualifiedDays(
			excludedSegmentId = null,
			zoneByDay = orderedZones,
			queryBounds = QueryBounds(queryBounds.fromMs, queryBounds.toMs),
			requireNonOverlappingLogicalSessions = true,
			discoverSourceRuns = true,
		)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun composeQualifiedDays(
		excludedSegmentId: Long?,
		zoneByDay: Map<Long, ZoneId>,
		queryBounds: QueryBounds,
		requireNonOverlappingLogicalSessions: Boolean = false,
		discoverSourceRuns: Boolean = false,
		blockOnActiveUnboundNonSteps: Boolean = false,
	): StepsDayRepairPreflight {
		val readDao = database.trackingHistoryReadDao()
		val presentationSegments = sourceRepairSegments(
			queryBounds = queryBounds,
			excludedSegmentId = excludedSegmentId,
		) ?: return unverifiable()
		val discoveredSourceRuns = if (discoverSourceRuns) {
			(serviceRunCandidates(queryBounds) ?: return unverifiable()).filterNot { run ->
				excludedSegmentId != null && run.sessionSegmentId == excludedSegmentId
			}
		} else {
			emptyList()
		}
		val discoveredSegmentIds = discoveredSourceRuns.mapNotNull(SourceServiceRunEntity::sessionSegmentId)
			.distinct()
		val discoveredSourceSegments = discoveredSegmentIds.chunked(QUERY_ID_BATCH_SIZE).flatMap { ids ->
			readDao.segments(ids)
		}
		val discoveredSourceSegmentIds = discoveredSourceSegments.mapTo(hashSetOf(), SessionSegment::id)
		val missingSegmentRuns = discoveredSourceRuns.filter { run ->
			run.sessionSegmentId?.let { segmentId -> segmentId !in discoveredSourceSegmentIds } == true
		}
		if (!allMissingSegmentsAreExactlyFenced(missingSegmentRuns, readDao)) {
			return unverifiable()
		}
		val missingServiceRunIds = missingSegmentRuns.mapTo(hashSetOf(), SourceServiceRunEntity::serviceRunId)
		val sourceRunCandidates = discoveredSourceRuns.filterNot { run ->
			run.serviceRunId in missingServiceRunIds
		}
		val unboundRuns = sourceRunCandidates.filter { run -> run.sessionSegmentId == null }
		val referencedSegmentIds = sourceRunCandidates.mapNotNull(SourceServiceRunEntity::sessionSegmentId)
			.toSet()
		val sourceOwnedSegments = discoveredSourceSegments.filter { segment ->
			segment.id in referencedSegmentIds
		}
		if (sourceOwnedSegments.map(SessionSegment::id).toSet() != referencedSegmentIds) {
			return unverifiable()
		}
		val segments = (presentationSegments + sourceOwnedSegments)
			.distinctBy(SessionSegment::id)
			.sortedWith(compareBy<SessionSegment>(SessionSegment::startTimeMs).thenBy(SessionSegment::id))
		if (segments.isEmpty() && unboundRuns.isEmpty()) {
			return StepsDayRepairPreflight.Ready(
				zoneByDay.map { (epochDay, zoneId) ->
					StepsDayRepairPlan(
						epochDay = epochDay,
						zoneId = zoneId,
						totals = null,
						numericSteps = StepsDayNumericComposition.NotCaptured,
					)
				},
			)
		}
		if (hasForbiddenPhysicalOverlap(segments, requireNonOverlappingLogicalSessions)) {
			return unverifiable()
		}

		val snapshot = loadSnapshot(segments, unboundRuns) ?: return unverifiable()
		val contributions = mutableListOf<SegmentContribution>()
		val unboundNonStepsCaptures = mutableListOf<UnboundNonStepsCapture>()
		val materializingAuthorities = mutableListOf<LogicalContributionAuthority>()
		var hasMaterializingSegment = false
		for (segment in segments) {
			when (val qualified = qualify(segment, snapshot)) {
				is SegmentQualification.Ready -> contributions += qualified.contribution
				is SegmentQualification.Materializing -> {
					hasMaterializingSegment = true
					materializingAuthorities += qualified.authority
				}
				SegmentQualification.Unverifiable -> return unverifiable()
			}
		}
		for (run in unboundRuns) {
			when (val qualified = qualifyUnboundRun(run, queryBounds, snapshot)) {
				is UnboundRunQualification.NonSteps -> {
					unboundNonStepsCaptures += qualified.capture
					materializingAuthorities += qualified.authority
					if (blockOnActiveUnboundNonSteps) {
						hasMaterializingSegment = true
					}
				}
				is UnboundRunQualification.Materializing -> {
					hasMaterializingSegment = true
					materializingAuthorities += qualified.authority
				}
				UnboundRunQualification.Unverifiable -> return unverifiable()
			}
		}
		if (hasForbiddenCaptureOverlap(
			contributions = contributions,
			unboundNonStepsCaptures = unboundNonStepsCaptures,
			rejectCrossLogicalOverlap = requireNonOverlappingLogicalSessions,
		)) {
			return unverifiable()
		}
		val groups = contributions.groupBy(SegmentContribution::logicalTrackingId)
		if (groups.values.any(::hasIncompatibleLogicalGroup)) {
			return unverifiable()
		}
		val logicalAuthorities = contributions.map { contribution ->
			LogicalContributionAuthority(
				logicalTrackingId = contribution.logicalTrackingId,
				logicalStartedAtMs = contribution.logicalStartedAtMs,
				zoneId = contribution.zoneId,
			)
		} + materializingAuthorities
		if (logicalAuthorities.groupBy(LogicalContributionAuthority::logicalTrackingId).values.any { group ->
				group.map(LogicalContributionAuthority::logicalStartedAtMs).distinct().size != 1 ||
					group.map(LogicalContributionAuthority::zoneId).distinct().size != 1
			}
		) {
			return unverifiable()
		}
		if (hasMaterializingSegment) {
			return StepsDayRepairPreflight.Materializing
		}
		val plans = mutableListOf<StepsDayRepairPlan>()
		for ((epochDay, zoneId) in zoneByDay) {
			when (val composition = composeDay(
				epochDay = epochDay,
				zoneId = zoneId,
				groups = groups.values,
				unboundNonStepsCaptures = unboundNonStepsCaptures,
			)) {
				is DayComposition.Ready -> plans += StepsDayRepairPlan(
					epochDay = epochDay,
					zoneId = zoneId,
					totals = composition.totals,
					numericSteps = composition.numericSteps,
				)
				DayComposition.Unverifiable -> return unverifiable()
			}
		}
		return StepsDayRepairPreflight.Ready(plans)
	}

	private suspend fun sourceRepairSegments(
		queryBounds: QueryBounds,
		excludedSegmentId: Long?,
	): List<SessionSegment>? {
		val rows = mutableListOf<SessionSegment>()
		var afterStartTimeMs: Long? = null
		var afterSegmentId: Long? = null
		var pageSize: Int
		do {
			val page = database.sessionSegmentDao().sourceRepairSegmentPage(
				fromMs = queryBounds.fromMs,
				toMs = queryBounds.toMs,
				excludedSegmentId = excludedSegmentId,
				limit = READ_PAGE_SIZE,
				afterStartTimeMs = afterStartTimeMs,
				afterSegmentId = afterSegmentId,
			)
			val orderedPage = rows.lastOrNull()?.let { previous -> listOf(previous) + page } ?: page
			if (page.any { segment -> segment.id <= 0L } || orderedPage.zipWithNext().any { (left, right) ->
				right.startTimeMs < left.startTimeMs ||
					right.startTimeMs == left.startTimeMs && right.id <= left.id
			}) {
				return null
			}
			rows += page
			afterStartTimeMs = page.lastOrNull()?.startTimeMs
			afterSegmentId = page.lastOrNull()?.id
			pageSize = page.size
		} while (pageSize == READ_PAGE_SIZE)
		return rows
	}

	private suspend fun serviceRunCandidates(queryBounds: QueryBounds): List<SourceServiceRunEntity>? {
		val rows = mutableListOf<SourceServiceRunEntity>()
		var afterStartedAtMs: Long? = null
		var afterServiceRunId: String? = null
		var pageSize: Int
		do {
			val page = database.trackingHistoryReadDao().serviceRunCandidatePage(
				fromMs = queryBounds.fromMs,
				toMs = queryBounds.toMs,
				limit = READ_PAGE_SIZE,
				afterStartedAtMs = afterStartedAtMs,
				afterServiceRunId = afterServiceRunId,
			)
			val orderedPage = rows.lastOrNull()?.let { previous -> listOf(previous) + page } ?: page
			if (page.any { run -> run.serviceRunId.isBlank() } ||
				orderedPage.zipWithNext().any { (left, right) ->
					right.startedAtMs < left.startedAtMs ||
						right.startedAtMs == left.startedAtMs && right.serviceRunId <= left.serviceRunId
				}
			) {
				return null
			}
			rows += page
			afterStartedAtMs = page.lastOrNull()?.startedAtMs
			afterServiceRunId = page.lastOrNull()?.serviceRunId
			pageSize = page.size
		} while (pageSize == READ_PAGE_SIZE)
		return rows
	}

	private suspend fun allMissingSegmentsAreExactlyFenced(
		runs: List<SourceServiceRunEntity>,
		readDao: TrackingHistoryReadDao,
	): Boolean {
		if (runs.isEmpty()) {
			return true
		}
		if (runs.any { run -> run.logicalTrackingId.isBlank() || run.serviceRunId.isBlank() }) {
			return false
		}
		val expectedDigests = runs.mapTo(hashSetOf()) { run ->
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				logicalTrackingId = run.logicalTrackingId,
				serviceRunId = run.serviceRunId,
			)
		}
		val actualDigests = expectedDigests.chunked(QUERY_ID_BATCH_SIZE).flatMapTo(hashSetOf()) { digests ->
			readDao.deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = digests,
			).map(SourceDeletionFenceEntity::scopeIdentityDigest)
		}
		return actualDigests == expectedDigests
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun loadSnapshot(
		segments: List<SessionSegment>,
		unboundRuns: List<SourceServiceRunEntity>,
	): RepairSnapshot? {
		val logicalTrackingIds = (segments.mapNotNull(SessionSegment::logicalTrackingId) +
			unboundRuns.map(SourceServiceRunEntity::logicalTrackingId))
			.filter(String::isNotBlank)
			.distinct()
		val serviceRunIds = (segments.mapNotNull(SessionSegment::serviceRunId) +
			unboundRuns.map(SourceServiceRunEntity::serviceRunId))
			.filter(String::isNotBlank)
			.distinct()
		val readDao = database.trackingHistoryReadDao()
		val logicalSessions = logicalTrackingIds.chunked(QUERY_ID_BATCH_SIZE).flatMap { ids ->
			database.sourceSessionDao().sessions(ids)
		}
		val serviceRuns = serviceRunIds.chunked(QUERY_ID_BATCH_SIZE).flatMap { ids ->
			readDao.serviceRuns(ids)
		}
		if (logicalSessions.map(LogicalTrackingSessionEntity::logicalTrackingId).toSet() !=
			logicalTrackingIds.toSet() ||
			serviceRuns.map(SourceServiceRunEntity::serviceRunId).toSet() != serviceRunIds.toSet()
		) {
			return null
		}
		val manifests = mutableListOf<SessionManifestVersionEntity>()
		val sources = mutableListOf<SessionManifestSourceEntity>()
		val completeness = mutableListOf<SourceSessionCompletenessEntity>()
		val lanes = mutableListOf<SourceProductProjectionLaneEntity>()
		for (ids in serviceRunIds.chunked(QUERY_ID_BATCH_SIZE)) {
			val manifestBatch = readDao.manifests(ids, limit = MAX_MANIFESTS_PER_QUERY_BATCH + 1)
			val sourceBatch = readDao.manifestSources(ids, limit = MAX_SOURCES_PER_QUERY_BATCH + 1)
			val completenessBatch = readDao.completeness(ids, limit = MAX_COMPLETENESS_PER_QUERY_BATCH + 1)
			if (manifestBatch.size > MAX_MANIFESTS_PER_QUERY_BATCH ||
				sourceBatch.size > MAX_SOURCES_PER_QUERY_BATCH ||
				completenessBatch.size > MAX_COMPLETENESS_PER_QUERY_BATCH
			) {
				return null
			}
			manifests += manifestBatch
			sources += sourceBatch
			completeness += completenessBatch
			lanes += readDao.productLanesForServiceRuns(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				serviceRunIds = ids,
			)
		}
		// Active facts can grow on every callback but can never produce a numeric value. Their durable
		// attribution is checked once the run terminalizes; settled runs are paged without a fact cap.
		val terminalRunIds = serviceRuns.filter { run -> run.completedAtMs != null }
			.map(SourceServiceRunEntity::serviceRunId)
		val factStates = loadFactStatePages(terminalRunIds) ?: return null
		val scopeDigests = serviceRuns.mapNotNull { run ->
			val logicalTrackingId = run.logicalTrackingId.takeIf(String::isNotBlank)
			val serviceRunId = run.serviceRunId.takeIf(String::isNotBlank)
			if (logicalTrackingId == null || serviceRunId == null) {
				null
			} else {
				SourceDeletionFenceEntity.logicalServiceRunIdentity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					logicalTrackingId = logicalTrackingId,
					serviceRunId = serviceRunId,
				)
			}
		}.distinct()
		val deletionFences = scopeDigests.chunked(QUERY_ID_BATCH_SIZE).flatMap { digests ->
			readDao.deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = digests,
			)
		}
		val afterOrdinal = lanes.mapNotNull { lane ->
			lane.activationOrdinal.takeIf { it > 0L }?.minus(1L)
		}.minOrNull()
		val throughOrdinal = completeness.mapNotNull { row -> row.lastAdmissionOrdinal }.maxOrNull()
		val failures = if (
			serviceRunIds.isEmpty() || afterOrdinal == null || throughOrdinal == null ||
			afterOrdinal >= throughOrdinal
		) {
			emptyList()
		} else {
			val rows = mutableListOf<SourceProjectionFailureEntity>()
			for (ids in serviceRunIds.chunked(QUERY_ID_BATCH_SIZE)) {
				val batch = readDao.terminalFailuresForServiceRuns(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					serviceRunIds = ids,
					afterOrdinal = afterOrdinal,
					throughOrdinal = throughOrdinal,
					limit = MAX_TERMINAL_FAILURES_PER_QUERY_BATCH + 1,
				)
				if (batch.size > MAX_TERMINAL_FAILURES_PER_QUERY_BATCH) {
					return null
				}
				rows += batch
			}
			rows.distinct()
		}
		val serviceRunsById = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId)
		if (unboundRuns.any { candidate -> serviceRunsById[candidate.serviceRunId] != candidate }) {
			return null
		}
		return RepairSnapshot(
			logicalSessions = logicalSessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			serviceRuns = serviceRunsById,
			manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByManifest = sources.groupBy { source ->
				ManifestKey(source.logicalTrackingId, source.manifestRevision)
			},
			completenessByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId),
			factStatesByRun = factStates.groupBy(ScopedStepFactState::serviceRunId),
			lanes = lanes.distinct().associateBy { lane ->
				LaneKey(lane.bindingGeneration, lane.projectionId, lane.projectionVersion)
			},
			deletionFenceDigests = deletionFences.mapTo(hashSetOf()) { fence ->
				fence.scopeIdentityDigest
			},
			failures = failures,
			evidenceState = database.sourceEvidenceStateDao().get(),
		)
	}

	private suspend fun loadFactStatePages(
		serviceRunIds: List<String>,
	): List<ScopedStepFactState>? {
		val readDao = database.trackingHistoryReadDao()
		val rows = mutableListOf<ScopedStepFactState>()
		for (ids in serviceRunIds.chunked(QUERY_ID_BATCH_SIZE)) {
			var afterServiceRunId: String? = null
			var afterFirstIntervalStartTimeMs: Long? = null
			var afterLogicalFactId: String? = null
			var afterWriterProjectionId: String? = null
			var afterWriterProjectionVersion: Int? = null
			var previous: ScopedStepFactState? = null
			var pageSize: Int
			do {
				val page = readDao.stepFactStatePage(
					serviceRunIds = ids,
					limit = READ_PAGE_SIZE,
					afterServiceRunId = afterServiceRunId,
					afterFirstIntervalStartTimeMs = afterFirstIntervalStartTimeMs,
					afterLogicalFactId = afterLogicalFactId,
					afterWriterProjectionId = afterWriterProjectionId,
					afterWriterProjectionVersion = afterWriterProjectionVersion,
				)
				val orderedPage = previous?.let { listOf(it) + page } ?: page
				if (page.any { row ->
						row.serviceRunId !in ids || row.serviceRunId.isBlank() ||
						row.state.logicalFactId.isBlank()
					} || orderedPage.zipWithNext().any { (left, right) ->
						!isFactStateAfter(right, left)
					}
				) {
					return null
				}
				rows += page
				val last = page.lastOrNull()
				previous = last ?: previous
				afterServiceRunId = last?.serviceRunId
				afterFirstIntervalStartTimeMs = last?.firstIntervalStartTimeMs
				afterLogicalFactId = last?.state?.logicalFactId
				afterWriterProjectionId = last?.state?.writerProjectionId
				afterWriterProjectionVersion = last?.state?.writerProjectionVersion
				pageSize = page.size
			} while (pageSize == READ_PAGE_SIZE)
		}
		return rows
	}

	private fun isFactStateAfter(
		candidate: ScopedStepFactState,
		previous: ScopedStepFactState,
	): Boolean = when {
		candidate.serviceRunId != previous.serviceRunId ->
			candidate.serviceRunId > previous.serviceRunId
		candidate.firstIntervalStartTimeMs != previous.firstIntervalStartTimeMs ->
			candidate.firstIntervalStartTimeMs > previous.firstIntervalStartTimeMs
		candidate.state.logicalFactId != previous.state.logicalFactId ->
			candidate.state.logicalFactId > previous.state.logicalFactId
		candidate.state.writerProjectionId != previous.state.writerProjectionId ->
			candidate.state.writerProjectionId > previous.state.writerProjectionId
		else -> candidate.state.writerProjectionVersion > previous.state.writerProjectionVersion
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun qualify(
		segment: SessionSegment,
		snapshot: RepairSnapshot,
	): SegmentQualification {
		if (segment.endTimeMs <= segment.startTimeMs || !segment.distanceM.isFinite() || segment.distanceM < 0f) {
			return SegmentQualification.Unverifiable
		}
		val logicalTrackingId = segment.logicalTrackingId
		val serviceRunId = segment.serviceRunId
		// Released rows have no immutable calendar or logical-start authority.
		if (logicalTrackingId.isNullOrBlank() || serviceRunId.isNullOrBlank()) {
			return SegmentQualification.Unverifiable
		}
		val logicalSession = snapshot.logicalSessions[logicalTrackingId]
			?: return SegmentQualification.Unverifiable
		val run = snapshot.serviceRuns[serviceRunId] ?: return SegmentQualification.Unverifiable
		if (run.logicalTrackingId != logicalTrackingId || run.sessionSegmentId != segment.id) {
			return SegmentQualification.Unverifiable
		}
		val lifecycle = boundLifecycle(logicalSession, run, segment)
			?: return SegmentQualification.Unverifiable

		val manifests = snapshot.manifestsByRun[serviceRunId].orEmpty()
		val authorities = manifestAuthorities(
			logicalSession = logicalSession,
			run = run,
			manifests = manifests,
			sourcesByManifest = snapshot.sourcesByManifest,
		) ?: return SegmentQualification.Unverifiable
		val zones = authorities.values.map(ManifestAuthority::zoneId).distinct()
		if (zones.size != 1) {
			// A physical segment is not revision-partitioned, so mixed-zone allocation is ambiguous.
			return SegmentQualification.Unverifiable
		}
		val exactBindings = authorities.values.mapNotNull(ManifestAuthority::stepsBinding)
		val writer = if (exactBindings.isEmpty()) {
			null
		} else {
			val writerBindings = exactBindings.map(::writerBinding).distinct()
			if (writerBindings.size != 1) {
				return SegmentQualification.Unverifiable
			}
			writerBindings.single().also { binding ->
				if (binding.owner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS) {
					// No legacy row, including a zero-sample row, is numeric source evidence.
					return SegmentQualification.Unverifiable
				}
			}
		}
		if (writer == null) {
			return nonStepsContribution(
				segment = segment,
				logicalSession = logicalSession,
				run = run,
				authorities = authorities,
				snapshot = snapshot,
				lifecycle = lifecycle,
			)
		}
		return candidateContribution(
			segment = segment,
			logicalSession = logicalSession,
			run = run,
			authorities = authorities,
			writer = writer,
			snapshot = snapshot,
			lifecycle = lifecycle,
		)
	}

	@Suppress("ReturnCount")
	private fun qualifyUnboundRun(
		run: SourceServiceRunEntity,
		queryBounds: QueryBounds,
		snapshot: RepairSnapshot,
	): UnboundRunQualification {
		if (run.sessionSegmentId != null || run.serviceRunId.isBlank() || run.logicalTrackingId.isBlank()) {
			return UnboundRunQualification.Unverifiable
		}
		val logicalSession = snapshot.logicalSessions[run.logicalTrackingId]
			?: return UnboundRunQualification.Unverifiable
		val authorities = manifestAuthorities(
			logicalSession = logicalSession,
			run = run,
			manifests = snapshot.manifestsByRun[run.serviceRunId].orEmpty(),
			sourcesByManifest = snapshot.sourcesByManifest,
		) ?: return UnboundRunQualification.Unverifiable
		val relevantAuthorities = unboundOverlappingAuthorities(run, authorities, queryBounds)
			?: return UnboundRunQualification.Unverifiable
		val relevantSteps = relevantAuthorities.mapNotNull(ManifestAuthority::stepsBinding)
		if (relevantSteps.isEmpty()) {
			return qualifyUnboundNonSteps(
				logicalSession = logicalSession,
				run = run,
				authorities = authorities,
				relevantAuthorities = relevantAuthorities,
				queryBounds = queryBounds,
				snapshot = snapshot,
			)
		}
		return qualifyUnboundSteps(
			logicalSession = logicalSession,
			run = run,
			authorities = authorities,
			relevantAuthorities = relevantAuthorities,
			relevantSteps = relevantSteps,
			snapshot = snapshot,
		)
	}

	@Suppress("ReturnCount")
	private fun qualifyUnboundNonSteps(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		authorities: Map<Long, ManifestAuthority>,
		relevantAuthorities: List<ManifestAuthority>,
		queryBounds: QueryBounds,
		snapshot: RepairSnapshot,
	): UnboundRunQualification {
		val capturedSourceKinds = authorities.values.flatMapTo(hashSetOf()) { authority ->
			authority.capturedSourceKinds
		}
		if (!validUnboundNonStepsEvidence(logicalSession, run, capturedSourceKinds, snapshot)) {
			return UnboundRunQualification.Unverifiable
		}
		val captureStartMs = maxOf(run.startedAtMs, queryBounds.fromMs)
		val captureEndMs = minOf(run.completedAtMs ?: queryBounds.toMs, queryBounds.toMs)
		if (captureEndMs <= captureStartMs) {
			return UnboundRunQualification.Unverifiable
		}
		val zoneId = relevantAuthorities.map(ManifestAuthority::zoneId).distinct().singleOrNull()
			?: return UnboundRunQualification.Unverifiable
		return UnboundRunQualification.NonSteps(
			capture = UnboundNonStepsCapture(
				logicalTrackingId = logicalSession.logicalTrackingId,
				startMs = captureStartMs,
				endMs = captureEndMs,
			),
			authority = LogicalContributionAuthority(
				logicalTrackingId = logicalSession.logicalTrackingId,
				logicalStartedAtMs = logicalSession.startedAtMs,
				zoneId = zoneId,
			),
		)
	}

	@Suppress("ReturnCount")
	private fun validUnboundNonStepsEvidence(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		capturedSourceKinds: Set<Int>,
		snapshot: RepairSnapshot,
	): Boolean {
		if (!validUnboundMaterializingLifecycle(logicalSession, run)) {
			return false
		}
		if (snapshot.factStatesByRun[run.serviceRunId].orEmpty().isNotEmpty()) {
			return false
		}
		return snapshot.completenessByRun[run.serviceRunId].orEmpty().none { row ->
			row.logicalTrackingId != logicalSession.logicalTrackingId ||
				row.serviceRunId != run.serviceRunId || row.sourceKind !in capturedSourceKinds
		}
	}

	@Suppress("ReturnCount")
	private fun qualifyUnboundSteps(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		authorities: Map<Long, ManifestAuthority>,
		relevantAuthorities: List<ManifestAuthority>,
		relevantSteps: List<SessionManifestSourceEntity>,
		snapshot: RepairSnapshot,
	): UnboundRunQualification {
		val writers = relevantSteps.map(::writerBinding).distinct()
		if (writers.size != 1 ||
			writers.single().owner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS
		) {
			return UnboundRunQualification.Unverifiable
		}
		val context = candidateAuthorityContext(
			logicalSession = logicalSession,
			run = run,
			authorities = authorities,
			writer = writers.single(),
			evidenceStartMs = run.startedAtMs,
			snapshot = snapshot,
		) ?: return UnboundRunQualification.Unverifiable
		val zoneId = relevantAuthorities.map(ManifestAuthority::zoneId).distinct().singleOrNull()
			?: return UnboundRunQualification.Unverifiable
		return if (validUnboundMaterializingLifecycle(logicalSession, run) &&
			context.lane.status == SourceProductProjectionLaneEntity.STATUS_ACTIVE
		) {
			UnboundRunQualification.Materializing(
				LogicalContributionAuthority(
					logicalTrackingId = logicalSession.logicalTrackingId,
					logicalStartedAtMs = logicalSession.startedAtMs,
					zoneId = zoneId,
				),
			)
		} else {
			UnboundRunQualification.Unverifiable
		}
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun boundLifecycle(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		segment: SessionSegment,
	): BoundLifecycle? {
		if (!validLogicalLifecycle(logicalSession) || run.logicalTrackingId != logicalSession.logicalTrackingId ||
			run.startedAtMs < 0L || run.startedElapsedNanos < 0L || run.rolloutRevision <= 0L ||
			run.desiredPlanRevision <= 0L || run.bootId.isBlank() || run.leaseGeneration <= 0L ||
			run.runRevision <= 0L || run.preparedManifestRevision <= 0L ||
			run.preparedIntentRevision <= 0L || run.startCommandGeneration <= 0L ||
			run.startDeliveryToken.isNullOrBlank() || segment.endTimeMs <= segment.startTimeMs
		) {
			return null
		}
		val completion = run.completedAtMs
		return when (run.state) {
			SessionLifecycleState.ACTIVE.name -> {
				if (completion != null ||
					run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_PENDING ||
					logicalSession.completedAtMs != null ||
					logicalSession.currentServiceRunId != run.serviceRunId ||
					logicalSession.state !in ACTIVE_RUN_LOGICAL_STATES
				) {
					null
				} else {
					BoundLifecycle.LIVE_MATERIALIZING
				}
			}
			SessionLifecycleState.STOPPING.name -> {
				if (completion != null ||
					run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_PENDING ||
					logicalSession.completedAtMs != null ||
					logicalSession.currentServiceRunId != run.serviceRunId ||
					logicalSession.state !in STOPPING_RUN_LOGICAL_STATES
				) {
					null
				} else {
					BoundLifecycle.LIVE_MATERIALIZING
				}
			}
			SessionLifecycleState.FINALIZED.name,
			SessionLifecycleState.FAILED.name,
			-> {
				if (completion == null || completion < 0L ||
					run.presentationAcknowledgement !in EXACT_PRESENTATION_ACKNOWLEDGEMENTS
				) {
					return null
				}
				val logicalState = logicalSession.state
				val restartGap = logicalState in LIVE_LOGICAL_STATES &&
					logicalSession.currentServiceRunId == null
				if (run.state == SessionLifecycleState.FAILED.name &&
					logicalState !in TERMINAL_LOGICAL_STATES
				) {
					return null
				}
				if (logicalState in TERMINAL_LOGICAL_STATES) {
					if (logicalSession.completedAtMs == null || logicalSession.currentServiceRunId != null ||
						run.state == SessionLifecycleState.FAILED.name &&
						(logicalState != SessionLifecycleState.FAILED.name ||
							logicalSession.completedAtMs != completion)
					) {
						return null
					}
				} else if (logicalState in LIVE_LOGICAL_STATES) {
					if (!restartGap && logicalSession.currentServiceRunId == run.serviceRunId) {
						return null
					}
				} else {
					return null
				}
				when (run.presentationAcknowledgement) {
					SourceServiceRunEntity.PRESENTATION_PENDING ->
						BoundLifecycle.TERMINAL_PRESENTATION_MATERIALIZING
					SourceServiceRunEntity.PRESENTATION_QUIESCED -> {
						if (run.presentationAcknowledgedAtMs == null) {
							null
						} else if (restartGap) {
							BoundLifecycle.RESTART_GAP_MATERIALIZING
						} else {
							BoundLifecycle.SETTLED
						}
					}
					else -> null
				}
			}
			else -> null
		}
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
	private fun validLogicalLifecycle(session: LogicalTrackingSessionEntity): Boolean {
		if (session.logicalTrackingId.isBlank() || session.startedAtMs < 0L ||
			session.startedElapsedNanos < 0L || session.lifecycleRevision <= 0L ||
			session.desiredPlanRevision <= 0L || session.rolloutRevision <= 0L ||
			session.clockDomainId.isBlank() || session.startOrigin.isBlank() ||
			session.sessionMode !in EXACT_SESSION_MODES ||
			(session.cutoffAtMs == null) != (session.cutoffElapsedNanos == null) ||
			session.cutoffAtMs?.let { cutoff -> cutoff < 0L } == true ||
			session.cutoffElapsedNanos?.let { cutoff -> cutoff < 0L } == true ||
			session.currentServiceRunId?.isBlank() == true ||
			session.currentManifestRevision?.let { revision -> revision <= 0L } == true ||
			session.currentIntentRevision?.let { revision -> revision <= 0L } == true ||
			session.lifecycleLeaseGeneration < 0L || session.lifecycleBootId?.isBlank() == true ||
			session.currentServiceRunId != null &&
			(session.currentManifestRevision == null || session.currentIntentRevision == null ||
				session.lifecycleLeaseGeneration <= 0L || session.lifecycleBootId == null)
		) {
			return false
		}
		return when (session.state) {
			SessionLifecycleState.STARTING.name ->
				session.completedAtMs == null && session.cutoffAtMs == null &&
					!session.currentServiceRunId.isNullOrBlank()
			SessionLifecycleState.ACTIVE.name ->
				session.completedAtMs == null && session.cutoffAtMs == null &&
					(session.currentServiceRunId != null || hasRetainedLifecycleAuthority(session))
			SessionLifecycleState.RECONFIGURING.name,
			-> session.completedAtMs == null && session.cutoffAtMs == null &&
				!session.currentServiceRunId.isNullOrBlank()
			SessionLifecycleState.STOPPING.name ->
				session.completedAtMs == null && session.cutoffAtMs != null &&
					!session.currentServiceRunId.isNullOrBlank()
			SessionLifecycleState.FINALIZED.name,
			SessionLifecycleState.FAILED.name,
			-> session.completedAtMs?.let { completed ->
				completed >= 0L && session.currentServiceRunId == null &&
					hasRetainedLifecycleAuthority(session)
			} == true
			else -> false
		}
	}

	private fun hasRetainedLifecycleAuthority(session: LogicalTrackingSessionEntity): Boolean =
		session.currentManifestRevision != null && session.currentIntentRevision != null &&
			session.lifecycleLeaseGeneration > 0L && !session.lifecycleBootId.isNullOrBlank()

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun validUnboundMaterializingLifecycle(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
	): Boolean {
		if (!validLogicalLifecycle(logicalSession) || run.logicalTrackingId != logicalSession.logicalTrackingId ||
			run.sessionSegmentId != null || run.completedAtMs != null ||
			run.presentationAcknowledgement != SourceServiceRunEntity.PRESENTATION_PENDING ||
			run.startedAtMs < 0L || run.startedElapsedNanos < 0L || run.rolloutRevision <= 0L ||
			run.desiredPlanRevision <= 0L || run.bootId.isBlank() || run.leaseGeneration <= 0L ||
			run.runRevision <= 0L || run.preparedManifestRevision <= 0L ||
			run.preparedIntentRevision <= 0L || run.startCommandGeneration <= 0L ||
			run.startDeliveryToken.isNullOrBlank() ||
			logicalSession.currentServiceRunId != run.serviceRunId
		) {
			return false
		}
		return when (run.state) {
			SessionLifecycleState.STARTING.name ->
				logicalSession.state == SessionLifecycleState.STARTING.name
			SessionLifecycleState.ACTIVE.name ->
				logicalSession.state == SessionLifecycleState.ACTIVE.name ||
					logicalSession.state == SessionLifecycleState.RECONFIGURING.name
			else -> false
		}
	}

	@Suppress("CyclomaticComplexMethod")
	private fun unboundOverlappingAuthorities(
		run: SourceServiceRunEntity,
		authorities: Map<Long, ManifestAuthority>,
		queryBounds: QueryBounds,
	): List<ManifestAuthority>? {
		val ordered = authorities.values.toList()
		val runEndMs = run.completedAtMs ?: Long.MAX_VALUE
		if (ordered.isEmpty() || runEndMs <= run.startedAtMs) {
			return null
		}
		val overlapping = mutableListOf<ManifestAuthority>()
		for (index in ordered.indices) {
			val authority = ordered[index]
			val startMs = if (index == 0) {
				run.startedAtMs
			} else {
				authority.manifest.effectiveWallTimeMs
			}
			val endMs = ordered.getOrNull(index + 1)?.manifest?.effectiveWallTimeMs ?: runEndMs
			if (startMs < run.startedAtMs || endMs > runEndMs || endMs <= startMs) {
				return null
			}
			if (startMs < queryBounds.toMs && endMs > queryBounds.fromMs) {
				overlapping += authority
			}
		}
		return overlapping.takeIf { it.isNotEmpty() }
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun candidateContribution(
		segment: SessionSegment,
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		authorities: Map<Long, ManifestAuthority>,
		writer: WriterBinding,
		snapshot: RepairSnapshot,
		lifecycle: BoundLifecycle,
	): SegmentQualification {
		val logicalTrackingId = logicalSession.logicalTrackingId
		val serviceRunId = run.serviceRunId
		val context = candidateAuthorityContext(
			logicalSession = logicalSession,
			run = run,
			authorities = authorities,
			writer = writer,
			evidenceStartMs = run.startedAtMs,
			snapshot = snapshot,
		) ?: return SegmentQualification.Unverifiable
		if (lifecycle == BoundLifecycle.LIVE_MATERIALIZING) {
			return if (context.lane.status == SourceProductProjectionLaneEntity.STATUS_ACTIVE) {
				materializing(logicalSession, authorities)
			} else {
				SegmentQualification.Unverifiable
			}
		}
		val captureSlices = manifestCaptureSlices(authorities, run, segment)
			?: return SegmentQualification.Unverifiable
		val completeness = context.completeness.filter { row ->
			row.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS
		}
		if (completeness.isEmpty()) {
			return SegmentQualification.Unverifiable
		}
		if (completeness.any { row ->
			!row.appDrainComplete || row.sourceInstanceId.isBlank() ||
				row.registrationGeneration <= 0L ||
				row.lastAdmissionOrdinal == null ||
				row.lastSourceSequence?.let { sequence -> sequence < 0L } != false ||
				row.stopStatus != COMPLETE_STOP_STATUS ||
				row.providerCoverage != COMPLETE_PROVIDER_COVERAGE ||
				row.unresolvedSequenceStart != null || row.unresolvedSequenceEnd != null
		}) {
			return SegmentQualification.Unverifiable
		}
		val targetOrdinal = completeness.mapNotNull { row -> row.lastAdmissionOrdinal }.maxOrNull()
			?: return SegmentQualification.Unverifiable
		val lane = context.lane
		if (targetOrdinal < lane.activationOrdinal) {
			return SegmentQualification.Unverifiable
		}
		if (snapshot.failures.any { failure ->
			failure.projectionId == context.projectionId &&
				failure.projectionVersion == context.projectionVersion &&
				failure.admissionOrdinal in lane.activationOrdinal..targetOrdinal
		}) {
			return SegmentQualification.Unverifiable
		}
		if (lane.captureAdmissionCutoffOrdinal?.let { cutoff -> targetOrdinal > cutoff } == true) {
			return SegmentQualification.Unverifiable
		}
		if (lane.contiguousAdmissionOrdinal < targetOrdinal) {
			return if (lane.status == SourceProductProjectionLaneEntity.STATUS_ACTIVE) {
				materializing(logicalSession, authorities)
			} else {
				SegmentQualification.Unverifiable
			}
		}

		val scopedStates = context.scopedStates
		if (scopedStates.isEmpty()) {
			return SegmentQualification.Unverifiable
		}
		val covered = mutableListOf<StepFactRevisionEntity>()
		for (scoped in scopedStates) {
			val state = scoped.state
			val admissionOrdinal = state.sourceAdmissionOrdinal
			if (admissionOrdinal == null || admissionOrdinal > targetOrdinal) {
				return SegmentQualification.Unverifiable
			}
			if (state.coverageKind == StepFactRevisionEntity.COVERAGE_COVERED) {
				covered += state
			}
		}
		if (covered.isEmpty()) {
			return SegmentQualification.Unverifiable
		}
		val coveredByAdmission = covered.groupBy(StepFactRevisionEntity::sourceAdmissionOrdinal)
		val terminalOrdinals = completeness.mapNotNull(
			SourceSessionCompletenessEntity::lastAdmissionOrdinal,
		)
		if (terminalOrdinals.distinct().size != completeness.size || completeness.any { row ->
			coveredByAdmission[row.lastAdmissionOrdinal].orEmpty().size != 1
		}) {
			return SegmentQualification.Unverifiable
		}
		val coveredManifestRevisions = covered.mapTo(hashSetOf(), StepFactRevisionEntity::manifestRevision)
		val stepsManifestRevisions = authorities.values.mapNotNullTo(hashSetOf()) { authority ->
			authority.manifest.manifestRevision.takeIf { authority.stepsBinding != null }
		}
		if (!coveredManifestRevisions.containsAll(stepsManifestRevisions)) {
			return SegmentQualification.Unverifiable
		}
		val contributions = mutableListOf<StepContribution>()
		for (fact in covered) {
			val startMs = fact.intervalStartTimeMs ?: return SegmentQualification.Unverifiable
			val endMs = fact.intervalEndTimeMs ?: return SegmentQualification.Unverifiable
			val count = fact.effectiveStepCount ?: return SegmentQualification.Unverifiable
			val uncertaintyMs = fact.wallTimeUncertaintyMs
				?: return SegmentQualification.Unverifiable
			val captureSlice = captureSlices.singleOrNull { slice ->
				slice.manifestRevision == fact.manifestRevision
			} ?: return SegmentQualification.Unverifiable
			if (!captureSlice.capturesSteps || startMs < captureSlice.startMs ||
				endMs > captureSlice.endMs || endMs <= startMs || count < 0L
			) {
				return SegmentQualification.Unverifiable
			}
			contributions += StepContribution(startMs, endMs, count, uncertaintyMs)
		}
		val contribution = SegmentContribution(
				logicalTrackingId = logicalTrackingId,
				logicalStartedAtMs = logicalSession.startedAtMs,
				zoneId = authorities.values.first().zoneId,
				segment = segment,
				captureSlices = captureSlices,
				stepContributions = contributions,
			)
		return if (lifecycle == BoundLifecycle.SETTLED) {
			SegmentQualification.Ready(contribution)
		} else {
			materializing(logicalSession, authorities)
		}
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun candidateAuthorityContext(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		authorities: Map<Long, ManifestAuthority>,
		writer: WriterBinding,
		evidenceStartMs: Long,
		snapshot: RepairSnapshot,
	): CandidateAuthorityContext? {
		val projectionId = writer.projectionId ?: return null
		val projectionVersion = writer.projectionVersion ?: return null
		val bindingGeneration = writer.bindingGeneration ?: return null
		if (writer.owner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS ||
			writer.ownerGeneration <= 0L || bindingGeneration <= 0L
		) {
			return null
		}
		val evidenceState = snapshot.evidenceState ?: return null
		if (evidenceState.retainedFromMs?.let { retained -> evidenceStartMs < retained } == true) {
			return null
		}
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalSession.logicalTrackingId,
			serviceRunId = run.serviceRunId,
		)
		if (digest in snapshot.deletionFenceDigests) {
			return null
		}
		val completeness = snapshot.completenessByRun[run.serviceRunId].orEmpty()
		val capturedSourceKinds = authorities.values.flatMapTo(hashSetOf()) { authority ->
			authority.capturedSourceKinds
		}
		if (completeness.any { row ->
				row.logicalTrackingId != logicalSession.logicalTrackingId ||
					row.serviceRunId != run.serviceRunId || row.sourceKind !in capturedSourceKinds
			}) {
			return null
		}
		val lane = snapshot.lanes[LaneKey(bindingGeneration, projectionId, projectionVersion)]
			?: return null
		val requiredCaptureModeMask = historicalCaptureModeMask(authorities) ?: return null
		val stepsAuthorities = authorities.values.filter { authority -> authority.stepsBinding != null }
		if (stepsAuthorities.isEmpty() || !validHistoricalLane(lane) ||
			lane.captureModeMask and requiredCaptureModeMask == 0L ||
			stepsAuthorities.any { authority ->
				lane.activatedRolloutRevision > authority.manifest.rolloutRevision
			}
		) {
			return null
		}
		val scopedStates = snapshot.factStatesByRun[run.serviceRunId].orEmpty()
		for (scoped in scopedStates) {
			val authority = authorities[scoped.manifestRevision] ?: return null
			val nextAuthority = authorities.values.firstOrNull { candidate ->
				candidate.manifest.manifestRevision > authority.manifest.manifestRevision
			}
			val binding = authority.stepsBinding ?: return null
			val state = scoped.state
			val admissionOrdinal = state.sourceAdmissionOrdinal
			if (scoped.logicalTrackingId != logicalSession.logicalTrackingId ||
				scoped.writerBindingGeneration != bindingGeneration ||
				state.writerProjectionId != projectionId ||
				state.writerProjectionVersion != projectionVersion ||
				state.writerBindingGeneration != bindingGeneration ||
				state.operation != StepFactRevisionEntity.OPERATION_UPSERT ||
				state.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
				admissionOrdinal == null || admissionOrdinal < lane.activationOrdinal ||
				lane.captureAdmissionCutoffOrdinal?.let { cutoff -> admissionOrdinal > cutoff } == true ||
				state.logicalTrackingId != logicalSession.logicalTrackingId ||
				state.serviceRunId != run.serviceRunId ||
				state.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
				state.manifestRevision != authority.manifest.manifestRevision ||
				state.sourcePolicyRevision != authority.manifest.sourcePolicyRevision ||
				state.captureConsentEpoch != binding.consentEpoch ||
				state.collectedDataEpoch != evidenceState.collectedDataEpoch ||
				state.coverageKind in UNSAFE_COVERAGE_KINDS ||
				!validLiveWalFactSemantics(state, run, authority, nextAuthority)
			) {
				return null
			}
		}
		return CandidateAuthorityContext(
			projectionId = projectionId,
			projectionVersion = projectionVersion,
			lane = lane,
			completeness = completeness,
			scopedStates = scopedStates,
		)
	}

	/** Replays every semantic relation that remains reconstructible from the self-contained fact. */
	@Suppress("ReturnCount")
	private fun validLiveWalFactSemantics(
		state: StepFactRevisionEntity,
		run: SourceServiceRunEntity,
		authority: ManifestAuthority,
		nextAuthority: ManifestAuthority?,
	): Boolean {
		val values = liveWalFactValues(state) ?: return false
		val sourceEventId = state.sourceEventId ?: return false
		val expectedLogicalFactId = "${state.writerProjectionId}:$sourceEventId"
		val expectedMutationId =
			"$expectedLogicalFactId:${state.semanticRevision}:${StepFactRevisionEntity.OPERATION_UPSERT}"
		if (!validLiveWalIdentity(state, run, sourceEventId, expectedLogicalFactId, expectedMutationId) ||
			!validLiveWalClockEnvelope(state, run, authority, nextAuthority, values)
		) {
			return false
		}
		return validLiveWalCoverage(state.coverageKind, values)
	}

	@Suppress("ComplexCondition")
	private fun validLiveWalIdentity(
		state: StepFactRevisionEntity,
		run: SourceServiceRunEntity,
		sourceEventId: String,
		expectedLogicalFactId: String,
		expectedMutationId: String,
	): Boolean =
		state.clockDomainId == run.bootId && state.bootClockDomainId == run.bootId &&
			state.semanticRevision == LIVE_WAL_SEMANTIC_REVISION &&
			state.logicalFactId == expectedLogicalFactId && state.originIdentity == sourceEventId &&
			state.mutationId == expectedMutationId

	private fun validLiveWalClockEnvelope(
		state: StepFactRevisionEntity,
		run: SourceServiceRunEntity,
		authority: ManifestAuthority,
		nextAuthority: ManifestAuthority?,
		values: LiveWalFactValues,
	): Boolean {
		if (!validLiveWalIntervalOrder(values) ||
			!validLiveWalAuthorityFloor(state, run, authority, values) ||
			!validLiveWalNextAuthority(nextAuthority, values)
		) {
			return false
		}
		val durationMs = (values.endElapsed - values.startElapsed) / NANOS_PER_MILLISECOND
		val expectedStartMs = if (durationMs > values.endMs) {
			0L
		} else {
			values.endMs - durationMs
		}
		return values.startMs == expectedStartMs
	}

	@Suppress("ComplexCondition")
	private fun validLiveWalIntervalOrder(values: LiveWalFactValues): Boolean =
		values.endMs >= values.startMs && values.endElapsed >= values.startElapsed

	@Suppress("ComplexCondition")
	private fun validLiveWalAuthorityFloor(
		state: StepFactRevisionEntity,
		run: SourceServiceRunEntity,
		authority: ManifestAuthority,
		values: LiveWalFactValues,
	): Boolean =
		values.startElapsed >= run.startedElapsedNanos && state.appliedAtMs == values.endMs &&
			values.startElapsed >= authority.manifest.effectiveElapsedRealtimeNanos &&
			values.endElapsed >= authority.manifest.effectiveElapsedRealtimeNanos

	private fun validLiveWalNextAuthority(
		nextAuthority: ManifestAuthority?,
		values: LiveWalFactValues,
	): Boolean {
		val nextEffective = nextAuthority?.manifest?.effectiveElapsedRealtimeNanos ?: return true
		return values.startElapsed < nextEffective && values.endElapsed < nextEffective
	}

	private fun validLiveWalCoverage(
		coverageKind: String?,
		values: LiveWalFactValues,
	): Boolean =
		when (coverageKind) {
			StepFactRevisionEntity.COVERAGE_BASELINE ->
				values.effective == 0L && values.cumulativeStart == values.cumulativeEnd
			StepFactRevisionEntity.COVERAGE_COVERED ->
				values.cumulativeEnd >= values.cumulativeStart &&
					values.effective == values.cumulativeEnd - values.cumulativeStart
			else -> false
		}

	@Suppress("ReturnCount")
	private fun liveWalFactValues(state: StepFactRevisionEntity): LiveWalFactValues? {
		return LiveWalFactValues(
			startMs = state.intervalStartTimeMs ?: return null,
			endMs = state.intervalEndTimeMs ?: return null,
			startElapsed = state.intervalStartElapsedRealtimeNanos ?: return null,
			endElapsed = state.intervalEndElapsedRealtimeNanos ?: return null,
			cumulativeStart = state.cumulativeStepCountStart ?: return null,
			cumulativeEnd = state.cumulativeStepCountEnd ?: return null,
			effective = state.effectiveStepCount ?: return null,
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun nonStepsContribution(
		segment: SessionSegment,
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		authorities: Map<Long, ManifestAuthority>,
		snapshot: RepairSnapshot,
		lifecycle: BoundLifecycle,
	): SegmentQualification {
		val serviceRunId = run.serviceRunId
		val evidenceState = snapshot.evidenceState ?: return SegmentQualification.Unverifiable
		if (evidenceState.retainedFromMs?.let { retained -> run.startedAtMs < retained } == true) {
			return SegmentQualification.Unverifiable
		}
		val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
			logicalTrackingId = logicalSession.logicalTrackingId,
			serviceRunId = serviceRunId,
		)
		if (digest in snapshot.deletionFenceDigests) {
			return SegmentQualification.Unverifiable
		}
		// A Steps fact attributed to a run whose immutable manifests never captured Steps is
		// contradictory source evidence, not a zero-valued non-Steps contribution.
		if (snapshot.factStatesByRun[serviceRunId].orEmpty().isNotEmpty()) {
			return SegmentQualification.Unverifiable
		}
		val runCompleteness = snapshot.completenessByRun[serviceRunId].orEmpty()
		val capturedSourceKinds = authorities.values.flatMapTo(hashSetOf()) { authority ->
			authority.capturedSourceKinds
		}
		if (runCompleteness.any { row ->
				row.logicalTrackingId != logicalSession.logicalTrackingId ||
					row.serviceRunId != serviceRunId || row.sourceKind !in capturedSourceKinds
			}) {
			return SegmentQualification.Unverifiable
		}
		if (lifecycle == BoundLifecycle.LIVE_MATERIALIZING) {
			return materializing(logicalSession, authorities)
		}
		val captureSlices = manifestCaptureSlices(authorities, run, segment)
			?: return SegmentQualification.Unverifiable
		val capturedForWholeRun = authorities.values.drop(1).fold(
			authorities.values.first().capturedSourceKinds,
		) { common, authority -> common intersect authority.capturedSourceKinds }
			.filterTo(linkedSetOf()) { sourceKind ->
				sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS
			}
		if (capturedForWholeRun.isEmpty()) {
			return SegmentQualification.Unverifiable
		}
		val rowsBySource = runCompleteness.groupBy(SourceSessionCompletenessEntity::sourceKind)
		for (sourceKind in capturedForWholeRun) {
			val rows = rowsBySource[sourceKind].orEmpty()
			if (rows.isEmpty()) {
				continue
			}
			if (rows.any { row -> !row.appDrainComplete }) {
				continue
			}
			if (rows.any { row ->
				row.sourceInstanceId.isBlank() || row.registrationGeneration <= 0L ||
					row.stopStatus != COMPLETE_STOP_STATUS ||
					row.providerCoverage != COMPLETE_PROVIDER_COVERAGE ||
					row.unresolvedSequenceStart != null || row.unresolvedSequenceEnd != null
			}) {
				continue
			}
			val hasDurableObservation = rows.any { row ->
				row.lastAdmissionOrdinal?.let { ordinal -> ordinal > 0L } == true &&
					row.lastSourceSequence?.let { sequence -> sequence >= 0L } == true
			}
			if (hasDurableObservation) {
				val contribution = SegmentContribution(
						logicalTrackingId = logicalSession.logicalTrackingId,
						logicalStartedAtMs = logicalSession.startedAtMs,
						zoneId = authorities.values.first().zoneId,
						segment = segment,
						captureSlices = captureSlices,
						stepContributions = emptyList(),
					)
				return if (lifecycle == BoundLifecycle.SETTLED) {
					SegmentQualification.Ready(contribution)
				} else {
					materializing(logicalSession, authorities)
				}
			}
		}
		return SegmentQualification.Unverifiable
	}

	private fun materializing(
		logicalSession: LogicalTrackingSessionEntity,
		authorities: Map<Long, ManifestAuthority>,
	) = SegmentQualification.Materializing(
		LogicalContributionAuthority(
			logicalTrackingId = logicalSession.logicalTrackingId,
			logicalStartedAtMs = logicalSession.startedAtMs,
			zoneId = authorities.values.first().zoneId,
		),
	)

	private fun hasForbiddenPhysicalOverlap(
		segments: List<SessionSegment>,
		rejectCrossLogicalOverlap: Boolean,
	): Boolean {
		val physical = segments.sortedWith(
			compareBy<SessionSegment>(SessionSegment::startTimeMs)
				.thenBy(SessionSegment::id),
		)
		for (firstIndex in physical.indices) {
			val first = physical[firstIndex]
			for (secondIndex in firstIndex + 1 until physical.size) {
				val second = physical[secondIndex]
				if (second.startTimeMs >= first.endTimeMs) {
					break
				}
				if (rejectCrossLogicalOverlap ||
					first.logicalTrackingId?.let { logicalId ->
						logicalId == second.logicalTrackingId
					} == true
				) {
					return true
				}
			}
		}
		return false
	}

	private fun hasIncompatibleLogicalGroup(group: List<SegmentContribution>): Boolean {
		if (group.map(SegmentContribution::zoneId).distinct().size != 1 ||
			group.map(SegmentContribution::logicalStartedAtMs).distinct().size != 1
		) {
			return true
		}
		val physical = group.map(SegmentContribution::segment).sortedBy(SessionSegment::startTimeMs)
		var physicalEnd = Long.MIN_VALUE
		for (segment in physical) {
			if (segment.startTimeMs < physicalEnd) {
				return true
			}
			physicalEnd = maxOf(physicalEnd, segment.endTimeMs)
		}
		val facts = group.flatMap(SegmentContribution::stepContributions).sortedBy(StepContribution::startMs)
		var factEnd = Long.MIN_VALUE
		for (fact in facts) {
			if (fact.startMs < factEnd) {
				return true
			}
			factEnd = maxOf(factEnd, fact.endMs)
		}
		return false
	}

	private fun hasForbiddenCaptureOverlap(
		contributions: List<SegmentContribution>,
		unboundNonStepsCaptures: List<UnboundNonStepsCapture>,
		rejectCrossLogicalOverlap: Boolean,
	): Boolean {
		val intervals = contributions.flatMap { contribution ->
			contribution.captureSlices.map { slice ->
				LogicalCaptureInterval(
					logicalTrackingId = contribution.logicalTrackingId,
					startMs = slice.startMs,
					endMs = slice.endMs,
				)
			}
		} + unboundNonStepsCaptures.map { capture ->
			LogicalCaptureInterval(
				logicalTrackingId = capture.logicalTrackingId,
				startMs = capture.startMs,
				endMs = capture.endMs,
			)
		}
		val ordered = intervals.sortedWith(
			compareBy<LogicalCaptureInterval>(LogicalCaptureInterval::startMs)
				.thenBy(LogicalCaptureInterval::endMs)
				.thenBy(LogicalCaptureInterval::logicalTrackingId),
		)
		for (firstIndex in ordered.indices) {
			val first = ordered[firstIndex]
			for (secondIndex in firstIndex + 1 until ordered.size) {
				val second = ordered[secondIndex]
				if (second.startMs >= first.endMs) {
					break
				}
				if (rejectCrossLogicalOverlap || first.logicalTrackingId == second.logicalTrackingId) {
					return true
				}
			}
		}
		return false
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ReturnCount")
	private fun composeDay(
		epochDay: Long,
		zoneId: ZoneId,
		groups: Collection<List<SegmentContribution>>,
		unboundNonStepsCaptures: List<UnboundNonStepsCapture>,
	): DayComposition {
		var distance = 0.0
		var steps = 0L
		var duration = 0L
		var trips = 0
		var hasContribution = false
		var exactNumericSteps = 0L
		var hasCompleteStepsCapture = false
		var hasPartialStepsCapture = false
		var hasNonStepsCapture = false
		val dayStartMs = startOfDayMs(epochDay, zoneId) ?: return DayComposition.Unverifiable
		val dayEndMs = startOfDayMs(epochDay + 1L, zoneId) ?: return DayComposition.Unverifiable
		hasNonStepsCapture = unboundNonStepsCaptures.any { capture ->
			capture.startMs < dayEndMs && capture.endMs > dayStartMs
		}
		for (group in groups) {
			val overlapping = group.filter { contribution ->
				(contribution.segment.startTimeMs < dayEndMs &&
					contribution.segment.endTimeMs > dayStartMs) ||
					contribution.captureSlices.any { slice ->
						slice.startMs < dayEndMs && slice.endMs > dayStartMs
					}
			}
			if (overlapping.isEmpty()) {
				continue
			}
			hasContribution = true
			for (contribution in overlapping) {
				for (slice in contribution.captureSlices) {
					val sliceStart = maxOf(slice.startMs, dayStartMs)
					val sliceEnd = minOf(slice.endMs, dayEndMs)
					if (sliceEnd <= sliceStart) {
						continue
					}
					if (slice.capturesSteps) {
						val coveredSteps = exactCoveredSteps(
							contribution.stepContributions,
							sliceStart,
							sliceEnd,
							dayStartMs,
							dayEndMs,
							capturedZoneId = contribution.zoneId,
							summaryZoneId = zoneId,
						)
						if (coveredSteps == null) {
							hasPartialStepsCapture = true
						} else {
							hasCompleteStepsCapture = true
							exactNumericSteps = try {
								Math.addExact(exactNumericSteps, coveredSteps)
							} catch (_: ArithmeticException) {
								return DayComposition.Unverifiable
							}
						}
					} else {
						hasNonStepsCapture = true
					}
				}
				val segment = contribution.segment
				if (segment.startTimeMs < dayEndMs && segment.endTimeMs > dayStartMs) {
					val segmentDuration = segment.endTimeMs - segment.startTimeMs
					val inDayDuration = minOf(segment.endTimeMs, dayEndMs) -
						maxOf(segment.startTimeMs, dayStartMs)
					if (segmentDuration <= 0L || inDayDuration <= 0L) {
						return DayComposition.Unverifiable
					}
					distance += segment.distanceM.toDouble() *
						(inDayDuration.toDouble() / segmentDuration.toDouble())
					duration = try {
						Math.addExact(duration, inDayDuration)
					} catch (_: ArithmeticException) {
						return DayComposition.Unverifiable
					}
				}
				for (fact in contribution.stepContributions) {
					val allocations = allocateSteps(fact.startMs, fact.endMs, fact.count, zoneId)
						?: return DayComposition.Unverifiable
					steps = try {
						Math.addExact(steps, allocations[epochDay] ?: 0L)
					} catch (_: ArithmeticException) {
						return DayComposition.Unverifiable
					}
				}
			}
			if (group.first().logicalStartedAtMs in dayStartMs until dayEndMs) {
				trips += 1
			}
		}
		if (!hasContribution) {
			return DayComposition.Ready(
				totals = null,
				numericSteps = StepsDayNumericComposition.NotCaptured,
			)
		}
		if (!distance.isFinite() || distance > Float.MAX_VALUE || steps !in 0L..Int.MAX_VALUE) {
			return DayComposition.Unverifiable
		}
		return DayComposition.Ready(
			totals = DailySummaryTotals(
				distanceM = distance.toFloat(),
				steps = steps.toInt(),
				durationMs = duration,
				tripCount = trips,
			),
			numericSteps = when {
				hasPartialStepsCapture || hasCompleteStepsCapture && hasNonStepsCapture ->
					StepsDayNumericComposition.PartialCapture
				hasCompleteStepsCapture -> StepsDayNumericComposition.Complete(exactNumericSteps)
				else -> StepsDayNumericComposition.NotCaptured
			},
		)
	}

	/**
	 * Returns an exact day-local total only when covered facts tile the required half-open slice.
	 * A positive fact crossing its captured calendar day or the summary's structural-day boundary
	 * has no exact per-day allocation and cannot be proportionally split. A zero fact may be clipped
	 * because both subintervals remain zero.
	 */
	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun exactCoveredSteps(
		facts: List<StepContribution>,
		requiredStartMs: Long,
		requiredEndMs: Long,
		dayStartMs: Long,
		dayEndMs: Long,
		capturedZoneId: ZoneId,
		summaryZoneId: ZoneId,
	): Long? {
		if (requiredEndMs <= requiredStartMs) {
			return null
		}
		val overlapping = facts.asSequence()
			.filter { fact -> fact.startMs < requiredEndMs && fact.endMs > requiredStartMs }
			.sortedWith(compareBy<StepContribution>(StepContribution::startMs).thenBy(StepContribution::endMs))
			.toList()
		var cursor = requiredStartMs
		var total = 0L
		for (fact in overlapping) {
			if (!hasExactDayAuthority(fact, capturedZoneId, summaryZoneId)) {
				return null
			}
			val clippedStart = maxOf(fact.startMs, requiredStartMs)
			val clippedEnd = minOf(fact.endMs, requiredEndMs)
			if (clippedStart != cursor || clippedEnd <= clippedStart) {
				return null
			}
			val crossesDayBoundary = fact.startMs < dayStartMs || fact.endMs > dayEndMs
			if (crossesDayBoundary && fact.count > 0L) {
				return null
			}
			if (!crossesDayBoundary) {
				total = try {
					Math.addExact(total, fact.count)
				} catch (_: ArithmeticException) {
					return null
				}
			}
			cursor = clippedEnd
		}
		return total.takeIf { cursor == requiredEndMs }
	}

	private fun hasExactDayAuthority(
		fact: StepContribution,
		capturedZoneId: ZoneId,
		summaryZoneId: ZoneId,
	): Boolean {
		val endpointsAreExact = hasExactEndpoints(fact, capturedZoneId) &&
			hasExactEndpoints(fact, summaryZoneId)
		val intervalIsExact = fact.count == 0L || isContainedInCapturedCalendarDay(
			startMs = fact.startMs,
			endMs = fact.endMs,
			zoneId = capturedZoneId,
		)
		return endpointsAreExact && intervalIsExact
	}

	private fun hasExactEndpoints(fact: StepContribution, zoneId: ZoneId): Boolean =
		hasExactEndpointDay(fact.startMs, fact.wallTimeUncertaintyMs, zoneId) &&
			hasExactEndpointDay(fact.endMs, fact.wallTimeUncertaintyMs, zoneId)

	private fun isContainedInCapturedCalendarDay(
		startMs: Long,
		endMs: Long,
		zoneId: ZoneId,
	): Boolean = try {
		endMs > startMs &&
			Instant.ofEpochMilli(startMs).atZone(zoneId).toLocalDate() ==
			Instant.ofEpochMilli(Math.subtractExact(endMs, 1L)).atZone(zoneId).toLocalDate()
	} catch (_: DateTimeException) {
		false
	} catch (_: ArithmeticException) {
		false
	}

	private fun hasExactEndpointDay(
		wallTimeMs: Long,
		uncertaintyMs: Long,
		zoneId: ZoneId,
	): Boolean {
		if (uncertaintyMs < 0L) {
			return false
		}
		val earliest = try {
			Math.subtractExact(wallTimeMs, uncertaintyMs)
		} catch (_: ArithmeticException) {
			return false
		}
		val latest = try {
			Math.addExact(wallTimeMs, uncertaintyMs)
		} catch (_: ArithmeticException) {
			return false
		}
		return try {
			val earliestInstant = Instant.ofEpochMilli(earliest)
			val latestInstant = Instant.ofEpochMilli(latest)
			epochDay(earliest, zoneId)?.let { earliestDay ->
				earliestDay == epochDay(latest, zoneId) &&
					zoneId.rules.getOffset(earliestInstant) == zoneId.rules.getOffset(latestInstant)
			} == true
		} catch (_: DateTimeException) {
			false
		}
	}

	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun allocateSteps(
		startMs: Long,
		endMs: Long,
		count: Long,
		zoneId: ZoneId,
	): Map<Long, Long>? {
		if (endMs <= startMs || count < 0L) {
			return null
		}
		val startDay = epochDay(startMs, zoneId) ?: return null
		val endDay = epochDay(endMs - 1L, zoneId) ?: return null
		val dayCount = endDay - startDay + 1L
		if (dayCount <= 0L || dayCount > MAX_REPAIR_DAYS) {
			return null
		}
		val totalDuration = endMs - startMs
		val total = BigInteger.valueOf(totalDuration)
		val weighted = (0L until dayCount).map { offset ->
			val allocationDay = startDay + offset
			val dayEndMs = startOfDayMs(allocationDay + 1L, zoneId) ?: return null
			val dayStartMs = startOfDayMs(allocationDay, zoneId) ?: return null
			val duration = minOf(endMs, dayEndMs) - maxOf(startMs, dayStartMs)
			val numerator = BigInteger.valueOf(count).multiply(BigInteger.valueOf(duration))
			val division = numerator.divideAndRemainder(total)
			WeightedDay(allocationDay, division[0].toLong(), division[1])
		}.toMutableList()
		val assigned = weighted.sumOf(WeightedDay::base)
		val remainder = count - assigned
		if (remainder < 0L || remainder >= dayCount) {
			return null
		}
		weighted.sortWith(compareByDescending<WeightedDay> { it.remainder }.thenBy { it.epochDay })
		repeat(remainder.toInt()) { index ->
			weighted[index] = weighted[index].copy(base = weighted[index].base + 1L)
		}
		return weighted.associate { day -> day.epochDay to day.base }
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun manifestAuthorities(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		manifests: List<SessionManifestVersionEntity>,
		sourcesByManifest: Map<ManifestKey, List<SessionManifestSourceEntity>>,
	): Map<Long, ManifestAuthority>? {
		val logicalTrackingId = logicalSession.logicalTrackingId
		val serviceRunId = run.serviceRunId
		val ordered = manifests.sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (ordered.isEmpty() || run.preparedManifestRevision <= 0L ||
			ordered.map(SessionManifestVersionEntity::manifestRevision).distinct().size != ordered.size ||
			ordered.first().manifestRevision != run.preparedManifestRevision ||
			ordered.first().effectiveWallTimeMs != run.startedAtMs ||
			ordered.first().effectiveElapsedRealtimeNanos != run.startedElapsedNanos ||
			ordered.first().effectiveBootId != run.bootId ||
			ordered.last().acquisitionPlanRevision != run.desiredPlanRevision
		) {
			return null
		}
		val currentIntentRevision = logicalSession.currentIntentRevision
		val retainedManifestRevision = logicalSession.currentManifestRevision
		if (retainedManifestRevision != null &&
			retainedManifestRevision < ordered.last().manifestRevision
		) {
			return null
		}
		if (logicalSession.currentServiceRunId == run.serviceRunId &&
			(logicalSession.currentManifestRevision != ordered.last().manifestRevision ||
				currentIntentRevision == null || currentIntentRevision < run.preparedIntentRevision ||
				logicalSession.desiredPlanRevision != run.desiredPlanRevision ||
				logicalSession.clockDomainId != run.bootId ||
				logicalSession.lifecycleBootId != run.bootId ||
				logicalSession.lifecycleLeaseGeneration != run.leaseGeneration)
		) {
			return null
		}
		val isLatestTerminalAuthority = logicalSession.state in TERMINAL_LOGICAL_STATES &&
			retainedManifestRevision == ordered.last().manifestRevision
		if (isLatestTerminalAuthority &&
			(run.state != logicalSession.state ||
				logicalSession.completedAtMs != run.completedAtMs ||
				logicalSession.desiredPlanRevision != run.desiredPlanRevision ||
				logicalSession.clockDomainId != run.bootId ||
				logicalSession.lifecycleBootId != run.bootId ||
				logicalSession.lifecycleLeaseGeneration != run.leaseGeneration ||
				currentIntentRevision == null || currentIntentRevision < run.preparedIntentRevision)
		) {
			return null
		}
		val authorities = linkedMapOf<Long, ManifestAuthority>()
		for ((index, manifest) in ordered.withIndex()) {
			if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId ||
				manifest.rolloutRevision != run.rolloutRevision ||
				manifest.rolloutRevision != logicalSession.rolloutRevision ||
				manifest.sessionMode != logicalSession.sessionMode ||
				manifest.startOrigin != run.startOrigin ||
				manifest.effectiveBootId != run.bootId ||
				manifest.effectiveElapsedRealtimeNanos < run.startedElapsedNanos ||
				manifest.acquisitionPlanRevision <= 0L
			) {
				return null
			}
			if (index > 0) {
				val previous = ordered[index - 1]
				val expectedRevision = try {
					Math.addExact(previous.manifestRevision, 1L)
				} catch (_: ArithmeticException) {
					return null
				}
				if (manifest.manifestRevision != expectedRevision ||
					manifest.effectiveBootId != previous.effectiveBootId ||
					manifest.effectiveElapsedRealtimeNanos < previous.effectiveElapsedRealtimeNanos
				) {
					return null
				}
			}
			val sources = sourcesByManifest[ManifestKey(logicalTrackingId, manifest.manifestRevision)].orEmpty()
			if (!SessionManifestIntegrity.verify(manifest, sources) ||
				sources.any { source ->
					source.purpose !in SessionManifestPurposeCode.ALL || source.sourceKind !in KNOWN_SOURCE_KINDS
				}
			) {
				return null
			}
			val captureSources = sources.filter { source ->
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}
			if (captureSources.isEmpty()) {
				return null
			}
			val stepsSources = captureSources.filter { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS && source.persistenceEligible
			}
			if (stepsSources.size > 1) {
				return null
			}
			val zoneId = try {
				ZoneId.of(manifest.zoneId)
			} catch (_: DateTimeException) {
				return null
			}
			authorities[manifest.manifestRevision] = ManifestAuthority(
				manifest = manifest,
				stepsBinding = stepsSources.singleOrNull(),
				capturedSourceKinds = captureSources.mapTo(linkedSetOf(), SessionManifestSourceEntity::sourceKind),
				zoneId = zoneId,
			)
		}
		return authorities
	}

	private fun historicalCaptureModeMask(
		authorities: Map<Long, ManifestAuthority>,
	): Long? {
		val sessionMode = authorities.values.map { authority -> authority.manifest.sessionMode }
			.distinct()
			.singleOrNull()
		return when (sessionMode) {
			SessionMode.MANUAL.name -> CaptureReachabilityMode.MANUAL_SESSION_CAPTURE.mask
			SessionMode.AUTOMATIC.name -> CaptureReachabilityMode.AUTOMATIC_SESSION_CAPTURE.mask
			else -> null
		}
	}

	/** Exact manifest-effective capture slices for one physical service-run segment. */
	@Suppress("CyclomaticComplexMethod")
	private fun manifestCaptureSlices(
		authorities: Map<Long, ManifestAuthority>,
		run: SourceServiceRunEntity,
		segment: SessionSegment,
	): List<SegmentCaptureSlice>? {
		val ordered = authorities.values.sortedBy { authority -> authority.manifest.manifestRevision }
		val sourceEndMs = run.completedAtMs ?: segment.endTimeMs
		if (ordered.isEmpty() || sourceEndMs <= run.startedAtMs ||
			ordered.first().manifest.effectiveWallTimeMs != run.startedAtMs
		) {
			return null
		}
		val slices = mutableListOf<SegmentCaptureSlice>()
		for (index in ordered.indices) {
			val authority = ordered[index]
			val effectiveAtMs = authority.manifest.effectiveWallTimeMs
			if (index > 0) {
				val previous = ordered[index - 1].manifest
				if (authority.manifest.manifestRevision <= previous.manifestRevision ||
					effectiveAtMs <= previous.effectiveWallTimeMs ||
					effectiveAtMs <= run.startedAtMs
				) {
					return null
				}
			}
			val startMs = if (index == 0) {
				run.startedAtMs
			} else {
				effectiveAtMs
			}
			val endMs = ordered.getOrNull(index + 1)?.manifest?.effectiveWallTimeMs
				?: sourceEndMs
			if (startMs < run.startedAtMs || endMs > sourceEndMs || endMs <= startMs) {
				return null
			}
			slices += SegmentCaptureSlice(
				manifestRevision = authority.manifest.manifestRevision,
				startMs = startMs,
				endMs = endMs,
				capturesSteps = authority.stepsBinding != null,
			)
		}
		return slices
	}

	private fun writerBinding(source: SessionManifestSourceEntity) = WriterBinding(
		owner = source.writerOwner ?: "",
		ownerGeneration = source.writerOwnerGeneration ?: -1L,
		projectionId = source.writerProjectionId,
		projectionVersion = source.writerProjectionVersion,
		bindingGeneration = source.writerBindingGeneration,
	)

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
	private fun validHistoricalLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.captureModeMask <= 0L ||
			lane.captureModeMask and CaptureReachabilityMode.ALL_MASK.inv() != 0L ||
			lane.productStage !in EXECUTABLE_PRODUCT_STAGES ||
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
				if (lane.retentionRequired || terminalDisposition == null) {
					return false
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

	private fun allZoneQueryBounds(sortedDays: List<Long>): QueryBounds? {
		if (sortedDays.isEmpty() || sortedDays.size > MAX_REPAIR_DAYS ||
			sortedDays.last() - sortedDays.first() + 1L > MAX_REPAIR_DAYS
		) {
			return null
		}
		return try {
			val utcStart = LocalDate.ofEpochDay(sortedDays.first())
				.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
			val utcEnd = LocalDate.ofEpochDay(Math.addExact(sortedDays.last(), 1L))
				.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
			QueryBounds(
				fromMs = Math.subtractExact(utcStart, MAX_ZONE_OFFSET_MS),
				toMs = Math.addExact(utcEnd, MAX_ZONE_OFFSET_MS),
			)
		} catch (_: ArithmeticException) {
			null
		} catch (_: DateTimeException) {
			null
		}
	}

	private fun epochDay(instantMs: Long, zoneId: ZoneId): Long? = try {
		Instant.ofEpochMilli(instantMs).atZone(zoneId).toLocalDate().toEpochDay()
	} catch (_: DateTimeException) {
		null
	}

	private fun startOfDayMs(epochDay: Long, zoneId: ZoneId): Long? = try {
		LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()
	} catch (_: DateTimeException) {
		null
	} catch (_: ArithmeticException) {
		null
	}

	private fun unverifiable() = StepsDayRepairPreflight.Unsupported(
		StepsSessionDeletionUnsupportedReason.DAY_REPAIR_UNVERIFIABLE,
	)

	private data class ManifestKey(val logicalTrackingId: String, val manifestRevision: Long)
	private data class LaneKey(val generation: Long, val projectionId: String, val version: Int)
	private data class QueryBounds(val fromMs: Long, val toMs: Long)
	private data class ManifestAuthority(
		val manifest: SessionManifestVersionEntity,
		val stepsBinding: SessionManifestSourceEntity?,
		val capturedSourceKinds: Set<Int>,
		val zoneId: ZoneId,
	)
	private data class WriterBinding(
		val owner: String,
		val ownerGeneration: Long,
		val projectionId: String?,
		val projectionVersion: Int?,
		val bindingGeneration: Long?,
	)
	private data class SegmentContribution(
		val logicalTrackingId: String,
		val logicalStartedAtMs: Long,
		val zoneId: ZoneId,
		val segment: SessionSegment,
		val captureSlices: List<SegmentCaptureSlice>,
		val stepContributions: List<StepContribution>,
	)
	private data class LogicalContributionAuthority(
		val logicalTrackingId: String,
		val logicalStartedAtMs: Long,
		val zoneId: ZoneId,
	)
	private data class SegmentCaptureSlice(
		val manifestRevision: Long,
		val startMs: Long,
		val endMs: Long,
		val capturesSteps: Boolean,
	)
	private data class StepContribution(
		val startMs: Long,
		val endMs: Long,
		val count: Long,
		val wallTimeUncertaintyMs: Long,
	)
	private data class UnboundNonStepsCapture(
		val logicalTrackingId: String,
		val startMs: Long,
		val endMs: Long,
	)
	private data class LogicalCaptureInterval(
		val logicalTrackingId: String,
		val startMs: Long,
		val endMs: Long,
	)
	private data class WeightedDay(
		val epochDay: Long,
		val base: Long,
		val remainder: BigInteger,
	)
	private data class CandidateAuthorityContext(
		val projectionId: String,
		val projectionVersion: Int,
		val lane: SourceProductProjectionLaneEntity,
		val completeness: List<SourceSessionCompletenessEntity>,
		val scopedStates: List<ScopedStepFactState>,
	)
	private data class LiveWalFactValues(
		val startMs: Long,
		val endMs: Long,
		val startElapsed: Long,
		val endElapsed: Long,
		val cumulativeStart: Long,
		val cumulativeEnd: Long,
		val effective: Long,
	)
	private data class RepairSnapshot(
		val logicalSessions: Map<String, LogicalTrackingSessionEntity>,
		val serviceRuns: Map<String, SourceServiceRunEntity>,
		val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		val sourcesByManifest: Map<ManifestKey, List<SessionManifestSourceEntity>>,
		val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
		val factStatesByRun: Map<String, List<ScopedStepFactState>>,
		val lanes: Map<LaneKey, SourceProductProjectionLaneEntity>,
		val deletionFenceDigests: Set<String>,
		val failures: List<SourceProjectionFailureEntity>,
		val evidenceState: SourceEvidenceState?,
	)
	private sealed interface SegmentQualification {
		data class Ready(val contribution: SegmentContribution) : SegmentQualification
		data class Materializing(val authority: LogicalContributionAuthority) : SegmentQualification
		data object Unverifiable : SegmentQualification
	}
	private enum class BoundLifecycle {
		LIVE_MATERIALIZING,
		TERMINAL_PRESENTATION_MATERIALIZING,
		RESTART_GAP_MATERIALIZING,
		SETTLED,
	}
	private sealed interface UnboundRunQualification {
		data class NonSteps(
			val capture: UnboundNonStepsCapture,
			val authority: LogicalContributionAuthority,
		) : UnboundRunQualification
		data class Materializing(val authority: LogicalContributionAuthority) : UnboundRunQualification
		data object Unverifiable : UnboundRunQualification
	}
	private sealed interface DayComposition {
		data class Ready(
			val totals: DailySummaryTotals?,
			val numericSteps: StepsDayNumericComposition,
		) : DayComposition
		data object Unverifiable : DayComposition
	}

	private companion object {
		const val MAX_REPAIR_DAYS = StepsNumericSummaryRequest.MAX_DAY_COUNT
		const val READ_PAGE_SIZE = 256
		const val QUERY_ID_BATCH_SIZE = 400
		// Cap-plus-one queries reject the complete snapshot on pathological metadata churn or
		// corruption. These are resource budgets, never permission to compose a truncated prefix.
		const val MAX_MANIFESTS_PER_QUERY_BATCH = 8_192
		const val MAX_SOURCES_PER_QUERY_BATCH = 49_152
		const val MAX_COMPLETENESS_PER_QUERY_BATCH = 8_192
		const val MAX_TERMINAL_FAILURES_PER_QUERY_BATCH = 2_048
		const val MAX_ZONE_OFFSET_MS = 18L * 60L * 60_000L
		const val NANOS_PER_MILLISECOND = 1_000_000L
		const val LIVE_WAL_SEMANTIC_REVISION = 1L
		const val COMPLETE_STOP_STATUS = "COMPLETE"
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
		val KNOWN_SOURCE_KINDS = SourceKind.values().mapTo(hashSetOf(), SourceKind::stableCode)
		val UNSAFE_COVERAGE_KINDS = setOf(
			StepFactRevisionEntity.COVERAGE_PARTIAL,
			StepFactRevisionEntity.COVERAGE_RESET_GAP,
		)
		val EXACT_SESSION_MODES = setOf(SessionMode.MANUAL.name, SessionMode.AUTOMATIC.name)
		val ACTIVE_RUN_LOGICAL_STATES = setOf(
			SessionLifecycleState.ACTIVE.name,
			SessionLifecycleState.RECONFIGURING.name,
		)
		val STOPPING_RUN_LOGICAL_STATES = setOf(
			SessionLifecycleState.ACTIVE.name,
			SessionLifecycleState.STOPPING.name,
		)
		val LIVE_LOGICAL_STATES = ACTIVE_RUN_LOGICAL_STATES + STOPPING_RUN_LOGICAL_STATES +
			SessionLifecycleState.STARTING.name
		val TERMINAL_LOGICAL_STATES = setOf(
			SessionLifecycleState.FINALIZED.name,
			SessionLifecycleState.FAILED.name,
		)
		val EXACT_PRESENTATION_ACKNOWLEDGEMENTS = setOf(
			SourceServiceRunEntity.PRESENTATION_PENDING,
			SourceServiceRunEntity.PRESENTATION_QUIESCED,
		)
		val EXECUTABLE_PRODUCT_STAGES = setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
	}
}

internal sealed interface StepsDayRepairPreflight {
	data class Ready(val plans: List<StepsDayRepairPlan>) : StepsDayRepairPreflight
	data class Unsupported(
		val reason: StepsSessionDeletionUnsupportedReason,
	) : StepsDayRepairPreflight
	data object Materializing : StepsDayRepairPreflight
}

internal data class StepsDayRepairPlan(
	val epochDay: Long,
	val zoneId: ZoneId,
	val totals: DailySummaryTotals?,
	val numericSteps: StepsDayNumericComposition,
)

/** Decision-facing Steps truth carried beside the broader day-repair totals. */
internal sealed interface StepsDayNumericComposition {
	data class Complete(val steps: Long) : StepsDayNumericComposition {
		init {
			require(steps >= 0L)
		}
	}

	data object NotCaptured : StepsDayNumericComposition
	data object PartialCapture : StepsDayNumericComposition
}

/** Exact wall-time envelope for one contiguous chain of structural calendar days. */
internal data class StepsNumericReadQueryBounds(
	val fromMs: Long,
	val toMs: Long,
)

/**
 * Resolves known per-day zone authorities without permitting gaps or double-counted wall time.
 *
 * A changed zone is accepted only when its next structural-day boundary exactly continues the
 * previous authority. Discontinuous travel-day semantics need an explicit product contract before
 * a numeric consumer may combine them.
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
internal fun stepsNumericReadQueryBounds(
	zoneByDay: Map<Long, ZoneId>,
): StepsNumericReadQueryBounds? {
	val ordered = zoneByDay.toSortedMap()
	if (ordered.isEmpty() || ordered.size > StepsNumericSummaryRequest.MAX_DAY_COUNT) {
		return null
	}
	var previousEpochDay: Long? = null
	var previousEndMs: Long? = null
	var firstStartMs: Long? = null
	for ((epochDay, zoneId) in ordered) {
		val priorDay = previousEpochDay
		if (priorDay != null) {
			val expected = try {
				Math.addExact(priorDay, 1L)
			} catch (_: ArithmeticException) {
				return null
			}
			if (epochDay != expected) {
				return null
			}
		}
		val nextEpochDay = try {
			Math.addExact(epochDay, 1L)
		} catch (_: ArithmeticException) {
			return null
		}
		val startMs = structuralDayStartMs(epochDay, zoneId) ?: return null
		val endMs = structuralDayStartMs(nextEpochDay, zoneId) ?: return null
		if (endMs <= startMs || previousEndMs?.let { it != startMs } == true) {
			return null
		}
		if (firstStartMs == null) {
			firstStartMs = startMs
		}
		previousEpochDay = epochDay
		previousEndMs = endMs
	}
	return StepsNumericReadQueryBounds(
		fromMs = firstStartMs ?: return null,
		toMs = previousEndMs ?: return null,
	)
}

private fun structuralDayStartMs(epochDay: Long, zoneId: ZoneId): Long? = try {
	LocalDate.ofEpochDay(epochDay).atStartOfDay(zoneId).toInstant().toEpochMilli()
} catch (_: DateTimeException) {
	null
} catch (_: ArithmeticException) {
	null
}
