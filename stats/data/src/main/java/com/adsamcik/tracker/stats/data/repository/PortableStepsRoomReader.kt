package com.adsamcik.tracker.stats.data.repository

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ScopedStepFactState
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestIntegrity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.StepFactRevisionEntity
import com.adsamcik.tracker.shared.preferences.tracking.TrackingSourceComponent
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsRequest
import com.adsamcik.tracker.stats.api.repository.ExportPortableStepsResult
import com.adsamcik.tracker.stats.api.repository.PORTABLE_STEPS_ENTRY_ORDER
import com.adsamcik.tracker.stats.api.repository.PORTABLE_STEPS_FACT_ORDER
import com.adsamcik.tracker.stats.api.repository.PORTABLE_STEPS_RUN_ORDER
import com.adsamcik.tracker.stats.api.repository.PortableStepsCaptureCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsCompletenessV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsDeletionScopeDigest
import com.adsamcik.tracker.stats.api.repository.PortableStepsEntryV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsExportUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsFactV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsIdentityKind
import com.adsamcik.tracker.stats.api.repository.PortableStepsManifestV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PortableStepsProviderCoverage
import com.adsamcik.tracker.stats.api.repository.PortableStepsRunV1
import com.adsamcik.tracker.stats.api.repository.PortableStepsSessionMode
import com.adsamcik.tracker.stats.api.repository.StepsPortableFormatV1
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Complete immutable result of one bounded Room export preflight. */
internal sealed interface PortableStepsSnapshot {
	data class Ready(val entries: List<PortableStepsEntryV1>) : PortableStepsSnapshot {
		init {
			require(entries.isNotEmpty())
			require(entries == entries.sortedWith(PORTABLE_STEPS_ENTRY_ORDER))
		}
	}

	data class Outcome(val result: ExportPortableStepsResult) : PortableStepsSnapshot {
		init {
			require(result !is ExportPortableStepsResult.Exported)
		}
	}
}

/**
 * Bounded read-only adapter from exact v28 Steps facts to the portable v1 wire domain.
 *
 * Every dependency is captured and validated in one Room reader transaction. No caller sink or
 * external I/O is reachable from this class.
 */
