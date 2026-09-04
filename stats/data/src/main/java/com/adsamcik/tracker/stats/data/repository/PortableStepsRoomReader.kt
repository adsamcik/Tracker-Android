package com.adsamcik.tracker.stats.data.repository

import androidx.room.deferredTransaction
import androidx.room.useReaderConnection
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ScopedStepFactState
import com.adsamcik.tracker.shared.base.database.dao.StepsFactCandidateState
import com.adsamcik.tracker.shared.base.database.dao.hasValidStepsFactCandidateState
import com.adsamcik.tracker.shared.base.database.dao.loadStepsFactCandidateStatesByRun
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
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
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
				} catch (_: IllegalArgumentException) {
					// Room entity invariants are part of the untrusted retained snapshot. Convert only
					// that validation family at this read boundary; cancellation and database failures
					// continue to propagate to their existing owners.
					PortableStepsSnapshot.Outcome(
						ExportPortableStepsResult.Unverifiable(
							PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE,
						),
					)
				}
			}
		}

	@Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
	private suspend fun readInTransaction(
		request: ExportPortableStepsRequest,
	): PortableStepsSnapshot {
		val budget = SnapshotBudget()
		val discovery = discoverLogicalEntries(request, budget)
		val logicalTrackingIds = discovery.logicalTrackingIds
		if (logicalTrackingIds.isEmpty()) {
			return noEntries()
		}
		val serviceRuns = loadReplacementRuns(logicalTrackingIds, budget)
		if (serviceRuns.isEmpty()) {
			if (discovery.segments.isNotEmpty()) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			return noEntries()
		}
		val segmentIds = serviceRuns.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		val segments = loadSegments(segmentIds, budget)
		val segmentsById = segments.associateBy(SessionSegment::id)
		validateDiscoveredSegmentBindings(
			serviceRuns = serviceRuns,
			discoveredSegments = discovery.segments,
			segmentsById = segmentsById,
		)
		val logicalSessions = loadLogicalSessions(logicalTrackingIds, budget)
		val dependencies = loadDependencies(serviceRuns, budget)
		val snapshot = dependencies.toHistorySnapshot(serviceRuns)
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
	): PortableEntryDiscovery {
		val identities = linkedSetOf<String>()
		val segments = mutableListOf<DiscoveredSegmentIdentity>()
		discoverSegmentLogicalEntries(request, budget, identities, segments)
		discoverServiceRunLogicalEntries(request, budget, identities)
		return PortableEntryDiscovery(identities.toList(), segments)
	}

	private suspend fun discoverSegmentLogicalEntries(
		request: ExportPortableStepsRequest,
		budget: SnapshotBudget,
		identities: MutableSet<String>,
		segments: MutableList<DiscoveredSegmentIdentity>,
	) {
		var afterStartTimeMs: Long? = null
		var afterSegmentId: Long? = null
		var previous: SessionSegment? = null
		do {
			currentCoroutineContext().ensureActive()
			val page = database.trackingHistoryReadDao().portableStepsSegmentCandidatePage(
				fromMs = request.fromInclusiveMs,
				toMs = request.toExclusiveMs,
				stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				limit = READ_PAGE_SIZE,
				afterStartTimeMs = afterStartTimeMs,
				afterSegmentId = afterSegmentId,
			)
			validateSegmentPage(page, previous)
			budget.consume(page.size)
			page.forEach { segment ->
				val logicalTrackingId = segment.logicalTrackingId
				val serviceRunId = segment.serviceRunId
				if ((logicalTrackingId == null) != (serviceRunId == null)) {
					abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
				}
				if (logicalTrackingId?.isBlank() == true || serviceRunId?.isBlank() == true) {
					abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
				}
				if (logicalTrackingId != null && serviceRunId != null) {
					segments += DiscoveredSegmentIdentity(segment.id, logicalTrackingId, serviceRunId)
					identities += logicalTrackingId
				}
				if (identities.size > StepsPortableFormatV1.MAX_ENTRIES) {
					abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
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
			val page = database.trackingHistoryReadDao().portableStepsServiceRunCandidatePage(
				fromMs = request.fromInclusiveMs,
				toMs = request.toExclusiveMs,
				stepsSourceKind = SourceDestinationOwnerEntity.SOURCE_STEPS,
				capturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				limit = READ_PAGE_SIZE,
				afterStartedAtMs = afterStartedAtMs,
				afterServiceRunId = afterServiceRunId,
			)
			validateCandidateRunPage(page, previous)
			budget.consume(page.size)
			page.forEach { run ->
				val logicalTrackingId = run.logicalTrackingId.takeIf(String::isNotBlank)
				if (logicalTrackingId != null) {
					identities += logicalTrackingId
				}
				if (identities.size > StepsPortableFormatV1.MAX_ENTRIES) {
					abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
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

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
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
				if (policies.putIfAbsent(policy.policyRevision, policy) == null) {
					budget.consume(1)
				}
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
				if (prior == null) {
					budget.consume(1)
				} else if (prior != lane) {
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
				}
			}
		}
		if (manifests.groupingBy(SessionManifestVersionEntity::serviceRunId).eachCount()
			.values.any { it > StepsPortableFormatV1.MAX_MANIFESTS_PER_RUN }
		) {
			abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
		}

		val historyFactStatesByRun = readDao.loadStepsFactCandidateStatesByRun(
			serviceRuns = serviceRuns,
			onPageLoaded = budget::consume,
		)
		val serviceRunsById = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId)
		if (historyFactStatesByRun.any { (runId, states) ->
				val run = serviceRunsById[runId]
				states.any { scoped ->
					val scope = scoped.scopeCarrier
					!hasValidStepsFactCandidateState(scoped) || run == null || scope == null ||
						scope.serviceRunId != runId ||
						scope.logicalTrackingId != run.logicalTrackingId
				}
			}
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val selectedUpsertRevisions = historyFactStatesByRun.values.asSequence()
			.flatten()
			.map { scoped -> requireNotNull(scoped.scopeCarrier) }
			.distinctBy(::factKey)
			.toList()
		val factLineages = loadFactLineagePages(selectedUpsertRevisions, budget)
		val factStates = composeFactStates(factLineages, runIds.toSet())
		val upsertRevisions = factLineages.filter { revision ->
			revision.operation == StepFactRevisionEntity.OPERATION_UPSERT
		}
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
		val fencesByDigest = fences.associateBy(SourceDeletionFenceEntity::scopeIdentityDigest)
		if (historyFactStatesByRun.any { (runId, states) ->
				val run = requireNotNull(serviceRunsById[runId])
				val fence = fencesByDigest[scopeDigest(run)]
				states.any { scoped ->
					val state = requireNotNull(scoped.state)
					state.operation == StepFactRevisionEntity.OPERATION_RETRACT &&
						(fence == null || state.scopeDeletionGeneration != fence.fenceGeneration ||
							state.collectedDataEpoch != fence.collectedDataEpoch)
				}
			}
		) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
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
		}.also { rows -> budget.consume(rows.size) }

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
			consents = consents,
			completeness = completeness,
			lanes = lanes.values.toList(),
			historyFactStatesByRun = historyFactStatesByRun,
			factStates = factStates,
			upsertRevisions = upsertRevisions,
			fences = fences,
			terminalFailures = failures,
			evidenceState = evidenceState,
		)
	}

	@Suppress("NestedBlockDepth")
	private suspend fun loadFactLineagePages(
		selectedUpserts: List<StepFactRevisionEntity>,
		budget: SnapshotBudget,
	): List<StepFactRevisionEntity> {
		if (selectedUpserts.isEmpty()) {
			return emptyList()
		}
		val selectedRunsByFact = selectedUpserts.groupBy(::factKey).mapValues { (_, revisions) ->
			revisions.mapTo(linkedSetOf()) { revision -> requireNotNull(revision.serviceRunId) }
		}
		val result = mutableListOf<StepFactRevisionEntity>()
		val lineageCountBySelectedRun = mutableMapOf<String, Int>()
		val writerGroups = selectedRunsByFact.keys.groupBy(::writerKey).entries.sortedWith(
			compareBy({ it.key.projectionId }, { it.key.projectionVersion }),
		)
		for ((writer, factKeys) in writerGroups) {
			for (logicalFactIds in factKeys.map(PortableFactKey::logicalFactId).sorted()
				.chunked(QUERY_ID_BATCH_SIZE)
			) {
				var afterLogicalFactId: String? = null
				var afterSemanticRevision: Long? = null
				var previous: StepFactRevisionEntity? = null
				do {
					currentCoroutineContext().ensureActive()
					val page = database.trackingHistoryReadDao().portableStepFactLineagePage(
						writerProjectionId = writer.projectionId,
						writerProjectionVersion = writer.projectionVersion,
						logicalFactIds = logicalFactIds,
						limit = READ_PAGE_SIZE,
						afterLogicalFactId = afterLogicalFactId,
						afterSemanticRevision = afterSemanticRevision,
					)
					validateFactLineagePage(page, previous, writer, logicalFactIds.toSet())
					page.asSequence()
						.filter { revision ->
							revision.operation == StepFactRevisionEntity.OPERATION_UPSERT
						}
						.forEach { revision ->
							selectedRunsByFact.getValue(factKey(revision)).forEach { selectedRunId ->
								val count = lineageCountBySelectedRun.getOrDefault(
									selectedRunId,
									0,
								) + 1
								if (count > MAX_HISTORICAL_UPSERT_REVISIONS_PER_RUN) {
									abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
								}
								lineageCountBySelectedRun[selectedRunId] = count
							}
						}
					budget.consume(page.size)
					result += page
					previous = page.lastOrNull() ?: previous
					afterLogicalFactId = page.lastOrNull()?.logicalFactId
					afterSemanticRevision = page.lastOrNull()?.semanticRevision
				} while (page.size == READ_PAGE_SIZE)
			}
		}
		if (result.mapTo(hashSetOf(), ::factKey) != selectedRunsByFact.keys) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		return result
	}

	private fun composeFactStates(
		factLineages: List<StepFactRevisionEntity>,
		selectedRunIds: Set<String>,
	): List<ScopedStepFactState> {
		val upserts = factLineages.filter { revision ->
			revision.operation == StepFactRevisionEntity.OPERATION_UPSERT
		}
		if (upserts.any { revision -> revision.serviceRunId !in selectedRunIds }) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val states = factLineages.groupBy(::factKey).map { (_, revisions) ->
			val latestUpsert = revisions.lastOrNull { revision ->
				revision.operation == StepFactRevisionEntity.OPERATION_UPSERT
			} ?: abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			ScopedStepFactState(
				serviceRunId = requireNotNull(latestUpsert.serviceRunId),
				logicalTrackingId = requireNotNull(latestUpsert.logicalTrackingId),
				manifestRevision = requireNotNull(latestUpsert.manifestRevision),
				writerBindingGeneration = latestUpsert.writerBindingGeneration,
				firstIntervalStartTimeMs = requireNotNull(latestUpsert.intervalStartTimeMs),
				state = revisions.last(),
			)
		}
		val countByRun = mutableMapOf<String, Int>()
		states.forEach { state ->
			val count = countByRun.getOrDefault(state.serviceRunId, 0) + 1
			if (count > StepsPortableFormatV1.MAX_FACTS_PER_RUN) {
				abort(PortableStepsExportUnverifiableReason.DEPENDENCY_OVERFLOW)
			}
			countByRun[state.serviceRunId] = count
		}
		return states.sortedWith(
			compareBy(
				ScopedStepFactState::serviceRunId,
				ScopedStepFactState::firstIntervalStartTimeMs,
				{ state -> state.state.logicalFactId },
				{ state -> state.state.writerProjectionId },
				{ state -> state.state.writerProjectionVersion },
			),
		)
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
		if (afterOrdinal >= throughOrdinal) {
			return emptyList()
		}
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
		if (!SessionManifestIntegrity.hasValidLogicalManifestRevisionUnion(
				serviceRuns.map { run ->
					dependencies.manifestsByRun[run.serviceRunId].orEmpty()
						.map(SessionManifestVersionEntity::manifestRevision)
				},
			)
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val sessionMode = sessionMode(session.sessionMode)
		val evidenceState = dependencies.evidenceState
		val surviving = mutableListOf<BoundPortableRun>()
		var deletedRunCount = 0
		var missingRunMaterializing = false
		for (run in serviceRuns) {
			if (run.logicalTrackingId != logicalTrackingId || run.serviceRunId.isBlank()) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			val digest = scopeDigest(run)
			val fence = dependencies.fencesByDigest[digest]
			if (fence != null) {
				validateDeletedRunFence(run, fence, evidenceState, dependencies)
				deletedRunCount += 1
				continue
			}
			val segment = run.sessionSegmentId?.let(segmentsById::get)
			if (segment == null) {
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
			surviving += BoundPortableRun(run, segment)
		}
		if (deletedRunCount > 0 && deletedRunCount != serviceRuns.size) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		if (surviving.isEmpty()) {
			if (missingRunMaterializing) {
				abort(PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING)
			}
			return null
		}
		val entryStartMs = surviving.minOf { bound -> bound.portableEnvelope().startTimeMs }
		val entryEndMs = surviving.maxOf { bound -> bound.portableEnvelope().endTimeMs }
		if (entryStartMs >= request.toExclusiveMs || entryEndMs <= request.fromInclusiveMs) {
			return null
		}
		val retainedFromMs = evidenceState.retainedFromMs
		if (retainedFromMs != null && entryEndMs < retainedFromMs) {
			return null
		}
		if (retainedFromMs != null && entryStartMs < retainedFromMs && entryEndMs >= retainedFromMs) {
			abort(PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY)
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
			val portableRun = buildRun(bound, sessionMode, dependencies)
			when (evidence.steps.materialization) {
				StepsHistoryMaterialization.MATERIALIZING -> materializing = true
				StepsHistoryMaterialization.READY -> if (
					evidence.steps.availability != StepsHistoryAvailability.AVAILABLE
				) {
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
				}
				else -> abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
			portableRun
		}.sortedWith(PORTABLE_STEPS_RUN_ORDER)
		if (materializing) {
			abort(PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING)
		}
		if (portableRuns.none { run ->
				run.facts.any { fact -> fact.coverage == PortableStepsFactCoverage.COVERED }
			}
		) {
			return null
		}
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

	@Suppress("ComplexCondition", "CyclomaticComplexMethod") // Exact bidirectional binding is one gate.
	private fun validateDiscoveredSegmentBindings(
		serviceRuns: List<SourceServiceRunEntity>,
		discoveredSegments: List<DiscoveredSegmentIdentity>,
		segmentsById: Map<Long, SessionSegment>,
	) {
		val runsById = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId)
		serviceRuns.forEach { run ->
			val segment = run.sessionSegmentId?.let(segmentsById::get) ?: return@forEach
			if (segment.logicalTrackingId != run.logicalTrackingId ||
				segment.serviceRunId != run.serviceRunId
			) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
		discoveredSegments.forEach { discovered ->
			val run = runsById[discovered.serviceRunId]
			val segment = segmentsById[discovered.segmentId]
			if (run == null || segment == null || run.logicalTrackingId != discovered.logicalTrackingId ||
				run.sessionSegmentId != discovered.segmentId ||
				segment.logicalTrackingId != discovered.logicalTrackingId ||
				segment.serviceRunId != discovered.serviceRunId
			) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
		}
	}

	private fun validateDeletedRunFence(
		run: SourceServiceRunEntity,
		fence: SourceDeletionFenceEntity,
		evidenceState: SourceEvidenceState,
		dependencies: PortableRoomDependencies,
	) {
		if (fence.collectedDataEpoch != evidenceState.collectedDataEpoch) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		val writer = deletedRunWriter(run, dependencies)
		if (dependencies.upsertsByRun[run.serviceRunId].orEmpty().any { fact ->
				!fact.belongsTo(writer)
			} || dependencies.factStatesByRun[run.serviceRunId].orEmpty().any { scoped ->
				!scoped.belongsTo(writer)
			}
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		dependencies.factStatesByRun[run.serviceRunId].orEmpty()
			.filter { scoped -> scoped.state.operation == StepFactRevisionEntity.OPERATION_RETRACT }
			.forEach { scoped ->
				if (scoped.state.scopeDeletionGeneration != fence.fenceGeneration ||
					scoped.state.collectedDataEpoch != fence.collectedDataEpoch
				) {
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
				}
			}
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun deletedRunWriter(
		run: SourceServiceRunEntity,
		dependencies: PortableRoomDependencies,
	): PortableWriterBinding {
		val manifests = dependencies.manifestsByRun[run.serviceRunId].orEmpty()
		if (!SessionManifestIntegrity.hasValidServiceRunTimeline(run, manifests)) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val bindings = manifests.mapNotNull { manifest ->
			val sources = dependencies.sourcesByManifest[
				ManifestKey(manifest.logicalTrackingId, manifest.manifestRevision)
			].orEmpty()
			if (manifest.logicalTrackingId != run.logicalTrackingId ||
				manifest.serviceRunId != run.serviceRunId ||
				!SessionManifestIntegrity.verify(manifest, sources)
			) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			val binding = sources.singleOrNull { source ->
				source.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS &&
					source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
					source.persistenceEligible
			}
			if (binding != null && !dependencies.hasValidStepsCaptureAuthority(manifest, binding)) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			binding?.let(::writerBinding)
		}
		val writer = bindings.distinct().singleOrNull()
			?: abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		if (writer.owner != SourceDestinationOwnerEntity.OWNER_STEPS_SESSION_FACTS ||
			writer.ownerGeneration == null || writer.ownerGeneration <= 0L ||
			writer.projectionId == null || writer.projectionVersion == null ||
			writer.bindingGeneration == null
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		return writer
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")
	private fun buildRun(
		bound: BoundPortableRun,
		sessionMode: PortableStepsSessionMode,
		dependencies: PortableRoomDependencies,
	): PortableStepsRunV1 {
		val run = bound.run
		val segment = bound.segment
		val envelope = bound.portableEnvelope()
		val manifests = dependencies.manifestsByRun[run.serviceRunId].orEmpty()
		val orderedManifests = manifests.sortedBy(SessionManifestVersionEntity::manifestRevision)
		if (!validManifestTimeline(run, sessionMode, orderedManifests)) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val stepsBindings = linkedMapOf<Long, SessionManifestSourceEntity>()
		val portableManifests = mutableListOf<PortableStepsManifestV1>()
		val zoneIds = linkedSetOf<String>()
		for (manifest in orderedManifests) {
			if (manifest.logicalTrackingId != run.logicalTrackingId ||
				manifest.serviceRunId != run.serviceRunId || manifest.sessionMode != sessionMode.name
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
				if (!StepFactRevisionIntegrity.hasValidStepsCaptureAuthority(
						policy = policy,
						consent = dependencies.consentsByEpoch[binding.consentEpoch],
						manifestPolicyRevision = manifest.sourcePolicyRevision,
						binding = binding,
					)
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
		val authority = portableRunAuthority(
			run = run,
			sessionMode = sessionMode,
			stepsBindings = stepsBindings,
			manifests = orderedManifests,
			writer = writer,
			dependencies = dependencies,
		)

		val historicalUpserts = dependencies.upsertsByRun[run.serviceRunId].orEmpty()
		val retainedFromMs = dependencies.evidenceState.retainedFromMs
		if (retainedFromMs != null && historicalUpserts.any { fact ->
				fact.intervalEndTimeMs?.let { endMs -> endMs < retainedFromMs } == true
			}
		) {
			abort(PortableStepsExportUnverifiableReason.RETENTION_CROSSES_ENTRY)
		}
		if (historicalUpserts.any { fact ->
				!fact.belongsTo(writer)
			}
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		historicalUpserts.forEach { fact ->
			if (fact.originKind != StepFactRevisionEntity.ORIGIN_LIVE_WAL) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			validateUpsertAttribution(
				fact,
				run,
				orderedManifests,
				stepsBindings,
				authority,
				dependencies,
			)
		}
		if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalRunTimeline(
				historicalUpserts,
				run.logicalTrackingId,
				run.serviceRunId,
			)
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}

		val factStates = dependencies.factStatesByRun[run.serviceRunId].orEmpty()
		if (factStates.any { scoped ->
				!scoped.belongsTo(writer)
			}
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val portableFacts = factStates.map { scoped ->
			validateScopedAttribution(
				scoped,
				run,
				orderedManifests,
				stepsBindings,
				authority,
				dependencies,
			)
			when (scoped.state.operation) {
				StepFactRevisionEntity.OPERATION_RETRACT ->
					abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
				StepFactRevisionEntity.OPERATION_UPSERT -> portableFact(scoped.state)
				else -> abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
		}.sortedWith(PORTABLE_STEPS_FACT_ORDER)
		val completeness = portableCompleteness(
			rows = authority.completeness,
			captureCoveredWholeRun = stepsBindings.size == manifests.size,
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
				startTimeMs = envelope.startTimeMs,
				endTimeMs = envelope.endTimeMs,
				storedZoneId = zoneIds.single(),
				manifests = portableManifests,
				completeness = completeness,
				facts = portableFacts,
			)
		}
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun validManifestTimeline(
		run: SourceServiceRunEntity,
		sessionMode: PortableStepsSessionMode,
		ordered: List<SessionManifestVersionEntity>,
	): Boolean = SessionManifestIntegrity.hasValidServiceRunTimeline(run, ordered) &&
		ordered.all { manifest -> manifest.sessionMode == sessionMode.name }

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun portableRunAuthority(
		run: SourceServiceRunEntity,
		sessionMode: PortableStepsSessionMode,
		stepsBindings: Map<Long, SessionManifestSourceEntity>,
		manifests: List<SessionManifestVersionEntity>,
		writer: PortableWriterBinding,
		dependencies: PortableRoomDependencies,
	): PortableRunAuthority {
		val projectionId = writer.projectionId
			?: abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val projectionVersion = writer.projectionVersion
			?: abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val bindingGeneration = writer.bindingGeneration
			?: abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		val lane = dependencies.lanesByKey[
			HistoricalLaneKey(bindingGeneration, projectionId, projectionVersion)
		] ?: abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		val requiredModeMask = when (sessionMode) {
			PortableStepsSessionMode.MANUAL -> MANUAL_SESSION_CAPTURE_MASK
			PortableStepsSessionMode.AUTOMATIC -> AUTOMATIC_SESSION_CAPTURE_MASK
		}
		if (!laneExecutionAuthority.owns(lane) || !isValidPortableLane(lane) ||
			lane.captureModeMask and requiredModeMask == 0L ||
			stepsBindings.keys.any { revision ->
				val manifest = manifests.singleOrNull { it.manifestRevision == revision }
				manifest == null || lane.activatedRolloutRevision > manifest.rolloutRevision
			}
		) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		val completeness = exactCompletenessRows(run, dependencies)
		val targetOrdinal = completeness.mapNotNull(SourceSessionCompletenessEntity::lastAdmissionOrdinal)
			.maxOrNull()
		if (targetOrdinal == null) {
			if (dependencies.upsertsByRun[run.serviceRunId].orEmpty().isNotEmpty()) {
				abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
		} else {
			if (targetOrdinal < lane.activationOrdinal ||
				lane.captureAdmissionCutoffOrdinal?.let { cutoff -> targetOrdinal > cutoff } == true ||
				lane.status == SourceProductProjectionLaneEntity.STATUS_RETIRED &&
				lane.contiguousAdmissionOrdinal < targetOrdinal
			) {
				abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
			}
			if (lane.contiguousAdmissionOrdinal < targetOrdinal) {
				abort(PortableStepsExportUnverifiableReason.ENTRY_MATERIALIZING)
			}
		}
		return PortableRunAuthority(writer, lane, targetOrdinal, completeness)
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun exactCompletenessRows(
		run: SourceServiceRunEntity,
		dependencies: PortableRoomDependencies,
	): List<SourceSessionCompletenessEntity> {
		val rows = dependencies.completenessByRun[run.serviceRunId].orEmpty().filter { row ->
			row.sourceKind == SourceDestinationOwnerEntity.SOURCE_STEPS
		}
		if (rows.isEmpty() || !StepsSessionCompletenessIntegrity.hasValidRegisteredTimeline(
				rows,
				run.logicalTrackingId,
				run.serviceRunId,
			)
		) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
		return rows
	}

	@Suppress("ComplexCondition", "CyclomaticComplexMethod")
	private fun validateUpsertAttribution(
		fact: StepFactRevisionEntity,
		run: SourceServiceRunEntity,
		manifests: List<SessionManifestVersionEntity>,
		stepsBindings: Map<Long, SessionManifestSourceEntity>,
		authority: PortableRunAuthority,
		dependencies: PortableRoomDependencies,
	) {
		val manifestRevision = fact.manifestRevision
		val manifest = manifests.singleOrNull { it.manifestRevision == manifestRevision }
		val binding = stepsBindings[manifestRevision]
		val admissionOrdinal = fact.sourceAdmissionOrdinal
		val targetOrdinal = authority.targetOrdinal
		if (manifest == null || binding == null || fact.logicalTrackingId != run.logicalTrackingId ||
			fact.serviceRunId != run.serviceRunId ||
			fact.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
			!fact.belongsTo(authority.writer) || admissionOrdinal == null || admissionOrdinal <= 0L ||
			targetOrdinal == null ||
			admissionOrdinal < authority.lane.activationOrdinal ||
			admissionOrdinal > targetOrdinal ||
			admissionOrdinal > authority.lane.contiguousAdmissionOrdinal ||
			authority.lane.captureAdmissionCutoffOrdinal?.let { cutoff ->
				admissionOrdinal > cutoff
			} == true ||
			authority.lane.activatedRolloutRevision > manifest.rolloutRevision ||
			fact.sourcePolicyRevision != manifest.sourcePolicyRevision ||
			fact.captureConsentEpoch != binding.consentEpoch ||
			fact.collectedDataEpoch != dependencies.evidenceState.collectedDataEpoch
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val manifestIndex = manifests.indexOfFirst { candidate ->
			candidate.manifestRevision == manifestRevision
		}
		if (manifestIndex < 0) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		val nextManifest = manifests.getOrNull(manifestIndex + 1)
		if (!validLiveWalFactSemantics(fact, run, manifest, nextManifest)) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
	}

	/** Adds exact run and manifest authority to the shared source-local fact contract. */
	@Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
	private fun validLiveWalFactSemantics(
		fact: StepFactRevisionEntity,
		run: SourceServiceRunEntity,
		manifest: SessionManifestVersionEntity,
		nextManifest: SessionManifestVersionEntity?,
	): Boolean {
		val startElapsed = fact.intervalStartElapsedRealtimeNanos ?: return false
		val endElapsed = fact.intervalEndElapsedRealtimeNanos ?: return false
		if (!StepFactRevisionIntegrity.hasValidCanonicalLiveWalFact(fact) ||
			fact.clockDomainId != run.bootId ||
			fact.bootClockDomainId != run.bootId || manifest.effectiveBootId != run.bootId ||
			startElapsed < run.startedElapsedNanos ||
			startElapsed < manifest.effectiveElapsedRealtimeNanos ||
			endElapsed < manifest.effectiveElapsedRealtimeNanos
		) {
			return false
		}
		val nextEffective = nextManifest?.effectiveElapsedRealtimeNanos
		return nextEffective == null ||
			(startElapsed < nextEffective && endElapsed < nextEffective)
	}

	@Suppress("ComplexCondition")
	private fun validateScopedAttribution(
		scoped: ScopedStepFactState,
		run: SourceServiceRunEntity,
		manifests: List<SessionManifestVersionEntity>,
		stepsBindings: Map<Long, SessionManifestSourceEntity>,
		authority: PortableRunAuthority,
		dependencies: PortableRoomDependencies,
	) {
		val binding = stepsBindings[scoped.manifestRevision]
		if (scoped.logicalTrackingId != run.logicalTrackingId ||
			scoped.serviceRunId != run.serviceRunId || binding == null ||
			manifests.none { it.manifestRevision == scoped.manifestRevision } ||
			!scoped.belongsTo(authority.writer) ||
			scoped.state.collectedDataEpoch != dependencies.evidenceState.collectedDataEpoch
		) {
			abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
		}
		if (scoped.state.operation == StepFactRevisionEntity.OPERATION_UPSERT) {
			if (scoped.firstIntervalStartTimeMs != scoped.state.intervalStartTimeMs ||
				scoped.manifestRevision != scoped.state.manifestRevision
			) {
				abort(PortableStepsExportUnverifiableReason.CAPTURE_ATTRIBUTION_UNVERIFIABLE)
			}
			validateUpsertAttribution(
				scoped.state,
				run,
				manifests,
				stepsBindings,
				authority,
				dependencies,
			)
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

	@Suppress("CyclomaticComplexMethod")
	private fun portableCompleteness(
		rows: List<SourceSessionCompletenessEntity>,
		captureCoveredWholeRun: Boolean,
	): PortableStepsCompletenessV1 {
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

	@Suppress("CyclomaticComplexMethod")
	private fun runSettlement(run: SourceServiceRunEntity): RunSettlement {
		val completedAtMs = run.completedAtMs
		return when (run.state) {
			"FINALIZED", "FAILED" -> when {
				completedAtMs == null || completedAtMs < 0L -> RunSettlement.INVALID
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

	private fun StepFactRevisionEntity.belongsTo(writer: PortableWriterBinding): Boolean =
		writerProjectionId == writer.projectionId &&
			writerProjectionVersion == writer.projectionVersion &&
			writerBindingGeneration == writer.bindingGeneration

	private fun ScopedStepFactState.belongsTo(writer: PortableWriterBinding): Boolean =
		writerBindingGeneration == writer.bindingGeneration &&
			state.writerBindingGeneration == writer.bindingGeneration &&
			state.writerProjectionId == writer.projectionId &&
			state.writerProjectionVersion == writer.projectionVersion

	@Suppress(
		"ComplexCondition",
		"CyclomaticComplexMethod",
		"ReturnCount",
	) // Every durable lane authority field participates in this fail-closed gate.
	private fun isValidPortableLane(lane: SourceProductProjectionLaneEntity): Boolean {
		if (lane.sourceKind != SourceDestinationOwnerEntity.SOURCE_STEPS ||
			lane.bindingGeneration <= 0L || lane.projectionId.isBlank() ||
			lane.projectionVersion <= 0 || lane.captureModeMask <= 0L ||
			lane.captureModeMask and ALL_CAPTURE_MODE_MASK.inv() != 0L ||
			lane.productStage !in PRODUCT_STAGES || lane.activatedRolloutRevision <= 0L ||
			lane.activationOrdinal <= 0L || lane.installedAtMs < 0L ||
			lane.updatedAtMs < lane.installedAtMs
		) {
			return false
		}
		val minimumCursor = lane.activationOrdinal - 1L
		val cutoff = lane.captureAdmissionCutoffOrdinal
		if (lane.contiguousAdmissionOrdinal < minimumCursor ||
			cutoff?.let { it < minimumCursor || lane.contiguousAdmissionOrdinal > it } == true
		) {
			return false
		}
		if ((lane.terminalDisposition == null) != (lane.terminalAtMs == null)) {
			return false
		}
		return when (lane.status) {
			SourceProductProjectionLaneEntity.STATUS_ACTIVE ->
				lane.retentionRequired && lane.terminalDisposition == null
			SourceProductProjectionLaneEntity.STATUS_RETIRED -> {
				if (lane.retentionRequired || lane.terminalDisposition == null) {
					return false
				}
				val terminalAtMs = lane.terminalAtMs ?: return false
				lane.terminalDisposition ==
					SourceProductProjectionLaneEntity.DISPOSITION_CONTAINED_AFTER_DRAIN &&
					cutoff != null && lane.contiguousAdmissionOrdinal == cutoff &&
					terminalAtMs >= lane.installedAtMs && lane.updatedAtMs >= terminalAtMs
			}
			else -> false
		}
	}

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
			}
		) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
	}

	private fun validateServiceRunPage(
		page: List<SourceServiceRunEntity>,
		previous: SourceServiceRunEntity?,
		expectedLogicalIds: Set<String>,
	) {
		val ordered = previous?.let { listOf(it) + page } ?: page
		if (page.any { it.logicalTrackingId !in expectedLogicalIds || it.serviceRunId.isBlank() } ||
			ordered.zipWithNext().any { (left, right) -> !serviceRunAfter(right, left) }
		) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
	}

	@Suppress("ComplexCondition") // Blank identities and strict page ordering fail together.
	private fun validateCandidateRunPage(
		page: List<SourceServiceRunEntity>,
		previous: SourceServiceRunEntity?,
	) {
		val ordered = previous?.let { listOf(it) + page } ?: page
		if (page.any { it.serviceRunId.isBlank() || it.logicalTrackingId.isBlank() } ||
			ordered.zipWithNext().any { (left, right) ->
				right.startedAtMs < left.startedAtMs ||
					right.startedAtMs == left.startedAtMs && right.serviceRunId <= left.serviceRunId
			}
		) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
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

	private fun validateFactLineagePage(
		page: List<StepFactRevisionEntity>,
		previous: StepFactRevisionEntity?,
		expectedWriter: PortableWriterKey,
		expectedFactIds: Set<String>,
	) {
		val ordered = previous?.let { listOf(it) + page } ?: page
		if (page.any { revision ->
				revision.writerProjectionId != expectedWriter.projectionId ||
					revision.writerProjectionVersion != expectedWriter.projectionVersion ||
					revision.logicalFactId !in expectedFactIds
			} || ordered.zipWithNext().any { (left, right) -> !factLineageAfter(right, left) }
		) {
			abort(PortableStepsExportUnverifiableReason.SOURCE_EVIDENCE_UNAVAILABLE)
		}
	}

	private fun factLineageAfter(
		candidate: StepFactRevisionEntity,
		previous: StepFactRevisionEntity,
	): Boolean = if (candidate.logicalFactId != previous.logicalFactId) {
		candidate.logicalFactId > previous.logicalFactId
	} else {
		candidate.semanticRevision > previous.semanticRevision
	}

	private fun factKey(revision: StepFactRevisionEntity) = PortableFactKey(
		projectionId = revision.writerProjectionId,
		projectionVersion = revision.writerProjectionVersion,
		logicalFactId = revision.logicalFactId,
	)

	private fun writerKey(fact: PortableFactKey) = PortableWriterKey(
		projectionId = fact.projectionId,
		projectionVersion = fact.projectionVersion,
	)

	private fun noEntries() = PortableStepsSnapshot.Outcome(ExportPortableStepsResult.NoEntries)

	private fun abort(reason: PortableStepsExportUnverifiableReason): Nothing =
		throw PortableSnapshotAbort(reason)

	private fun BoundPortableRun.portableEnvelope() = PortableRunEnvelope(
		startTimeMs = minOf(run.startedAtMs, segment.startTimeMs),
		endTimeMs = maxOf(segment.endTimeMs, run.completedAtMs ?: segment.endTimeMs),
	)

	private data class BoundPortableRun(
		val run: SourceServiceRunEntity,
		val segment: SessionSegment,
	)

	private data class PortableRunEnvelope(
		val startTimeMs: Long,
		val endTimeMs: Long,
	)

	private data class PortableEntryDiscovery(
		val logicalTrackingIds: List<String>,
		val segments: List<DiscoveredSegmentIdentity>,
	)

	private data class DiscoveredSegmentIdentity(
		val segmentId: Long,
		val logicalTrackingId: String,
		val serviceRunId: String,
	)

	private data class PortableWriterBinding(
		val owner: String,
		val ownerGeneration: Long?,
		val projectionId: String?,
		val projectionVersion: Int?,
		val bindingGeneration: Long?,
	)

	private data class PortableWriterKey(
		val projectionId: String,
		val projectionVersion: Int,
	)

	private data class PortableFactKey(
		val projectionId: String,
		val projectionVersion: Int,
		val logicalFactId: String,
	)

	private data class PortableRunAuthority(
		val writer: PortableWriterBinding,
		val lane: SourceProductProjectionLaneEntity,
		val targetOrdinal: Long?,
		val completeness: List<SourceSessionCompletenessEntity>,
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
		const val MANUAL_SESSION_CAPTURE_MASK = 1L shl 0
		const val AUTOMATIC_SESSION_CAPTURE_MASK = 1L shl 1
		const val ALL_CAPTURE_MODE_MASK = 7L
		val PRODUCT_STAGES = setOf(
			SourceProductProjectionLaneEntity.STAGE_EVENT_SHADOW,
			SourceProductProjectionLaneEntity.STAGE_EVENT_CANONICAL,
		)
		val EXACT_SESSION_MODES = PortableStepsSessionMode.entries.mapTo(hashSetOf()) { it.name }
	}
}

private data class PortableRoomDependencies(
	val manifests: List<SessionManifestVersionEntity>,
	val sources: List<SessionManifestSourceEntity>,
	val policies: List<SourcePolicyEntity>,
	val consents: List<SourceConsentEpochEntity>,
	val completeness: List<SourceSessionCompletenessEntity>,
	val lanes: List<SourceProductProjectionLaneEntity>,
	val historyFactStatesByRun: Map<String, List<StepsFactCandidateState>>,
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
	val consentsByEpoch = consents.associateBy(SourceConsentEpochEntity::epoch)
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

	fun hasValidStepsCaptureAuthority(
		manifest: SessionManifestVersionEntity,
		binding: SessionManifestSourceEntity,
	): Boolean = StepFactRevisionIntegrity.hasValidStepsCaptureAuthority(
		policy = policiesByRevision[manifest.sourcePolicyRevision],
		consent = consentsByEpoch[binding.consentEpoch],
		manifestPolicyRevision = manifest.sourcePolicyRevision,
		binding = binding,
	)

	fun toHistorySnapshot(serviceRuns: List<SourceServiceRunEntity>): StepsHistoryBatchSnapshot =
		StepsHistoryBatchSnapshot(
			serviceRuns = serviceRuns.associateBy(SourceServiceRunEntity::serviceRunId),
			manifestsByRun = manifestsByRun,
			sourcesByManifest = sourcesByManifest,
			stepPolicies = policiesByRevision,
			stepCaptureConsents = consentsByEpoch,
			deletionFenceDigests = fencesByDigest.keys,
			factStatesByRun = historyFactStatesByRun,
			completenessByRun = completenessByRun,
			productLanes = lanesByKey,
			terminalFailures = terminalFailures,
			dependencyOverflow = null,
			evidenceState = evidenceState,
		)
}
