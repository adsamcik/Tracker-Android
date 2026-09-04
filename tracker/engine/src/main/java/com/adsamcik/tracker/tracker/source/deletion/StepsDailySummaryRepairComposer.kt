package com.adsamcik.tracker.tracker.source.deletion

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.aggregator.DailySummaryTotals
import com.adsamcik.tracker.shared.base.database.dao.ScopedStepFactState
import com.adsamcik.tracker.shared.base.database.dao.TrackingHistoryReadDao
import com.adsamcik.tracker.shared.base.database.dao.hasValidStepsFactCandidateState
import com.adsamcik.tracker.shared.base.database.dao.visitStepsFactCandidateStatesByRun
import com.adsamcik.tracker.shared.base.database.data.DailySummaryEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionIntegrity
import com.adsamcik.tracker.shared.base.database.data.StepsSessionCompletenessIntegrity
import com.adsamcik.tracker.stats.api.repository.StepsNumericSummaryRequest
import com.adsamcik.tracker.stats.api.repository.StepsSessionDeletionUnsupportedReason
import com.adsamcik.tracker.tracker.source.coordinator.CaptureReachabilityMode
import com.adsamcik.tracker.tracker.source.coordinator.ExecutableSourceLaneCatalog
import com.adsamcik.tracker.tracker.source.coordinator.SessionLifecycleState
import com.adsamcik.tracker.tracker.source.coordinator.SessionMode
import com.adsamcik.tracker.tracker.source.model.SourceKind
import com.adsamcik.tracker.tracker.source.summary.StepsNumericCoveredFact
import com.adsamcik.tracker.tracker.source.summary.StepsNumericDayWindowAccumulator
import com.adsamcik.tracker.tracker.source.summary.StepsNumericRunContribution
import java.time.DateTimeException
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
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority =
		ExecutableSourceLaneCatalog(),
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
		).requirePersistableNumericSteps()
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
		).requirePersistableNumericSteps()
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
		val retainedFromMs = database.sourceEvidenceStateDao().get()?.retainedFromMs
		if (retainedFromMs != null) {
			for ((epochDay, zoneId) in zoneByDay) {
				val dayStartMs = startOfDayMs(epochDay, zoneId) ?: return unverifiable()
				// A fact discarded below this floor could have wall-regressed into any earlier day.
				// Without its payload, an empty candidate set cannot prove that day was not captured.
				if (dayStartMs < retainedFromMs) {
					return unverifiable()
				}
			}
		}
		val presentationSegments = sourceRepairSegments(
			queryBounds = queryBounds,
			excludedSegmentId = excludedSegmentId,
		) ?: return unverifiable()
		val discoveredSourceRuns = if (discoverSourceRuns) {
			(allSourceRunCandidates(queryBounds) ?: return unverifiable()).filterNot { run ->
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
		val factDescriptors = linkedMapOf<String, RunFactDescriptor>()
		val unboundNonStepsCaptures = mutableListOf<UnboundNonStepsCapture>()
		val materializingAuthorities = mutableListOf<LogicalContributionAuthority>()
		var hasMaterializingSegment = false
		for (segment in segments) {
			when (val qualified = qualify(segment, snapshot)) {
				is SegmentQualification.Ready -> {
					contributions += qualified.contribution
					if (!addFactDescriptor(factDescriptors, qualified.factDescriptor)) {
						return unverifiable()
					}
				}
				is SegmentQualification.Materializing -> {
					hasMaterializingSegment = true
					materializingAuthorities += qualified.authority
					if (!addFactDescriptor(factDescriptors, qualified.factDescriptor)) {
						return unverifiable()
					}
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
		val accumulator = when {
			zoneByDay.isNotEmpty() -> StepsNumericDayWindowAccumulator.create(zoneByDay)
			excludedSegmentId != null -> StepsNumericDayWindowAccumulator.createEmptyValidationOnly()
			else -> null
		} ?: return unverifiable()
		for (group in groups.values) {
			if (!accumulator.addLogicalGroup(group.map { contribution ->
					contribution.toAccumulatorContribution()
				})
			) {
				return unverifiable()
			}
		}
		for (capture in unboundNonStepsCaptures) {
			if (!accumulator.addUnboundNonStepsCapture(capture.startMs, capture.endMs)) {
				return unverifiable()
			}
		}
		if (!streamFactStatePages(factDescriptors.values.toList(), accumulator)) {
			return unverifiable()
		}
		if (hasMaterializingSegment) {
			return StepsDayRepairPreflight.Materializing
		}
		val accumulatedDays = accumulator.results() ?: return unverifiable()
		return StepsDayRepairPreflight.Ready(
			accumulatedDays.map { day ->
				val zoneId = zoneByDay[day.epochDay] ?: return unverifiable()
				StepsDayRepairPlan(
					epochDay = day.epochDay,
					zoneId = zoneId,
					totals = if (day.hasContribution) {
						DailySummaryTotals(
							distanceM = day.distanceM,
							steps = day.steps,
							durationMs = day.durationMs,
							tripCount = day.tripCount,
						)
					} else {
						null
					},
					numericSteps = when {
						day.hasPartialStepsCapture ||
							day.hasCompleteStepsCapture && day.hasNonStepsCapture ->
							StepsDayNumericComposition.PartialCapture
						day.hasCompleteStepsCapture ->
							StepsDayNumericComposition.Complete(day.exactSteps)
						else -> StepsDayNumericComposition.NotCaptured
					},
				)
			},
		)
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

	/**
	 * Unions presentation-envelope discovery with source-fact wall projections.
	 *
	 * Wall clocks may jump while one elapsed-authorized run remains active, so neither a run nor its
	 * segment envelope is sufficient to discover every fact that contributes to a civil day.
	 */
	@Suppress("ReturnCount")
	private suspend fun allSourceRunCandidates(
		queryBounds: QueryBounds,
	): List<SourceServiceRunEntity>? {
		val envelopeRuns = serviceRunCandidates(queryBounds) ?: return null
		val runsById = envelopeRuns.associateByTo(linkedMapOf()) { run -> run.serviceRunId }
		val factRunIds = factServiceRunCandidateIds(queryBounds) ?: return null
		val missingRunIds = factRunIds.filterNot(runsById::containsKey)
		for (ids in missingRunIds.chunked(QUERY_ID_BATCH_SIZE)) {
			val loaded = database.trackingHistoryReadDao().serviceRuns(ids)
			if (loaded.map(SourceServiceRunEntity::serviceRunId).toSet() != ids.toSet()) {
				return null
			}
			loaded.forEach { run ->
				if (run.serviceRunId.isBlank() || runsById.put(run.serviceRunId, run) != null) {
					return null
				}
			}
		}
		return runsById.values.toList()
	}

	private suspend fun factServiceRunCandidateIds(queryBounds: QueryBounds): List<String>? {
		val rows = mutableListOf<String>()
		var afterServiceRunId: String? = null
		var pageSize: Int
		do {
			val page = database.trackingHistoryReadDao().stepFactServiceRunCandidateIdPage(
				fromMs = queryBounds.fromMs,
				toMs = queryBounds.toMs,
				limit = READ_PAGE_SIZE,
				afterServiceRunId = afterServiceRunId,
			)
			val orderedPage = rows.lastOrNull()?.let { previous -> listOf(previous) + page } ?: page
			if (page.any(String::isBlank) || orderedPage.zipWithNext().any { (left, right) ->
					right <= left
				}
			) {
				return null
			}
			rows += page
			afterServiceRunId = page.lastOrNull()
			pageSize = page.size
		} while (pageSize == READ_PAGE_SIZE)
		return rows
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
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
		val serviceRunIds = runs.map(SourceServiceRunEntity::serviceRunId)
		if (serviceRunIds.distinct().size != serviceRunIds.size) {
			return false
		}
		val manifests = mutableListOf<SessionManifestVersionEntity>()
		val sources = mutableListOf<SessionManifestSourceEntity>()
		for (ids in serviceRunIds.chunked(QUERY_ID_BATCH_SIZE)) {
			val manifestBatch = readDao.manifests(ids, limit = MAX_MANIFESTS_PER_QUERY_BATCH + 1)
			val sourceBatch = readDao.manifestSources(ids, limit = MAX_SOURCES_PER_QUERY_BATCH + 1)
			if (manifestBatch.size > MAX_MANIFESTS_PER_QUERY_BATCH ||
				sourceBatch.size > MAX_SOURCES_PER_QUERY_BATCH
			) {
				return false
			}
			manifests += manifestBatch
			sources += sourceBatch
		}
		val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
		val sourcesByManifest = sources.groupBy { source ->
			ManifestKey(source.logicalTrackingId, source.manifestRevision)
		}
		val expectedBySource = linkedMapOf<Int, MutableSet<String>>()
		for (run in runs) {
			val runManifests = manifestsByRun[run.serviceRunId].orEmpty()
			if (!SessionManifestIntegrity.hasValidServiceRunTimeline(run, runManifests)) {
				return false
			}
			val captureSourceKinds = mutableSetOf<Int>()
			for (manifest in runManifests) {
				val manifestSources = sourcesByManifest[
					ManifestKey(manifest.logicalTrackingId, manifest.manifestRevision)
				].orEmpty()
				if (!SessionManifestIntegrity.verify(manifest, manifestSources) ||
					manifestSources.any { source ->
						source.sourceKind !in KNOWN_SOURCE_KINDS ||
							source.purpose !in SessionManifestPurposeCode.ALL
					}
				) {
					return false
				}
				val captures = manifestSources.filter { source ->
					source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE
				}
				val capture = captures.singleOrNull()?.takeIf { it.persistenceEligible } ?: return false
				captureSourceKinds += capture.sourceKind
			}
			val sourceKind = captureSourceKinds.singleOrNull()
				?.takeIf { source -> source in DELETABLE_SOURCE_KINDS }
				?: return false
			val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
				sourceKind = sourceKind,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalTrackingId = run.logicalTrackingId,
				serviceRunId = run.serviceRunId,
			)
			expectedBySource.getOrPut(sourceKind, ::linkedSetOf) += digest
		}
		for ((sourceKind, expectedDigests) in expectedBySource) {
			val actualDigests = expectedDigests.chunked(QUERY_ID_BATCH_SIZE)
				.flatMapTo(hashSetOf()) { digests ->
					readDao.deletionFences(
						sourceKind = sourceKind,
						purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
						scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
						scopeIdentityDigests = digests,
					).map(SourceDeletionFenceEntity::scopeIdentityDigest)
				}
			if (actualDigests != expectedDigests) {
				return false
			}
		}
		return true
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
		val policies = linkedMapOf<Long, SourcePolicyEntity>()
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
			readDao.policiesForServiceRuns(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				serviceRunIds = ids,
			).forEach { policy -> policies[policy.policyRevision] = policy }
			lanes += readDao.productLanesForServiceRuns(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				serviceRunIds = ids,
			)
		}
		val requestedConsentEpochs = sources.asSequence().filter { source ->
			source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
				source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
				source.persistenceEligible
		}.map(SessionManifestSourceEntity::consentEpoch).distinct().toList()
		val consents = requestedConsentEpochs.chunked(QUERY_ID_BATCH_SIZE).flatMap { epochs ->
			database.sourcePolicyDao().consentEpochs(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				epochs = epochs,
			)
		}.associateBy(SourceConsentEpochEntity::epoch)
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
		val retentionScopesByDigest = serviceRuns.associateBy { run ->
			StepFactRevisionIntegrity.retentionTruncationIdentity(
				logicalTrackingId = run.logicalTrackingId,
				serviceRunId = run.serviceRunId,
			)
		}
		val retentionTruncationFences = retentionScopesByDigest.keys
			.chunked(QUERY_ID_BATCH_SIZE)
			.flatMap { digests ->
				readDao.deletionFences(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = StepFactRevisionIntegrity.RETENTION_TRUNCATION_PURPOSE,
					scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
					scopeIdentityDigests = digests,
				)
			}
		if (!hasOnlyAuthorizedFactCandidates(readDao, serviceRuns, deletionFences)) {
			return null
		}
		if (!hasValidCanonicalFactTimelines(readDao, serviceRuns)) {
			return null
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
		val evidenceState = database.sourceEvidenceStateDao().get()
		if (retentionTruncationFences.any { fence ->
				val run = retentionScopesByDigest[fence.scopeIdentityDigest]
				run == null || evidenceState == null ||
					!StepFactRevisionIntegrity.isRetentionTruncationFence(
						fence = fence,
						logicalTrackingId = run.logicalTrackingId,
						serviceRunId = run.serviceRunId,
						collectedDataEpoch = evidenceState.collectedDataEpoch,
					)
			}
		) {
			return null
		}
		return RepairSnapshot(
			logicalSessions = logicalSessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			serviceRuns = serviceRunsById,
			manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByManifest = sources.groupBy { source ->
				ManifestKey(source.logicalTrackingId, source.manifestRevision)
			},
			stepPolicies = policies,
			stepCaptureConsents = consents,
			completenessByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId),
			lanes = lanes.distinct().associateBy { lane ->
				LaneKey(lane.bindingGeneration, lane.projectionId, lane.projectionVersion)
			},
			deletionFenceDigests = deletionFences.mapTo(hashSetOf()) { fence ->
				fence.scopeIdentityDigest
			},
			retentionTruncationDigests = retentionTruncationFences.mapTo(hashSetOf()) { fence ->
				fence.scopeIdentityDigest
			},
			failures = failures,
			evidenceState = evidenceState,
		)
	}

	@Suppress("ComplexCondition")
	private suspend fun hasOnlyAuthorizedFactCandidates(
		readDao: TrackingHistoryReadDao,
		serviceRuns: List<SourceServiceRunEntity>,
		deletionFences: List<SourceDeletionFenceEntity>,
	): Boolean {
		val runsById = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId)
		val fencesByDigest = deletionFences.associateBy(SourceDeletionFenceEntity::scopeIdentityDigest)
		return readDao.visitStepsFactCandidateStatesByRun(serviceRuns) { runId, _, candidate ->
			val run = runsById[runId]
			val scope = candidate.scopeCarrier
			val state = candidate.state
			if (!hasValidStepsFactCandidateState(candidate) || run == null || scope == null ||
				state == null || scope.serviceRunId != runId ||
				scope.logicalTrackingId != run.logicalTrackingId
			) {
				false
			} else if (state.operation == StepFactRevisionEntity.OPERATION_RETRACT) {
				val digest = SourceDeletionFenceEntity.logicalServiceRunIdentity(
					sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
					purpose = StepFactRevisionEntity.PURPOSE_SESSION_CAPTURE,
					logicalTrackingId = run.logicalTrackingId,
					serviceRunId = runId,
				)
				val fence = fencesByDigest[digest]
				fence != null && state.scopeDeletionGeneration == fence.fenceGeneration &&
					state.collectedDataEpoch == fence.collectedDataEpoch
			} else {
				true
			}
		}
	}

	/** Streams exact run-local fact chains in admission order after raw candidate authentication. */
	@Suppress("CyclomaticComplexMethod")
	private suspend fun hasValidCanonicalFactTimelines(
		readDao: TrackingHistoryReadDao,
		serviceRuns: List<SourceServiceRunEntity>,
	): Boolean {
		val runsById = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId)
		for (runBatch in serviceRuns.chunked(QUERY_ID_BATCH_SIZE)) {
			val runIds = runBatch.map(SourceServiceRunEntity::serviceRunId)
			var afterServiceRunId: String? = null
			var afterSourceAdmissionOrdinal: Long? = null
			var afterWriterProjectionId: String? = null
			var afterWriterProjectionVersion: Int? = null
			var afterLogicalFactId: String? = null
			var afterSemanticRevision: Long? = null
			var previous: StepFactRevisionEntity? = null
			var pageSize: Int
			do {
				val page = readDao.canonicalStepFactTimelinePage(
					serviceRunIds = runIds,
					limit = READ_PAGE_SIZE,
					afterServiceRunId = afterServiceRunId,
					afterSourceAdmissionOrdinal = afterSourceAdmissionOrdinal,
					afterWriterProjectionId = afterWriterProjectionId,
					afterWriterProjectionVersion = afterWriterProjectionVersion,
					afterLogicalFactId = afterLogicalFactId,
					afterSemanticRevision = afterSemanticRevision,
				)
				val orderedPage = previous?.let { prior -> listOf(prior) + page } ?: page
				if (page.any { fact -> fact.serviceRunId !in runIds } ||
					orderedPage.zipWithNext().any { (left, right) ->
						!isTimelineFactAfter(right, left)
					}
				) {
					return false
				}
				for (fact in page) {
					val run = runsById[fact.serviceRunId] ?: return false
					val prior = previous?.takeIf { candidate ->
						candidate.serviceRunId == fact.serviceRunId
					}
					val chain = prior?.let { listOf(it, fact) } ?: listOf(fact)
					if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
							chain,
							run.logicalTrackingId,
							run.serviceRunId,
						)
					) {
						return false
					}
					previous = fact
				}
				val last = page.lastOrNull()
				afterServiceRunId = last?.serviceRunId
				afterSourceAdmissionOrdinal = last?.sourceAdmissionOrdinal
				afterWriterProjectionId = last?.writerProjectionId
				afterWriterProjectionVersion = last?.writerProjectionVersion
				afterLogicalFactId = last?.logicalFactId
				afterSemanticRevision = last?.semanticRevision
				pageSize = page.size
			} while (pageSize == READ_PAGE_SIZE)
		}
		return true
	}

	private fun isTimelineFactAfter(
		candidate: StepFactRevisionEntity,
		previous: StepFactRevisionEntity,
	): Boolean = when {
		candidate.serviceRunId != previous.serviceRunId ->
			requireNotNull(candidate.serviceRunId) > requireNotNull(previous.serviceRunId)
		candidate.sourceAdmissionOrdinal != previous.sourceAdmissionOrdinal ->
			requireNotNull(candidate.sourceAdmissionOrdinal) >
				requireNotNull(previous.sourceAdmissionOrdinal)
		candidate.writerProjectionId != previous.writerProjectionId ->
			candidate.writerProjectionId > previous.writerProjectionId
		candidate.writerProjectionVersion != previous.writerProjectionVersion ->
			candidate.writerProjectionVersion > previous.writerProjectionVersion
		candidate.logicalFactId != previous.logicalFactId ->
			candidate.logicalFactId > previous.logicalFactId
		else -> candidate.semanticRevision > previous.semanticRevision
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
			stepPolicies = snapshot.stepPolicies,
			stepCaptureConsents = snapshot.stepCaptureConsents,
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
			stepPolicies = snapshot.stepPolicies,
			stepCaptureConsents = snapshot.stepCaptureConsents,
		) ?: return UnboundRunQualification.Unverifiable
		// The candidate query already established civil relevance. Manifest membership is exact for
		// the run as a whole; a wall-clock jump must not select one revision over another.
		val relevantAuthorities = authorities.values.toList()
		if (relevantAuthorities.isEmpty()) {
			return UnboundRunQualification.Unverifiable
		}
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
		val contribution = SegmentContribution(
			logicalTrackingId = logicalTrackingId,
			logicalStartedAtMs = logicalSession.startedAtMs,
			zoneId = authorities.values.first().zoneId,
			segment = segment,
			allManifestRevisions = authorities.keys,
			stepsManifestRevisions = authorities.values.mapNotNullTo(linkedSetOf()) { authority ->
				authority.manifest.manifestRevision.takeIf { authority.stepsBinding != null }
			},
			hasManifestWithoutStepsCapture = authorities.values.any { authority ->
				authority.stepsBinding == null
			},
		)
		val completeness = context.completeness.filter { row ->
			row.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS
		}
		if (completeness.isEmpty() ||
			!StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				completeness,
				logicalTrackingId,
				serviceRunId,
			)
		) {
			return SegmentQualification.Unverifiable
		}
		if (completeness.any { row ->
			!row.appDrainComplete || row.lastAdmissionOrdinal == null ||
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
				val descriptor = candidateFactDescriptor(
					contribution = contribution,
					run = run,
					authorities = authorities,
					writer = writer,
					context = context,
					targetOrdinal = targetOrdinal,
					requiresCompleteEvidence = false,
				) ?: return SegmentQualification.Unverifiable
				materializing(
					logicalSession,
					authorities,
					descriptor,
				)
			} else {
				SegmentQualification.Unverifiable
			}
		}

		val terminalOrdinals = completeness.mapNotNull(
			SourceSessionCompletenessEntity::lastAdmissionOrdinal,
		)
		if (terminalOrdinals.distinct().size != completeness.size) {
			return SegmentQualification.Unverifiable
		}
		val descriptor = candidateFactDescriptor(
			contribution = contribution,
			run = run,
			authorities = authorities,
			writer = writer,
			context = context,
			targetOrdinal = targetOrdinal,
			terminalOrdinals = terminalOrdinals.toSet(),
			requiresCompleteEvidence = true,
		) ?: return SegmentQualification.Unverifiable
		return if (lifecycle == BoundLifecycle.SETTLED) {
			SegmentQualification.Ready(contribution, descriptor)
		} else {
			materializing(logicalSession, authorities, descriptor)
		}
	}

	private fun candidateFactDescriptor(
		contribution: SegmentContribution,
		run: SourceServiceRunEntity,
		authorities: Map<Long, ManifestAuthority>,
		writer: WriterBinding,
		context: CandidateAuthorityContext,
		targetOrdinal: Long,
		terminalOrdinals: Set<Long> = emptySet(),
		requiresCompleteEvidence: Boolean,
	): RunFactDescriptor.Candidate? {
		val writerBindingGeneration = writer.bindingGeneration ?: return null
		val orderedAuthorities = authorities.values.sortedBy { authority ->
			authority.manifest.manifestRevision
		}
		return RunFactDescriptor.Candidate(
			serviceRunId = run.serviceRunId,
			logicalTrackingId = contribution.logicalTrackingId,
			run = run,
			authorities = authorities,
			nextAuthorityByManifestRevision = orderedAuthorities.zipWithNext().associate { (current, next) ->
				current.manifest.manifestRevision to next
			},
			writerBindingGeneration = writerBindingGeneration,
			projectionId = context.projectionId,
			projectionVersion = context.projectionVersion,
			lane = context.lane,
			collectedDataEpoch = context.collectedDataEpoch,
			targetOrdinal = targetOrdinal,
			terminalOrdinals = terminalOrdinals,
			stepsManifestRevisions = contribution.stepsManifestRevisions,
			accumulatorContribution = contribution.toAccumulatorContribution(),
			requiresCompleteEvidence = requiresCompleteEvidence,
		)
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
		val retentionDigest = StepFactRevisionIntegrity.retentionTruncationIdentity(
			logicalTrackingId = logicalSession.logicalTrackingId,
			serviceRunId = run.serviceRunId,
		)
		if (retentionDigest in snapshot.retentionTruncationDigests) {
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
		val stepsCompleteness = completeness.filter { row ->
			row.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS
		}
		if (!StepsSessionCompletenessIntegrity.hasValidTimeline(
				stepsCompleteness,
				logicalSession.logicalTrackingId,
				run.serviceRunId,
			)
		) {
			return null
		}
		val lane = snapshot.lanes[LaneKey(bindingGeneration, projectionId, projectionVersion)]
			?: return null
		val requiredCaptureModeMask = historicalCaptureModeMask(authorities) ?: return null
		val stepsAuthorities = authorities.values.filter { authority -> authority.stepsBinding != null }
		if (stepsAuthorities.isEmpty() || !laneExecutionAuthority.owns(lane) ||
			!validHistoricalLane(lane) ||
			lane.captureModeMask and requiredCaptureModeMask == 0L ||
			stepsAuthorities.any { authority ->
				lane.activatedRolloutRevision > authority.manifest.rolloutRevision
			}
		) {
			return null
		}
		return CandidateAuthorityContext(
			projectionId = projectionId,
			projectionVersion = projectionVersion,
			lane = lane,
			completeness = completeness,
			collectedDataEpoch = evidenceState.collectedDataEpoch,
		)
	}

	/** Adds exact run and manifest authority to the shared source-local fact contract. */
	@Suppress("CyclomaticComplexMethod", "ReturnCount")
	private fun validLiveWalFactSemantics(
		state: StepFactRevisionEntity,
		run: SourceServiceRunEntity,
		authority: ManifestAuthority,
		nextAuthority: ManifestAuthority?,
	): Boolean {
		if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(state) ||
			state.clockDomainId != run.bootId || state.bootClockDomainId != run.bootId ||
			authority.manifest.effectiveBootId != run.bootId
		) {
			return false
		}
		val startElapsed = state.intervalStartElapsedRealtimeNanos ?: return false
		val endElapsed = state.intervalEndElapsedRealtimeNanos ?: return false
		if (startElapsed < run.startedElapsedNanos ||
			startElapsed < authority.manifest.effectiveElapsedRealtimeNanos ||
			endElapsed < authority.manifest.effectiveElapsedRealtimeNanos
		) {
			return false
		}
		val nextEffective = nextAuthority?.manifest?.effectiveElapsedRealtimeNanos
		return nextEffective == null ||
			(startElapsed < nextEffective && endElapsed < nextEffective)
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
					allManifestRevisions = authorities.keys,
					stepsManifestRevisions = emptySet(),
					hasManifestWithoutStepsCapture = true,
				)
				val descriptor = RunFactDescriptor.MustBeEmpty(serviceRunId)
				return if (lifecycle == BoundLifecycle.SETTLED) {
					SegmentQualification.Ready(contribution, descriptor)
				} else {
					materializing(logicalSession, authorities, descriptor)
				}
			}
		}
		return SegmentQualification.Unverifiable
	}

	private fun materializing(
		logicalSession: LogicalTrackingSessionEntity,
		authorities: Map<Long, ManifestAuthority>,
		factDescriptor: RunFactDescriptor? = null,
	) = SegmentQualification.Materializing(
		authority = LogicalContributionAuthority(
			logicalTrackingId = logicalSession.logicalTrackingId,
			logicalStartedAtMs = logicalSession.startedAtMs,
			zoneId = authorities.values.first().zoneId,
		),
		factDescriptor = factDescriptor,
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
			group.map(SegmentContribution::logicalStartedAtMs).distinct().size != 1 ||
			!SessionManifestIntegrity.hasValidLogicalManifestRevisionSliceUnion(
				group.map(SegmentContribution::allManifestRevisions),
			)
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
		return false
	}

	private fun hasForbiddenCaptureOverlap(
		contributions: List<SegmentContribution>,
		unboundNonStepsCaptures: List<UnboundNonStepsCapture>,
		rejectCrossLogicalOverlap: Boolean,
	): Boolean {
		val intervals = contributions.map { contribution ->
			LogicalCaptureInterval(
				logicalTrackingId = contribution.logicalTrackingId,
				startMs = contribution.segment.startTimeMs,
				endMs = contribution.segment.endTimeMs,
			)
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

	private fun addFactDescriptor(
		descriptors: MutableMap<String, RunFactDescriptor>,
		descriptor: RunFactDescriptor?,
	): Boolean {
		descriptor ?: return true
		return descriptors.putIfAbsent(descriptor.serviceRunId, descriptor) == null
	}

	private fun SegmentContribution.toAccumulatorContribution() = StepsNumericRunContribution(
		serviceRunId = segment.serviceRunId.orEmpty(),
		logicalTrackingId = logicalTrackingId,
		logicalStartedAtMs = logicalStartedAtMs,
		capturedZoneId = zoneId,
		segmentStartMs = segment.startTimeMs,
		segmentEndMs = segment.endTimeMs,
		distanceM = segment.distanceM,
		stepsManifestRevisions = stepsManifestRevisions,
		hasManifestWithoutStepsCapture = hasManifestWithoutStepsCapture,
	)

	private suspend fun streamFactStatePages(
		descriptors: List<RunFactDescriptor>,
		accumulator: StepsNumericDayWindowAccumulator,
	): Boolean {
		val ordered = descriptors.sortedBy(RunFactDescriptor::serviceRunId)
		if (ordered.zipWithNext().any { (left, right) -> left.serviceRunId >= right.serviceRunId }) {
			return false
		}
		for (batch in ordered.chunked(QUERY_ID_BATCH_SIZE)) {
			if (!streamFactStateBatch(batch, accumulator)) {
				return false
			}
		}
		return true
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun streamFactStateBatch(
		descriptors: List<RunFactDescriptor>,
		accumulator: StepsNumericDayWindowAccumulator,
	): Boolean {
		if (descriptors.isEmpty()) {
			return true
		}
		val serviceRunIds = descriptors.map(RunFactDescriptor::serviceRunId)
		val serviceRunIdSet = serviceRunIds.toHashSet()
		val readDao = database.trackingHistoryReadDao()
		var descriptorIndex = 0
		var currentState: CandidateFactStreamState? = null
		var afterServiceRunId: String? = null
		var afterFirstIntervalStartTimeMs: Long? = null
		var afterLogicalFactId: String? = null
		var afterWriterProjectionId: String? = null
		var afterWriterProjectionVersion: Int? = null
		var previous: ScopedStepFactState? = null
		var pageSize: Int
		do {
			val page = readDao.stepFactStatePage(
				serviceRunIds = serviceRunIds,
				limit = READ_PAGE_SIZE,
				afterServiceRunId = afterServiceRunId,
				afterFirstIntervalStartTimeMs = afterFirstIntervalStartTimeMs,
				afterLogicalFactId = afterLogicalFactId,
				afterWriterProjectionId = afterWriterProjectionId,
				afterWriterProjectionVersion = afterWriterProjectionVersion,
			)
			val orderedPage = previous?.let { listOf(it) + page } ?: page
			if (page.any { row ->
					row.serviceRunId !in serviceRunIdSet || row.serviceRunId.isBlank() ||
						row.state.logicalFactId.isBlank()
				} || orderedPage.zipWithNext().any { (left, right) ->
					!isFactStateAfter(right, left)
				}
			) {
				return false
			}
			for (row in page) {
				while (descriptorIndex < descriptors.size &&
					descriptors[descriptorIndex].serviceRunId < row.serviceRunId
				) {
					if (!finishFactDescriptor(
							descriptors[descriptorIndex],
							currentState,
							accumulator,
						)
					) {
						return false
					}
					currentState = null
					descriptorIndex += 1
				}
				val descriptor = descriptors.getOrNull(descriptorIndex) ?: return false
				if (descriptor.serviceRunId != row.serviceRunId || descriptor is RunFactDescriptor.MustBeEmpty) {
					return false
				}
				val candidate = descriptor as RunFactDescriptor.Candidate
				val state = currentState ?: startCandidateFactStream(candidate, accumulator)
					?: return false
				if (state.descriptor != candidate || !consumeCandidateFact(row, state, accumulator)) {
					return false
				}
				currentState = state
			}
			val last = page.lastOrNull()
			previous = last ?: previous
			afterServiceRunId = last?.serviceRunId
			afterFirstIntervalStartTimeMs = last?.firstIntervalStartTimeMs
			afterLogicalFactId = last?.state?.logicalFactId
			afterWriterProjectionId = last?.state?.writerProjectionId
			afterWriterProjectionVersion = last?.state?.writerProjectionVersion
			pageSize = page.size
		} while (pageSize == READ_PAGE_SIZE)
		while (descriptorIndex < descriptors.size) {
			if (!finishFactDescriptor(descriptors[descriptorIndex], currentState, accumulator)) {
				return false
			}
			currentState = null
			descriptorIndex += 1
		}
		return true
	}

	private fun startCandidateFactStream(
		descriptor: RunFactDescriptor.Candidate,
		accumulator: StepsNumericDayWindowAccumulator,
	): CandidateFactStreamState? {
		if (!accumulator.startRun(descriptor.accumulatorContribution)) {
			return null
		}
		return CandidateFactStreamState(
			descriptor = descriptor,
			terminalCoveredCounts = descriptor.terminalOrdinals.associateWith { 0 }.toMutableMap(),
		)
	}

	@Suppress("CyclomaticComplexMethod")
	private fun finishFactDescriptor(
		descriptor: RunFactDescriptor,
		state: CandidateFactStreamState?,
		accumulator: StepsNumericDayWindowAccumulator,
	): Boolean = when (descriptor) {
		is RunFactDescriptor.MustBeEmpty -> state == null
		is RunFactDescriptor.Candidate -> when {
			state == null -> !descriptor.requiresCompleteEvidence
			state.descriptor != descriptor || !accumulator.finishRun() -> false
			!descriptor.requiresCompleteEvidence -> true
			else -> state.sawState && state.sawCovered &&
				state.terminalCoveredCounts.values.all { count -> count == 1 } &&
				state.coveredManifestRevisions.containsAll(descriptor.stepsManifestRevisions)
		}
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun consumeCandidateFact(
		scoped: ScopedStepFactState,
		stream: CandidateFactStreamState,
		accumulator: StepsNumericDayWindowAccumulator,
	): Boolean {
		val descriptor = stream.descriptor
		val authority = descriptor.authorities[scoped.manifestRevision] ?: return false
		val nextAuthority = descriptor.nextAuthorityByManifestRevision[scoped.manifestRevision]
		val binding = authority.stepsBinding ?: return false
		val state = scoped.state
		val admissionOrdinal = state.sourceAdmissionOrdinal ?: return false
		if (scoped.logicalTrackingId != descriptor.logicalTrackingId ||
			scoped.writerBindingGeneration != descriptor.writerBindingGeneration ||
			scoped.firstIntervalStartTimeMs != state.intervalStartTimeMs ||
			state.writerProjectionId != descriptor.projectionId ||
			state.writerProjectionVersion != descriptor.projectionVersion ||
			state.writerBindingGeneration != descriptor.writerBindingGeneration ||
			state.operation != StepFactRevisionEntity.OPERATION_UPSERT ||
			state.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL ||
			admissionOrdinal < descriptor.lane.activationOrdinal ||
			admissionOrdinal > descriptor.targetOrdinal ||
			descriptor.lane.captureAdmissionCutoffOrdinal?.let { cutoff ->
				admissionOrdinal > cutoff
			} == true ||
			state.logicalTrackingId != descriptor.logicalTrackingId ||
			state.serviceRunId != descriptor.serviceRunId ||
			state.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
			state.manifestRevision != authority.manifest.manifestRevision ||
			state.sourcePolicyRevision != authority.manifest.sourcePolicyRevision ||
			state.captureConsentEpoch != binding.consentEpoch ||
			state.collectedDataEpoch != descriptor.collectedDataEpoch ||
			state.coverageKind in UNSAFE_COVERAGE_KINDS ||
			!validLiveWalFactSemantics(state, descriptor.run, authority, nextAuthority)
		) {
			return false
		}
		stream.sawState = true
		if (state.coverageKind != StepFactRevisionEntity.COVERAGE_COVERED) {
			return true
		}
		val startMs = state.intervalStartTimeMs ?: return false
		val endMs = state.intervalEndTimeMs ?: return false
		val count = state.effectiveStepCount ?: return false
		val uncertaintyMs = state.wallTimeUncertaintyMs ?: return false
		if (endMs <= startMs || count < 0L) {
			return false
		}
		stream.sawCovered = true
		stream.coveredManifestRevisions += scoped.manifestRevision
		stream.terminalCoveredCounts[admissionOrdinal]?.let { currentCount ->
			stream.terminalCoveredCounts[admissionOrdinal] = currentCount + 1
		}
		return accumulator.consumeCoveredFact(
			StepsNumericCoveredFact(
				serviceRunId = descriptor.serviceRunId,
				manifestRevision = scoped.manifestRevision,
				startMs = startMs,
				endMs = endMs,
				steps = count,
				wallTimeUncertaintyMs = uncertaintyMs,
			),
		)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun manifestAuthorities(
		logicalSession: LogicalTrackingSessionEntity,
		run: SourceServiceRunEntity,
		manifests: List<SessionManifestVersionEntity>,
		sourcesByManifest: Map<ManifestKey, List<SessionManifestSourceEntity>>,
		stepPolicies: Map<Long, SourcePolicyEntity>,
		stepCaptureConsents: Map<Long, SourceConsentEpochEntity>,
	): Map<Long, ManifestAuthority>? {
		val logicalTrackingId = logicalSession.logicalTrackingId
		val serviceRunId = run.serviceRunId
		val ordered = manifests.sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (!SessionManifestIntegrity.hasValidServiceRunTimeline(run, ordered)) {
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
		for (manifest in ordered) {
			if (manifest.logicalTrackingId != logicalTrackingId || manifest.serviceRunId != serviceRunId ||
				manifest.rolloutRevision != logicalSession.rolloutRevision ||
				manifest.sessionMode != logicalSession.sessionMode ||
				manifest.effectiveBootId != run.bootId
			) {
				return null
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
			val stepsBinding = stepsSources.singleOrNull()
			if (stepsBinding != null && !StepFactRevisionIntegrity.hasValidStepsCaptureAuthority(
					policy = stepPolicies[manifest.sourcePolicyRevision],
					consent = stepCaptureConsents[stepsBinding.consentEpoch],
					manifestPolicyRevision = manifest.sourcePolicyRevision,
					binding = stepsBinding,
				)
			) {
				return null
			}
			val zoneId = try {
				ZoneId.of(manifest.zoneId)
			} catch (_: DateTimeException) {
				return null
			}
			authorities[manifest.manifestRevision] = ManifestAuthority(
				manifest = manifest,
				stepsBinding = stepsBinding,
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

	/** Untyped daily_summary cannot preserve the uncertainty carried by PartialCapture. */
	private fun StepsDayRepairPreflight.requirePersistableNumericSteps(): StepsDayRepairPreflight =
		when (this) {
			is StepsDayRepairPreflight.Ready -> if (
				plans.any { plan ->
					plan.numericSteps == StepsDayNumericComposition.PartialCapture
				}
			) {
				unverifiable()
			} else {
				this
			}
			else -> this
		}

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
		val allManifestRevisions: Set<Long>,
		val stepsManifestRevisions: Set<Long>,
		val hasManifestWithoutStepsCapture: Boolean,
	)
	private data class LogicalContributionAuthority(
		val logicalTrackingId: String,
		val logicalStartedAtMs: Long,
		val zoneId: ZoneId,
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
	private data class CandidateAuthorityContext(
		val projectionId: String,
		val projectionVersion: Int,
		val lane: SourceProductProjectionLaneEntity,
		val completeness: List<SourceSessionCompletenessEntity>,
		val collectedDataEpoch: Long,
	)
	private sealed interface RunFactDescriptor {
		val serviceRunId: String

		data class Candidate(
			override val serviceRunId: String,
			val logicalTrackingId: String,
			val run: SourceServiceRunEntity,
			val authorities: Map<Long, ManifestAuthority>,
			val nextAuthorityByManifestRevision: Map<Long, ManifestAuthority>,
			val writerBindingGeneration: Long,
			val projectionId: String,
			val projectionVersion: Int,
			val lane: SourceProductProjectionLaneEntity,
			val collectedDataEpoch: Long,
			val targetOrdinal: Long,
			val terminalOrdinals: Set<Long>,
			val stepsManifestRevisions: Set<Long>,
			val accumulatorContribution: StepsNumericRunContribution,
			val requiresCompleteEvidence: Boolean,
		) : RunFactDescriptor

		data class MustBeEmpty(
			override val serviceRunId: String,
		) : RunFactDescriptor
	}
	private data class CandidateFactStreamState(
		val descriptor: RunFactDescriptor.Candidate,
		val terminalCoveredCounts: MutableMap<Long, Int>,
		val coveredManifestRevisions: MutableSet<Long> = hashSetOf(),
		var sawState: Boolean = false,
		var sawCovered: Boolean = false,
	)
	private data class RepairSnapshot(
		val logicalSessions: Map<String, LogicalTrackingSessionEntity>,
		val serviceRuns: Map<String, SourceServiceRunEntity>,
		val manifestsByRun: Map<String, List<SessionManifestVersionEntity>>,
		val sourcesByManifest: Map<ManifestKey, List<SessionManifestSourceEntity>>,
		val stepPolicies: Map<Long, SourcePolicyEntity>,
		val stepCaptureConsents: Map<Long, SourceConsentEpochEntity>,
		val completenessByRun: Map<String, List<SourceSessionCompletenessEntity>>,
		val lanes: Map<LaneKey, SourceProductProjectionLaneEntity>,
		val deletionFenceDigests: Set<String>,
		val retentionTruncationDigests: Set<String>,
		val failures: List<SourceProjectionFailureEntity>,
		val evidenceState: SourceEvidenceState?,
	)
	private sealed interface SegmentQualification {
		data class Ready(
			val contribution: SegmentContribution,
			val factDescriptor: RunFactDescriptor?,
		) : SegmentQualification
		data class Materializing(
			val authority: LogicalContributionAuthority,
			val factDescriptor: RunFactDescriptor?,
		) : SegmentQualification
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
		val DELETABLE_SOURCE_KINDS = setOf(
			SourceDestinationOwnerEntity.SOURCE_STEPS,
			SourceDestinationOwnerEntity.SOURCE_PRESSURE,
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