@Singleton
@Suppress("LargeClass", "LongParameterList", "TooManyFunctions")
internal class PortableStepsRoomReader @Inject constructor(
	private val database: AppDatabase,
	private val stepsSelector: StepsSegmentHistorySelector,
) {
	suspend fun read(request: ExportPortableStepsRequest): PortableStepsSnapshot =
		database.useReaderConnection { connection ->
			connection.deferredTransaction {
				try {
					readInTransaction(request)
				} catch (abort: PortableSnapshotAbort) {
					PortableStepsSnapshot.Outcome(
						ExportPortableStepsResult.Unverifiable(abort.reason),
					)
				}
			}
		}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun readInTransaction(
		request: ExportPortableStepsRequest,
	): PortableStepsSnapshot {
		val budget = SnapshotBudget()
		val logicalTrackingIds = discoverLogicalEntries(request, budget)
		if (logicalTrackingIds.isEmpty()) {
			return noEntries()
		}
		val serviceRuns = loadReplacementRuns(logicalTrackingIds, budget)
		if (serviceRuns.isEmpty()) {
			return noEntries()
		}
		val segmentIds = serviceRuns.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		val segments = loadSegments(segmentIds, budget)
		val logicalSessions = loadLogicalSessions(logicalTrackingIds, budget)
		val dependencies = loadDependencies(serviceRuns, budget)
		val snapshot = dependencies.toHistorySnapshot(serviceRuns)
		val segmentsById = segments.associateBy(SessionSegment::id)
		val runsByLogical = serviceRuns.groupBy(SourceServiceRunEntity::logicalTrackingId)
		val evidenceBySegmentId = stepsSelector.selectManyWithSnapshot(
			segments = segments.sortedBy(SessionSegment::id),
			snapshot = snapshot,
		).associateBy { evidence -> evidence.segment.id }

		val candidateLogicalIds = serviceRuns.asSequence()
			.filter { run ->
				dependencies.hasStepsCaptureMembership(run) ||
					dependencies.factStatesByRun[run.serviceRunId].orEmpty().any { scoped ->
						scoped.state.operation == StepFactRevisionEntity.OPERATION_UPSERT &&
							scoped.state.coverageKind == StepFactRevisionEntity.COVERAGE_COVERED
					}
			}
			.map(SourceServiceRunEntity::logicalTrackingId)
			.toSet()
		if (candidateLogicalIds.isEmpty()) {
			return noEntries()
		}

		val entries = candidateLogicalIds.mapNotNull { logicalTrackingId ->
			currentCoroutineContext().ensureActive()
			buildEntry(
				logicalTrackingId = logicalTrackingId,
				request = request,
				logicalSession = logicalSessions[logicalTrackingId],
				serviceRuns = runsByLogical[logicalTrackingId].orEmpty(),
				segmentsById = segmentsById,
				evidenceBySegmentId = evidenceBySegmentId,
				dependencies = dependencies,
			)
		}.sortedWith(PORTABLE_STEPS_ENTRY_ORDER)
		if (entries.isEmpty()) {
			return noEntries()
		}
		if (entries.size > StepsPortableFormatV1.MAX_ENTRIES) {
			abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		return PortableStepsSnapshot.Ready(entries)
	}

	private suspend fun discoverLogicalEntries(
		request: ExportPortableStepsRequest,
		budget: SnapshotBudget,
	): List<String> {
		val identities = linkedSetOf<String>()
		discoverSegmentLogicalEntries(request, budget, identities)
		discoverServiceRunLogicalEntries(request, budget, identities)
		return identities.toList()
	}

	private suspend fun discoverSegmentLogicalEntries(
		request: ExportPortableStepsRequest,
		budget: SnapshotBudget,
		identities: MutableSet<String>,
	) {
		var afterStartTimeMs: Long? = null
		var afterSegmentId: Long? = null
		var previous: SessionSegment? = null
		do {
			currentCoroutineContext().ensureActive()
			val page = database.sessionSegmentDao().sourceRepairSegmentPage(
				fromMs = request.fromInclusiveMs,
				toMs = request.toExclusiveMs,
				excludedSegmentId = null,
				limit = READ_PAGE_SIZE,
				afterStartTimeMs = afterStartTimeMs,
				afterSegmentId = afterSegmentId,
			)
			validateSegmentPage(page, previous)
			page.forEach { segment ->
				val logicalTrackingId = segment.logicalTrackingId?.takeIf(String::isNotBlank)
				val serviceRunId = segment.serviceRunId?.takeIf(String::isNotBlank)
				if (logicalTrackingId != null && serviceRunId != null && identities.add(logicalTrackingId)) {
					budget.consume(1)
					if (identities.size > StepsPortableFormatV1.MAX_ENTRIES) {
						abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
					}
				}
			}
			previous = page.lastOrNull() ?: previous
			afterStartTimeMs = page.lastOrNull()?.startTimeMs
			afterSegmentId = page.lastOrNull()?.id
		} while (page.size == READ_PAGE_SIZE)
	}

	private suspend fun discoverServiceRunLogicalEntries(
		request: ExportPortableStepsRequest,
		budget: SnapshotBudget,
		identities: MutableSet<String>,
	) {
		var afterStartedAtMs: Long? = null
		var afterServiceRunId: String? = null
		var previous: SourceServiceRunEntity? = null
		do {
			currentCoroutineContext().ensureActive()
			val page = database.trackingHistoryReadDao().serviceRunCandidatePage(
				fromMs = request.fromInclusiveMs,
				toMs = request.toExclusiveMs,
				limit = READ_PAGE_SIZE,
				afterStartedAtMs = afterStartedAtMs,
				afterServiceRunId = afterServiceRunId,
			)
			validateCandidateRunPage(page, previous)
			page.forEach { run ->
				val logicalTrackingId = run.logicalTrackingId.takeIf(String::isNotBlank)
				if (logicalTrackingId != null && identities.add(logicalTrackingId)) {
					budget.consume(1)
					if (identities.size > StepsPortableFormatV1.MAX_ENTRIES) {
						abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
					}
				}
			}
			previous = page.lastOrNull() ?: previous
			afterStartedAtMs = page.lastOrNull()?.startedAtMs
			afterServiceRunId = page.lastOrNull()?.serviceRunId
		} while (page.size == READ_PAGE_SIZE)
	}

	private suspend fun loadReplacementRuns(
		logicalTrackingIds: List<String>,
		budget: SnapshotBudget,
	): List<SourceServiceRunEntity> {
		val result = mutableListOf<SourceServiceRunEntity>()
		for (logicalIdBatch in logicalTrackingIds.chunked(QUERY_ID_BATCH_SIZE)) {
			var afterLogicalTrackingId: String? = null
			var afterStartedAtMs: Long? = null
			var afterServiceRunId: String? = null
			var previous: SourceServiceRunEntity? = null
			do {
				currentCoroutineContext().ensureActive()
				val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
					logicalTrackingIds = logicalIdBatch,
					limit = READ_PAGE_SIZE,
					afterLogicalTrackingId = afterLogicalTrackingId,
					afterStartedAtMs = afterStartedAtMs,
					afterServiceRunId = afterServiceRunId,
				)
				validateServiceRunPage(page, previous, logicalIdBatch.toSet())
				budget.consume(page.size)
				result += page
				previous = page.lastOrNull() ?: previous
				afterLogicalTrackingId = page.lastOrNull()?.logicalTrackingId
				afterStartedAtMs = page.lastOrNull()?.startedAtMs
				afterServiceRunId = page.lastOrNull()?.serviceRunId
			} while (page.size == READ_PAGE_SIZE)
		}
		if (result.map(SourceServiceRunEntity::serviceRunId).distinct().size != result.size) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		return result
	}

	private suspend fun loadSegments(
		segmentIds: List<Long>,
		budget: SnapshotBudget,
	): List<SessionSegment> {
		val result = segmentIds.chunked(QUERY_ID_BATCH_SIZE).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			database.trackingHistoryReadDao().segments(ids).also { budget.consume(it.size) }
		}
		if (result.any { it.id !in segmentIds } || result.map(SessionSegment::id).distinct().size != result.size) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		return result
	}

	private suspend fun loadLogicalSessions(
		logicalTrackingIds: List<String>,
		budget: SnapshotBudget,
	): Map<String, LogicalTrackingSessionEntity> {
		val result = logicalTrackingIds.chunked(QUERY_ID_BATCH_SIZE).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			database.sourceSessionDao().sessions(ids).also { budget.consume(it.size) }
		}
		if (result.map(LogicalTrackingSessionEntity::logicalTrackingId).distinct().size != result.size) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		return result.associateBy(LogicalTrackingSessionEntity::logicalTrackingId)
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private suspend fun loadDependencies(
		serviceRuns: List<SourceServiceRunEntity>,
		budget: SnapshotBudget,
	): PortableRoomDependencies {
		val readDao = database.trackingHistoryReadDao()
		val manifests = mutableListOf<SessionManifestVersionEntity>()
		val sources = mutableListOf<SessionManifestSourceEntity>()
		val policies = linkedMapOf<Long, SourcePolicyEntity>()
		val completeness = mutableListOf<SourceSessionCompletenessEntity>()
		val lanes = linkedMapOf<HistoricalLaneKey, SourceProductProjectionLaneEntity>()
		val runIds = serviceRuns.map(SourceServiceRunEntity::serviceRunId)
		for (ids in runIds.chunked(QUERY_ID_BATCH_SIZE)) {
			currentCoroutineContext().ensureActive()
			val manifestBatch = readDao.manifests(ids, budget.queryLimit())
			budget.consume(manifestBatch.size)
			manifests += manifestBatch
			val sourceBatch = readDao.manifestSources(ids, budget.queryLimit())
			budget.consume(sourceBatch.size)
			sources += sourceBatch
			val completenessBatch = readDao.completeness(ids, budget.queryLimit())
			budget.consume(completenessBatch.size)
			completeness += completenessBatch
			readDao.policiesForServiceRuns(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				serviceRunIds = ids,
			).forEach { policy ->
				if (policies.putIfAbsent(policy.policyRevision, policy) == null) budget.consume(1)
			}
			readDao.productLanesForServiceRuns(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				serviceRunIds = ids,
			).forEach { lane ->
				val key = HistoricalLaneKey(
					bindingGeneration = lane.bindingGeneration,
					projectionId = lane.projectionId,
					projectionVersion = lane.projectionVersion,
				)
				val prior = lanes.putIfAbsent(key, lane)
				if (prior == null) budget.consume(1) else if (prior != lane) {
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
				}
			}
		}
		if (manifests.groupingBy(SessionManifestVersionEntity::serviceRunId).eachCount()
			.values.any { it > StepsPortableFormatV1.MAX_MANIFESTS_PER_RUN }
		) {
			abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}

		val factStates = loadFactStatePages(runIds, budget)
		val upsertRevisions = loadUpsertRevisionPages(runIds, budget)
		val scopeDigests = serviceRuns.map { run -> scopeDigest(run) }
		val fences = mutableListOf<SourceDeletionFenceEntity>()
		for (digests in scopeDigests.chunked(QUERY_ID_BATCH_SIZE)) {
			val batch = readDao.deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = digests,
			)
			budget.consume(batch.size)
			fences += batch
		}
		if (fences.map(SourceDeletionFenceEntity::scopeIdentityDigest).distinct().size != fences.size) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}

		val failures = loadTerminalFailures(
			runIds = runIds,
			lanes = lanes.values.toList(),
			completeness = completeness,
			budget = budget,
		)
		val evidenceState = database.sourceEvidenceStateDao().get()
			?: abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		budget.consume(1)
		return PortableRoomDependencies(
			manifests = manifests,
			sources = sources,
			policies = policies.values.toList(),
			completeness = completeness,
			lanes = lanes.values.toList(),
			factStates = factStates,
			upsertRevisions = upsertRevisions,
			fences = fences,
			terminalFailures = failures,
			evidenceState = evidenceState,
		)
	}

	private suspend fun loadFactStatePages(
		serviceRunIds: List<String>,
		budget: SnapshotBudget,
	): List<ScopedStepFactState> {
		val result = mutableListOf<ScopedStepFactState>()
		val countByRun = mutableMapOf<String, Int>()
		for (ids in serviceRunIds.chunked(QUERY_ID_BATCH_SIZE)) {
			var afterServiceRunId: String? = null
			var afterFirstIntervalStartTimeMs: Long? = null
			var afterLogicalFactId: String? = null
			var afterWriterProjectionId: String? = null
			var afterWriterProjectionVersion: Int? = null
			var previous: ScopedStepFactState? = null
			do {
				currentCoroutineContext().ensureActive()
				val page = database.trackingHistoryReadDao().stepFactStatePage(
					serviceRunIds = ids,
					limit = READ_PAGE_SIZE,
					afterServiceRunId = afterServiceRunId,
					afterFirstIntervalStartTimeMs = afterFirstIntervalStartTimeMs,
					afterLogicalFactId = afterLogicalFactId,
					afterWriterProjectionId = afterWriterProjectionId,
					afterWriterProjectionVersion = afterWriterProjectionVersion,
				)
				validateFactStatePage(page, previous, ids.toSet())
				page.forEach { row ->
					val count = countByRun.getOrDefault(row.serviceRunId, 0) + 1
					if (count > StepsPortableFormatV1.MAX_FACTS_PER_RUN) {
						abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
					}
					countByRun[row.serviceRunId] = count
				}
				budget.consume(page.size)
				result += page
				previous = page.lastOrNull() ?: previous
				afterServiceRunId = page.lastOrNull()?.serviceRunId
				afterFirstIntervalStartTimeMs = page.lastOrNull()?.firstIntervalStartTimeMs
				afterLogicalFactId = page.lastOrNull()?.state?.logicalFactId
				afterWriterProjectionId = page.lastOrNull()?.state?.writerProjectionId
				afterWriterProjectionVersion = page.lastOrNull()?.state?.writerProjectionVersion
			} while (page.size == READ_PAGE_SIZE)
		}
		return result
	}

	private suspend fun loadUpsertRevisionPages(
		serviceRunIds: List<String>,
		budget: SnapshotBudget,
	): List<StepFactRevisionEntity> {
		val result = mutableListOf<StepFactRevisionEntity>()
		val countByRun = mutableMapOf<String, Int>()
		for (ids in serviceRunIds.chunked(QUERY_ID_BATCH_SIZE)) {
			var afterServiceRunId: String? = null
			var afterWriterProjectionId: String? = null
			var afterWriterProjectionVersion: Int? = null
			var afterLogicalFactId: String? = null
			var afterSemanticRevision: Long? = null
			var previous: StepFactRevisionEntity? = null
			do {
				currentCoroutineContext().ensureActive()
				val page = database.trackingHistoryReadDao().stepFactUpsertRevisionPage(
					serviceRunIds = ids,
					capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
					limit = READ_PAGE_SIZE,
					afterServiceRunId = afterServiceRunId,
					afterWriterProjectionId = afterWriterProjectionId,
					afterWriterProjectionVersion = afterWriterProjectionVersion,
					afterLogicalFactId = afterLogicalFactId,
					afterSemanticRevision = afterSemanticRevision,
				)
				validateUpsertPage(page, previous, ids.toSet())
				page.forEach { row ->
					val runId = requireNotNull(row.serviceRunId)
					val count = countByRun.getOrDefault(runId, 0) + 1
					if (count > MAX_HISTORICAL_UPSERT_REVISIONS_PER_RUN) {
						abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
					}
					countByRun[runId] = count
				}
				budget.consume(page.size)
				result += page
				previous = page.lastOrNull() ?: previous
				afterServiceRunId = page.lastOrNull()?.serviceRunId
				afterWriterProjectionId = page.lastOrNull()?.writerProjectionId
				afterWriterProjectionVersion = page.lastOrNull()?.writerProjectionVersion
				afterLogicalFactId = page.lastOrNull()?.logicalFactId
				afterSemanticRevision = page.lastOrNull()?.semanticRevision
			} while (page.size == READ_PAGE_SIZE)
		}
		return result
	}

	private suspend fun loadTerminalFailures(
		runIds: List<String>,
		lanes: List<SourceProductProjectionLaneEntity>,
		completeness: List<SourceSessionCompletenessEntity>,
		budget: SnapshotBudget,
	): List<SourceProjectionFailureEntity> {
		val afterOrdinal = lanes.mapNotNull { lane ->
			lane.activationOrdinal.takeIf { it > 0L }?.minus(1L)
		}.minOrNull() ?: return emptyList()
		val throughOrdinal = completeness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal)
			.maxOrNull() ?: return emptyList()
		if (afterOrdinal >= throughOrdinal) return emptyList()
		val failures = linkedSetOf<SourceProjectionFailureEntity>()
		for (ids in runIds.chunked(QUERY_ID_BATCH_SIZE)) {
			val page = database.trackingHistoryReadDao().terminalFailuresForServiceRuns(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				serviceRunIds = ids,
				afterOrdinal = afterOrdinal,
				throughOrdinal = throughOrdinal,
				limit = MAX_TERMINAL_FAILURES + 1,
			)
			if (page.size > MAX_TERMINAL_FAILURES) {
				abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			page.forEach { failure ->
				if (failures.add(failure)) {
					budget.consume(1)
					if (failures.size > MAX_TERMINAL_FAILURES) {
						abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
					}
				}
			}
		}
		return failures.toList()
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private fun buildEntry(
		logicalTrackingId: String,
		request: ExportPortableStepsRequest,
		logicalSession: LogicalTrackingSessionEntity?,
		serviceRuns: List<SourceServiceRunEntity>,
		segmentsById: Map<Long, SessionSegment>,
		evidenceBySegmentId: Map<Long, HistoricalSegmentEvidence>,
		dependencies: PortableRoomDependencies,
	): PortableStepsEntryV1? {
		if (serviceRuns.size > StepsPortableFormatV1.MAX_RUNS_PER_ENTRY) {
			abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}
		val session = logicalSession
			?: abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (session.logicalTrackingId != logicalTrackingId) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val sessionMode = sessionMode(session.sessionMode)
		val evidenceState = dependencies.evidenceState
		val surviving = mutableListOf<BoundPortableRun>()
		var missingRunMaterializing = false
		for (run in serviceRuns) {
			if (run.logicalTrackingId != logicalTrackingId || run.serviceRunId.isBlank()) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			val digest = scopeDigest(run)
			val fence = dependencies.fencesByDigest[digest]
			val segment = run.sessionSegmentId?.let(segmentsById::get)
			if (segment == null) {
				if (fence != null) {
					if (fence.collectedDataEpoch != evidenceState.collectedDataEpoch) {
						abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
					}
					continue
				}
				if (runCanStillMaterialize(run)) {
					missingRunMaterializing = true
					continue
				}
				abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
			if (segment.logicalTrackingId != logicalTrackingId ||
				segment.serviceRunId != run.serviceRunId || run.sessionSegmentId != segment.id
			) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			surviving += BoundPortableRun(run, segment, fence)
		}
		if (surviving.isEmpty()) {
			if (missingRunMaterializing) {
				abort(PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING)
			}
			return null
		}
		val entryStartMs = surviving.minOf { it.segment.startTimeMs }
		val entryEndMs = surviving.maxOf { it.segment.endTimeMs }
		if (entryStartMs >= request.toExclusiveMs || entryEndMs <= request.fromInclusiveMs) return null
		val retainedFromMs = evidenceState.retainedFromMs
		if (retainedFromMs != null && entryEndMs <= retainedFromMs) return null
		if (retainedFromMs != null && entryStartMs < retainedFromMs && entryEndMs > retainedFromMs) {
			abort(PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY)
		}
		if (surviving.any { it.fence != null }) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}

		var materializing = missingRunMaterializing || logicalSessionMaterializing(session)
		val portableRuns = surviving.map { bound ->
			when (runSettlement(bound.run)) {
				RunSettlement.SETTLED -> Unit
				RunSettlement.MATERIALIZING -> materializing = true
				RunSettlement.INVALID ->
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
			val evidence = evidenceBySegmentId[bound.segment.id]
				?: abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			val capture = evidence.captureAuthority as? HistoricalCaptureAuthority.Exact
				?: abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			if (TrackingSourceComponent.STEPS !in capture.capturedInAnyRevision) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			when (evidence.steps.materialization) {
				StepsHistoryMaterialization.MATERIALIZING -> materializing = true
				StepsHistoryMaterialization.READY -> if (
					evidence.steps.availability != StepsHistoryAvailability.AVAILABLE
				) {
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
				}
				else -> abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
			buildRun(bound, sessionMode, dependencies)
		}.sortedWith(PORTABLE_STEPS_RUN_ORDER)
		if (materializing) {
			abort(PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
		if (portableRuns.none { run ->
			run.facts.any { fact -> fact.coverage == PortableStepsFactCoverage.COVERED }
		}) return null
		return portableValue {
			PortableStepsEntryV1.create(
				identity = PortableStepsOpaqueIdentity.derive(
					PortableStepsIdentityKind.LOGICAL_ENTRY,
					logicalTrackingId,
				),
				sessionMode = sessionMode,
				startTimeMs = portableRuns.minOf(PortableStepsRunV1::startTimeMs),
				endTimeMs = portableRuns.maxOf(PortableStepsRunV1::endTimeMs),
				runs = portableRuns,
			)
		}
	}

	@Suppress("CyclomaticComplexMethod", "LongMethod")
	private fun buildRun(
		bound: BoundPortableRun,
		sessionMode: PortableStepsSessionMode,
		dependencies: PortableRoomDependencies,
	): PortableStepsRunV1 {
		val run = bound.run
		val segment = bound.segment
		val manifests = dependencies.manifestsByRun[run.serviceRunId].orEmpty()
		if (manifests.isEmpty()) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val stepsBindings = linkedMapOf<Long, SessionManifestSourceEntity>()
		val portableManifests = mutableListOf<PortableStepsManifestV1>()
		val zoneIds = linkedSetOf<String>()
		for (manifest in manifests.sortedBy(SessionManifestVersionEntity::manifestRevision)) {
			if (manifest.logicalTrackingId != run.logicalTrackingId ||
				manifest.serviceRunId != run.serviceRunId || manifest.sessionMode != sessionMode.name ||
				manifest.effectiveWallTimeMs !in segment.startTimeMs..segment.endTimeMs
			) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			val sources = dependencies.sourcesByManifest[ManifestKey(
				manifest.logicalTrackingId,
				manifest.manifestRevision,
			)].orEmpty()
			if (!SessionManifestIntegrity.verify(manifest, sources) || sources.any { source ->
				source.logicalTrackingId != run.logicalTrackingId ||
					source.manifestRevision != manifest.manifestRevision ||
					source.purpose !in SessionManifestPurposeCode.ALL ||
					runCatching { TrackingSourceComponent.fromStableCode(source.sourceKind) }.isFailure
			}) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			zoneIds += manifest.zoneId
			val binding = sources.singleOrNull { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
					source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}
			if (binding != null) {
				val policy = dependencies.policiesByRevision[manifest.sourcePolicyRevision]
				if (policy == null || policy.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
					!policy.enabled || !policy.capturePersistenceEligible ||
					policy.captureConsentEpoch != binding.consentEpoch ||
					policy.qosCode != binding.qosCode
				) {
					abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
				}
				stepsBindings[manifest.manifestRevision] = binding
				portableManifests += PortableStepsManifestV1(
					revision = manifest.manifestRevision,
					effectiveWallTimeMs = manifest.effectiveWallTimeMs,
					originSourcePolicyRevision = manifest.sourcePolicyRevision,
					captureConsentEpoch = binding.consentEpoch,
				)
			}
		}
		if (stepsBindings.isEmpty() || zoneIds.size != 1) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val writer = stepsBindings.values.map(::writerBinding).distinct().singleOrNull()
			?: abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (writer.owner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS ||
			writer.ownerGeneration == null || writer.ownerGeneration <= 0L ||
			writer.projectionId == null || writer.projectionVersion == null ||
			writer.bindingGeneration == null
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}

		val historicalUpserts = dependencies.upsertsByRun[run.serviceRunId].orEmpty().filter { fact ->
			fact.writerProjectionId == writer.projectionId &&
				fact.writerProjectionVersion == writer.projectionVersion &&
				fact.writerBindingGeneration == writer.bindingGeneration
		}
		historicalUpserts.forEach { fact ->
			if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			validateUpsertAttribution(fact, run, segment, manifests, stepsBindings, writer, dependencies)
		}

		val factStates = dependencies.factStatesByRun[run.serviceRunId].orEmpty().filter { scoped ->
			scoped.state.writerProjectionId == writer.projectionId &&
				scoped.state.writerProjectionVersion == writer.projectionVersion &&
				scoped.writerBindingGeneration == writer.bindingGeneration
		}
		val portableFacts = factStates.mapNotNull { scoped ->
			validateScopedAttribution(scoped, run, manifests, stepsBindings, writer, dependencies)
			when (scoped.state.operation) {
				StepFactRevisionEntity.OPERATION_RETRACT -> null
				StepFactRevisionEntity.OPERATION_UPSERT -> portableFact(scoped.state)
				else -> abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
		}.sortedWith(PORTABLE_STEPS_FACT_ORDER)
		val completeness = portableCompleteness(
			run = run,
			captureCoveredWholeRun = stepsBindings.size == manifests.size,
			dependencies = dependencies,
		)
		return portableValue {
			PortableStepsRunV1(
				identity = PortableStepsOpaqueIdentity.derive(
					PortableStepsIdentityKind.PHYSICAL_RUN,
					run.serviceRunId,
				),
				deletionScopeDigest = PortableStepsDeletionScopeDigest.derive(
					logicalTrackingId = run.logicalTrackingId,
					serviceRunId = run.serviceRunId,
				),
				startTimeMs = segment.startTimeMs,
				endTimeMs = segment.endTimeMs,
				storedZoneId = zoneIds.single(),
				manifests = portableManifests,
				completeness = completeness,
				facts = portableFacts,
			)
		}
	}

	@Suppress("ComplexCondition")
	private fun validateUpsertAttribution(
		fact: StepFactRevisionEntity,
		run: SourceServiceRunEntity,
		segment: SessionSegment,
		manifests: List<SessionManifestVersionEntity>,
		stepsBindings: Map<Long, SessionManifestSourceEntity>,
		writer: PortableWriterBinding,
		dependencies: PortableRoomDependencies,
	) {
		val manifestRevision = fact.manifestRevision
		val manifest = manifests.singleOrNull { it.manifestRevision == manifestRevision }
		val binding = stepsBindings[manifestRevision]
		if (manifest == null || binding == null || fact.logicalTrackingId != run.logicalTrackingId ||
			fact.serviceRunId != run.serviceRunId ||
			fact.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
			fact.writerProjectionId != writer.projectionId ||
			fact.writerProjectionVersion != writer.projectionVersion ||
			fact.writerBindingGeneration != writer.bindingGeneration ||
			fact.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			fact.captureConsentEpoch != binding.consentEpoch ||
			fact.collectedDataEpoch != dependencies.evidenceState.collectedDataEpoch ||
			requireNotNull(fact.intervalStartTimeMs) < segment.startTimeMs ||
			requireNotNull(fact.intervalEndTimeMs) > segment.endTimeMs
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
	}

	@Suppress("ComplexCondition")
	private fun validateScopedAttribution(
		scoped: ScopedStepFactState,
		run: SourceServiceRunEntity,
		manifests: List<SessionManifestVersionEntity>,
		stepsBindings: Map<Long, SessionManifestSourceEntity>,
		writer: PortableWriterBinding,
		dependencies: PortableRoomDependencies,
	) {
		val binding = stepsBindings[scoped.manifestRevision]
		if (scoped.logicalTrackingId != run.logicalTrackingId ||
			scoped.serviceRunId != run.serviceRunId || binding == null ||
			manifests.none { it.manifestRevision == scoped.manifestRevision } ||
			scoped.writerBindingGeneration != writer.bindingGeneration ||
			scoped.state.writerProjectionId != writer.projectionId ||
			scoped.state.writerProjectionVersion != writer.projectionVersion ||
			scoped.state.collectedDataEpoch != dependencies.evidenceState.collectedDataEpoch
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
	}

	private fun portableFact(fact: StepFactRevisionEntity): PortableStepsFactV1 = portableValue {
		val coverage = when (fact.coverageKind) {
			StepFactRevisionEntity.COVERAGE_BASELINE -> PortableStepsFactCoverage.BASELINE
			StepFactRevisionEntity.COVERAGE_COVERED -> PortableStepsFactCoverage.COVERED
			StepFactRevisionEntity.COVERAGE_RESET_GAP -> PortableStepsFactCoverage.RESET_GAP
			StepFactRevisionEntity.COVERAGE_PARTIAL -> PortableStepsFactCoverage.PARTIAL
			else -> abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		PortableStepsFactV1.create(
			identity = PortableStepsOpaqueIdentity.derive(
				PortableStepsIdentityKind.FACT,
				factIdentity(fact),
			),
			manifestRevision = requireNotNull(fact.manifestRevision),
			intervalStartTimeMs = requireNotNull(fact.intervalStartTimeMs),
			intervalEndTimeMs = requireNotNull(fact.intervalEndTimeMs),
			wallTimeUncertaintyMs = requireNotNull(fact.wallTimeUncertaintyMs),
			coverage = coverage,
			stepCount = fact.effectiveStepCount.takeIf {
				coverage == PortableStepsFactCoverage.COVERED
			},
		)
	}

	private fun portableCompleteness(
		run: SourceServiceRunEntity,
		captureCoveredWholeRun: Boolean,
		dependencies: PortableRoomDependencies,
	): PortableStepsCompletenessV1 {
		val rows = dependencies.completenessByRun[run.serviceRunId].orEmpty().filter { row ->
			row.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS
		}
		if (rows.isEmpty() || rows.any { row ->
			row.logicalTrackingId != run.logicalTrackingId || row.sourceInstanceId.isBlank() ||
				row.registrationGeneration <= 0L || row.updatedAtMs < 0L ||
				(row.unresolvedSequenceStart == null) != (row.unresolvedSequenceEnd == null) ||
				row.providerCoverage !in PROVIDER_COVERAGE_VALUES ||
				row.stopStatus !in STOP_STATUS_VALUES
		}) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		val providerCoverage = when {
			rows.all { it.providerCoverage == COMPLETE_PROVIDER_COVERAGE } ->
				PortableStepsProviderCoverage.COMPLETE
			rows.all { it.providerCoverage == UNOBSERVABLE_PROVIDER_COVERAGE } ->
				PortableStepsProviderCoverage.UNOBSERVABLE
			else -> PortableStepsProviderCoverage.PARTIAL
		}
		return PortableStepsCompletenessV1(
			captureCoverage = if (captureCoveredWholeRun) {
				PortableStepsCaptureCoverage.WHOLE_RUN
			} else {
				PortableStepsCaptureCoverage.PARTIAL
			},
			providerCoverage = providerCoverage,
			appDrainComplete = rows.all(SourceSessionCompletenessEntity::appDrainComplete),
			stopComplete = rows.all { it.stopStatus == COMPLETE_STOP_STATUS },
			hasUnresolvedProviderRange = rows.any { it.unresolvedSequenceStart != null },
		)
	}

	private fun sessionMode(value: String): PortableStepsSessionMode = when (value) {
		PortableStepsSessionMode.MANUAL.name -> PortableStepsSessionMode.MANUAL
		PortableStepsSessionMode.AUTOMATIC.name -> PortableStepsSessionMode.AUTOMATIC
		else -> abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}

	@Suppress("ComplexCondition")
	private fun logicalSessionMaterializing(session: LogicalTrackingSessionEntity): Boolean {
		if (session.logicalTrackingId.isBlank() || session.startedAtMs < 0L ||
			session.sessionMode !in EXACT_SESSION_MODES
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		return when (session.state) {
			"FINALIZED", "FAILED" -> {
				if (session.completedAtMs == null || session.currentServiceRunId != null) {
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
				}
				false
			}
			"STARTING", "ACTIVE", "RECONFIGURING", "STOPPING" -> {
				if (session.completedAtMs != null) {
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
				}
				true
			}
			else -> abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
	}

	private fun runSettlement(run: SourceServiceRunEntity): RunSettlement {
		val completedAtMs = run.completedAtMs
		return when (run.state) {
			"FINALIZED", "FAILED" -> when {
				completedAtMs == null || completedAtMs < run.startedAtMs -> RunSettlement.INVALID
				run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_QUIESCED ->
					RunSettlement.SETTLED
				run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING ->
					RunSettlement.MATERIALIZING
				else -> RunSettlement.INVALID
			}
			"STARTING", "ACTIVE", "STOPPING" -> if (
				completedAtMs == null &&
				run.presentationAcknowledgement == SourceServiceRunEntity.PRESENTATION_PENDING
			) {
				RunSettlement.MATERIALIZING
			} else {
				RunSettlement.INVALID
			}
			else -> RunSettlement.INVALID
		}
	}

	private fun runCanStillMaterialize(run: SourceServiceRunEntity): Boolean =
		runSettlement(run) == RunSettlement.MATERIALIZING

	private fun writerBinding(source: SessionManifestSourceEntity) = PortableWriterBinding(
		owner = source.writerOwner.orEmpty(),
		ownerGeneration = source.writerOwnerGeneration,
		projectionId = source.writerProjectionId,
		projectionVersion = source.writerProjectionVersion,
		bindingGeneration = source.writerBindingGeneration,
	)

	private fun factIdentity(fact: StepFactRevisionEntity): String = buildString {
		append(fact.writerProjectionId.length)
		append(':')
		append(fact.writerProjectionId)
		append(':')
		append(fact.writerProjectionVersion)
		append(':')
		append(fact.logicalFactId.length)
		append(':')
		append(fact.logicalFactId)
	}

	private fun scopeDigest(run: SourceServiceRunEntity): String =
		SourceDeletionFenceEntity.logicalServiceRunIdentity(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			logicalTrackingId = run.logicalTrackingId,
			serviceRunId = run.serviceRunId,
		)

	private inline fun <T> portableValue(block: () -> T): T = try {
		block()
	} catch (abort: PortableSnapshotAbort) {
		throw abort
	} catch (_: IllegalArgumentException) {
		abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
	}

	private fun validateSegmentPage(page: List<SessionSegment>, previous: SessionSegment?) {
		val ordered = previous?.let { listOf(it) + page } ?: page
		if (page.any { it.id <= 0L } || ordered.zipWithNext().any { (left, right) ->
			right.startTimeMs < left.startTimeMs ||
				right.startTimeMs == left.startTimeMs && right.id <= left.id
		}) abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun validateServiceRunPage(
		page: List<SourceServiceRunEntity>,
		previous: SourceServiceRunEntity?,
		expectedLogicalIds: Set<String>,
	) {
		val ordered = previous?.let { listOf(it) + page } ?: page
		if (page.any { it.logicalTrackingId !in expectedLogicalIds || it.serviceRunId.isBlank() } ||
			ordered.zipWithNext().any { (left, right) -> !serviceRunAfter(right, left) }
		) abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun validateCandidateRunPage(
		page: List<SourceServiceRunEntity>,
		previous: SourceServiceRunEntity?,
	) {
		val ordered = previous?.let { listOf(it) + page } ?: page
		if (page.any { it.serviceRunId.isBlank() } || ordered.zipWithNext().any { (left, right) ->
			right.startedAtMs < left.startedAtMs ||
				right.startedAtMs == left.startedAtMs && right.serviceRunId <= left.serviceRunId
		}) abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun serviceRunAfter(
		candidate: SourceServiceRunEntity,
		previous: SourceServiceRunEntity,
	): Boolean = when {
		candidate.logicalTrackingId != previous.logicalTrackingId ->
			candidate.logicalTrackingId > previous.logicalTrackingId
		candidate.startedAtMs != previous.startedAtMs -> candidate.startedAtMs > previous.startedAtMs
		else -> candidate.serviceRunId > previous.serviceRunId
	}

	private fun validateFactStatePage(
		page: List<ScopedStepFactState>,
		previous: ScopedStepFactState?,
		expectedRunIds: Set<String>,
	) {
		val ordered = previous?.let { listOf(it) + page } ?: page
		if (page.any { it.serviceRunId !in expectedRunIds } ||
			ordered.zipWithNext().any { (left, right) -> !factStateAfter(right, left) }
		) abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun factStateAfter(candidate: ScopedStepFactState, previous: ScopedStepFactState): Boolean =
		when {
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

	private fun validateUpsertPage(
		page: List<StepFactRevisionEntity>,
		previous: StepFactRevisionEntity?,
		expectedRunIds: Set<String>,
	) {
		val ordered = previous?.let { listOf(it) + page } ?: page
		if (page.any { it.serviceRunId !in expectedRunIds } ||
			ordered.zipWithNext().any { (left, right) -> !upsertAfter(right, left) }
		) abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
	}

	private fun upsertAfter(
		candidate: StepFactRevisionEntity,
		previous: StepFactRevisionEntity,
	): Boolean = when {
		candidate.serviceRunId != previous.serviceRunId ->
			requireNotNull(candidate.serviceRunId) > requireNotNull(previous.serviceRunId)
		candidate.writerProjectionId != previous.writerProjectionId ->
			candidate.writerProjectionId > previous.writerProjectionId
		candidate.writerProjectionVersion != previous.writerProjectionVersion ->
			candidate.writerProjectionVersion > previous.writerProjectionVersion
		candidate.logicalFactId != previous.logicalFactId ->
			candidate.logicalFactId > previous.logicalFactId
		else -> candidate.semanticRevision > previous.semanticRevision
	}

	private fun noEntries() = PortableStepsSnapshot.Outcome(ExportPortableStepsResult.NoEntries)

	private fun abort(reason: PortableStepsExportUnverifiableReason): Nothing =
		throw PortableSnapshotAbort(reason)

	private data class BoundPortableRun(
		val run: SourceServiceRunEntity,
		val segment: SessionSegment,
		val fence: SourceDeletionFenceEntity?,
	)

	private data class PortableWriterBinding(
		val owner: String,
		val ownerGeneration: Long?,
		val projectionId: String?,
		val projectionVersion: Int?,
		val bindingGeneration: Long?,
	)

	private enum class RunSettlement { SETTLED, MATERIALIZING, INVALID }

	private class SnapshotBudget {
		private var consumed = 0

		fun consume(count: Int) {
			require(count >= 0)
			if (count > MAX_SNAPSHOT_DEPENDENCIES - consumed) {
				throw PortableSnapshotAbort(
					PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW,
				)
			}
			consumed += count
		}

		fun queryLimit(): Int = MAX_SNAPSHOT_DEPENDENCIES - consumed + 1
	}

	private class PortableSnapshotAbort(
		val reason: PortableStepsExportUnverifiableReason,
	) : RuntimeException(null, null, false, false)

	private companion object {
		const val READ_PAGE_SIZE = 256
		const val QUERY_ID_BATCH_SIZE = 400
		const val MAX_SNAPSHOT_DEPENDENCIES = 16_384
		const val MAX_HISTORICAL_UPSERT_REVISIONS_PER_RUN = 2_048
		const val MAX_TERMINAL_FAILURES = 2_048
		const val COMPLETE_PROVIDER_COVERAGE = "CALLBACKS_ENTERED_BEFORE_BARRIER"
		const val UNOBSERVABLE_PROVIDER_COVERAGE = "PROVIDER_COMPLETENESS_UNOBSERVABLE"
		const val COMPLETE_STOP_STATUS = "COMPLETE"
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
		val EXACT_SESSION_MODES = PortableStepsSessionMode.entries.mapTo(hashSetOf()) { it.name }
	}
}

private data class PortableRoomDependencies(
	val manifests: List<SessionManifestVersionEntity>,
	val sources: List<SessionManifestSourceEntity>,
	val policies: List<SourcePolicyEntity>,
	val completeness: List<SourceSessionCompletenessEntity>,
	val lanes: List<SourceProductProjectionLaneEntity>,
	val factStates: List<ScopedStepFactState>,
	val upsertRevisions: List<StepFactRevisionEntity>,
	val fences: List<SourceDeletionFenceEntity>,
	val terminalFailures: List<SourceProjectionFailureEntity>,
	val evidenceState: SourceEvidenceState,
) {
	val manifestsByRun = manifests.groupBy(SessionManifestVersionEntity::serviceRunId)
	val sourcesByManifest = sources.groupBy {
		ManifestKey(it.logicalTrackingId, it.manifestRevision)
	}
	val policiesByRevision = policies.associateBy(SourcePolicyEntity::policyRevision)
	val completenessByRun = completeness.groupBy(SourceSessionCompletenessEntity::serviceRunId)
	val factStatesByRun = factStates.groupBy(ScopedStepFactState::serviceRunId)
	val upsertsByRun = upsertRevisions.groupBy { fact -> requireNotNull(fact.serviceRunId) }
	val fencesByDigest = fences.associateBy(SourceDeletionFenceEntity::scopeIdentityDigest)
	val lanesByKey = lanes.associateBy {
		HistoricalLaneKey(it.bindingGeneration, it.projectionId, it.projectionVersion)
	}

	fun hasStepsCaptureMembership(run: SourceServiceRunEntity): Boolean {
		val runManifestKeys = manifestsByRun[run.serviceRunId].orEmpty().mapTo(hashSetOf()) { manifest ->
			ManifestKey(manifest.logicalTrackingId, manifest.manifestRevision)
		}
		return runManifestKeys.any { key ->
			sourcesByManifest[key].orEmpty().any { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
					source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}
		}
	}

	fun toHistorySnapshot(serviceRuns: List<SourceServiceRunEntity>): StepsHistoryBatchSnapshot =
		StepsHistoryBatchSnapshot(
			serviceRuns = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId),
			manifestsByRun = manifestsByRun,
			sourcesByManifest = sourcesByManifest,
			stepPolicies = policiesByRevision,
			deletionFenceDigests = fencesByDigest.keys,
			factStatesByRun = factStatesByRun,
			completenessByRun = completenessByRun,
			productLanes = lanesByKey,
			terminalFailures = terminalFailures,
			dependencyOverflow = null,
			evidenceState = evidenceState,
		)
}
