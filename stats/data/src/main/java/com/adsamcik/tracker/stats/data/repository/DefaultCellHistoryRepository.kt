package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.savedSessionCutoff
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.DeletedCapturedCellSelectionAuthentication
import com.adsamcik.tracker.shared.base.database.DeletedImportedCellSelectionAuthentication
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedCellProductFailure
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReadBudget
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReadUsage
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReader
import com.adsamcik.tracker.shared.base.database.ImportedCellProductScanContextFailure
import com.adsamcik.tracker.shared.base.database.ImportedCellProductScanPage
import com.adsamcik.tracker.shared.base.database.ImportedCellProductScanScope
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellObservationV1
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellRunV1
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableCellOriginComparison
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.authenticateDeletedImportedCellSelectionInTransaction
import com.adsamcik.tracker.shared.base.database.authenticateDeletedCapturedCellSelectionInTransaction
import com.adsamcik.tracker.shared.base.database.dao.CellLogicalHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.AcquisitionPlanRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.SourceSessionCompletenessEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductProjectionLaneEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProjectionFailureEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedChildEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCapturedEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.ProviderRegistrationGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryObservation
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntrySelection
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryPage
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeContinuation
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangePage
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeRequest
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeScope
import com.adsamcik.tracker.stats.api.repository.CellHistoryRangeUnavailableReason
import com.adsamcik.tracker.stats.api.repository.CellHistoryRepository
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDay
import com.adsamcik.tracker.stats.api.repository.CellHistoryStructuralDayCompleteness
import com.adsamcik.tracker.stats.api.repository.HistorySource
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryDigest
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistoryIdentity
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.SourceAwareHistoryPageUnavailableReason
import com.adsamcik.tracker.stats.api.value.EpochMs
import com.adsamcik.tracker.shared.base.time.BootClockDomainProvider
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-local Cell facade. Every dependency for a call is read in one bounded Room transaction. */
internal class DefaultCellHistoryRepository internal constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val ioDispatcher: CoroutineDispatcher,
	private val sharedHistoryPageCheckpoint: suspend (completedPageCount: Int) -> Unit,
	private val bootClockDomainProvider: BootClockDomainProvider? = null,
) : CellHistoryRepository, CellImportedHistoryEligibleReader {
	@Inject
	constructor(
		database: AppDatabase,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
		bootClockDomainProvider: BootClockDomainProvider,
	) : this(
		database,
		laneExecutionAuthority,
		ioDispatcher,
		{ currentCoroutineContext().ensureActive() },
		bootClockDomainProvider,
	)

	internal constructor(
		database: AppDatabase,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
		ioDispatcher: CoroutineDispatcher,
	) : this(
		database,
		laneExecutionAuthority,
		ioDispatcher,
		{ currentCoroutineContext().ensureActive() },
		null,
	)

	private val importedProductReader = ImportedCellProductReader(database)
	private val rangeContinuationIssuer = Any()

	private val snapshots = CellHistorySnapshotReader(database, bootClockDomainProvider)
	private val native = CellNativeHistoryReader(database, laneExecutionAuthority, snapshots)
	private val details = CellHistoryDetailReader(database, laneExecutionAuthority, ioDispatcher,
		importedProductReader, snapshots)
	private val merged = CellHistoryMergeReader(database, laneExecutionAuthority, importedProductReader, native)
	private val importedScan = CellImportedHistoryScanReader(database, laneExecutionAuthority,
		importedProductReader, sharedHistoryPageCheckpoint)

	/** Exact selected imports, read inside the caller's current Room snapshot. */
	internal suspend fun selectedImportedInTransaction(
		identities: List<String>, range: com.adsamcik.tracker.stats.api.repository.HistoryRange,
	): Map<String, CellHistoryEntry> = details.selectedImportedInTransaction(identities, range)

	override suspend fun detail(selection: CellHistoryEntrySelection): CellHistoryQuery =
		when (selection) {
			is LocalCellHistorySelection -> details.local(selection)
			is ImportedCellHistorySelection -> imported(selection)
			else -> CellHistoryQuery.NotFound
		}

	/** Original imported receipt/lineage qualification without constructing a whole public entry. */
	internal suspend fun measuredImportedOriginCandidatesInTransaction(evaluation: ImportedCellProductEvaluation.Readable,
		observedTimeMs: Long, itemIndex: Int): List<CellCapturedFactRevisionEntity> =
		importedProductReader.measuredChildOriginCandidatesInTransaction(
			evaluation, observedTimeMs, itemIndex, MAX_RESULTS + 1)

	internal suspend fun measuredImportedEvaluationInTransaction(identity: String): ImportedCellProductEvaluation? =
		importedProductReader.selectIdentityInTransaction(PortableCellOpaqueIdentity(identity))

	internal suspend fun measuredFactInTransaction(factId: String, revision: Long): QualifiedMeasuredCellFact? {
		val snapshot = measuredSnapshotInTransaction(factId)
			?:
                throw MeasuredRadioParentFailure(
				com.adsamcik.tracker.stats.api.repository.HistoryProblemReason.INTEGRITY_UNAVAILABLE)
		return CellHistoryComposer.qualifyMeasuredFact(factId, revision, snapshot, laneExecutionAuthority)
	}

	/** Original capture inputs for one measured parent in the current transaction. */
	internal suspend fun measuredSnapshotInTransaction(factId: String): CellHistorySnapshot? =
		details.measuredSnapshotInTransaction(factId)

	override suspend fun session(segmentId: Long): CellHistoryQuery {
		require(segmentId > 0L)
		return withContext(ioDispatcher) {
			database.withTransaction { sessionInTransaction(segmentId) }
		}
	}

	internal suspend fun sessionInTransaction(segmentId: Long): CellHistoryQuery =
		details.sessionInTransaction(segmentId)

	override suspend fun imported(selection: ImportedCellHistorySelection): CellHistoryQuery =
        details.imported(selection)

	override suspend fun recent(limit: Int): CellHistoryPage {
		require(limit in 1..MAX_RESULTS)
		return withContext(ioDispatcher) {
			try {
				database.withTransaction { merged.composeRecent(limit) }
			} catch (cancelled: kotlinx.coroutines.CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				CellHistoryPage.Failed(CellHistoryCause.FACT_INTEGRITY_FAILED)
			}
		}
	}

	/** Cell groups selected from the caller's bounded physical candidates. */
	internal suspend fun selectBySegmentIdsInTransaction(segmentIds: List<Long>): CellComposedPage =
		native.selectBySegmentIdsInTransaction(segmentIds)

	/** Entries whose original chosen capture sources contain only Cell. */
	internal suspend fun recentCellOnlyInTransaction(limit: Int): CellComposedPage {
		require(limit in 1..MAX_RESULTS)
		return native.loadRecentCellCompositions(limit) { it.capturesOnlyCell }
	}

	/** Local and imported Cell groups, retaining native ownership for exact duplicates. */
	internal suspend fun recentCellHistoryInTransaction(limit: Int): CellSourceComposedPage =
		merged.recentCellHistoryInTransaction(limit)

	override suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<CellImportedHistoryEligibleEntry> =
		importedScan.recentImportedEligibleForSharedHistoryInTransaction(limit)

	override suspend fun range(request: CellHistoryRangeRequest): CellHistoryRangePage {
		val continuation = request.continuation
		if (continuation != null) {
			if (continuation !is CellHistoryRangeContinuationSnapshot ||
				continuation.scope != request.scope ||
				continuation.issuer !== rangeContinuationIssuer
			) {
				return CellHistoryRangePage.Unavailable(
					CellHistoryRangeUnavailableReason.INVALID_CONTINUATION,
				)
			}
			return continuation.remaining.toRangePage(
				request.scope,
				request.limit,
				rangeContinuationIssuer,
			)
		}
		return withContext(ioDispatcher) {
			val result = database.withTransaction { merged.composeRange(request.scope) }
			when (result) {
				is CellHistoryRangeBuild.Failed -> CellHistoryRangePage.Failed(result.cause)
				is CellHistoryRangeBuild.Ready ->
					result.entries.toRangePage(
						request.scope,
						request.limit,
						rangeContinuationIssuer,
					)
			}
		}
	}
}

/** Bounded source dependency snapshots; callers keep the surrounding Room transaction. */
private class CellHistorySnapshotReader(
	private val database: AppDatabase, private val bootClockDomainProvider: BootClockDomainProvider?,
) {
	suspend fun expandMembership(seeds: List<SessionSegment>): CellMembershipExpansion {
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).filter(String::isNotBlank).distinct()
		if (logicalIds.isEmpty()) { return CellMembershipExpansion(seeds, emptyMap()) }
		val runs = loadMembershipRuns(logicalIds) ?: return CellMembershipExpansion(seeds,
			logicalIds.associateWith { CellHistoryCause.READ_BUDGET_EXCEEDED }, overflow = true)
		currentCoroutineContext().ensureActive()
		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		val loaded = if (segmentIds.isEmpty()) { emptyList() } else {
			database.trackingHistoryReadDao().segments(segmentIds) }
		val segments = (loaded + seeds).distinctBy(SessionSegment::id)
		return CellMembershipExpansion(segments, membershipFailures(logicalIds, runs, seeds, segments))
	}

	private suspend fun loadMembershipRuns(logicalIds: List<String>): List<SourceServiceRunEntity>? {
		val runs = mutableListOf<SourceServiceRunEntity>()
		var cursor: ServiceRunCursor? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_LOGICAL_MEMBERS - runs.size
			val pageLimit = minOf(MEMBER_PAGE_SIZE, remaining + 1)
			val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
				logicalIds, pageLimit, cursor?.logicalId, cursor?.startedAtMs, cursor?.serviceRunId)
			if (page.size > remaining) { return null }
			if (page.isEmpty()) { break }
			val next = ServiceRunCursor(page.last())
			check(cursor == null || next > cursor) { "Cell member cursor did not advance" }
			runs += page
			cursor = next
			if (page.size < pageLimit) { break }
		}
		return runs
	}

	private fun membershipFailures(
		logicalIds: List<String>, runs: List<SourceServiceRunEntity>, seeds: List<SessionSegment>,
		segments: List<SessionSegment>,
	): Map<String, CellHistoryCause> {
		val byId = segments.associateBy(SessionSegment::id)
		val failures = linkedMapOf<String, CellHistoryCause>()
		logicalIds.filter { logical -> runs.none { it.logicalTrackingId == logical } }.forEach {
			failures[it] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID }
		runs.filterNot { runHasExactSegment(it, byId) }.forEach {
			failures[it.logicalTrackingId] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID }
		seeds.forEach { seed ->
			val logical = seed.logicalTrackingId ?: return@forEach
			if (!seedHasExactRun(seed, logical, runs)) {
				failures[logical] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID }
		}
		return failures
	}

	private fun runHasExactSegment(run: SourceServiceRunEntity, byId: Map<Long, SessionSegment>): Boolean {
		val segment = run.sessionSegmentId?.let(byId::get)
		return segment != null && segment.logicalTrackingId == run.logicalTrackingId &&
			segment.serviceRunId == run.serviceRunId
	}

	private fun seedHasExactRun(seed: SessionSegment, logicalId: String, runs: List<SourceServiceRunEntity>): Boolean {
		val run = runs.singleOrNull { it.serviceRunId == seed.serviceRunId }
		return run != null && run.logicalTrackingId == logicalId && run.sessionSegmentId == seed.id
	}

	@Suppress("LongMethod")
	suspend fun loadSnapshot(expansion: CellMembershipExpansion, selectedFactId: String? =
        null): CellHistorySnapshot {
		if (expansion.overflow) {
			return CellHistorySnapshot.empty(expansion).copy(overflow = true)
		}
		val segments = expansion.segments
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId).filter(String::isNotBlank).distinct()
		if (runIds.isEmpty()) {
			return CellHistorySnapshot.empty(expansion)
		}
		val logicalIds = segments.mapNotNull(SessionSegment::logicalTrackingId).filter(String::isNotBlank).distinct()
		val readDao = database.trackingHistoryReadDao()
		val factDao = database.cellCapturedFactDao()
		val runs = readDao.serviceRuns(runIds)
		val manifests = readDao.manifests(runIds, MAX_MANIFESTS + 1)
		val sources = readDao.manifestSources(runIds, MAX_MANIFEST_SOURCES + 1)
		val policies = readDao.policiesForServiceRuns(CELL_SOURCE, runIds)
		val completeness = readDao.completeness(runIds, MAX_COMPLETENESS + 1)
		val sessions = database.sourceSessionDao().sessions(logicalIds)
		val factLoad = if (selectedFactId == null) {
			loadFactPages(runIds, logicalIds)
		} else {
			val rows = factDao.selectedHistoryLineages(SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION, selectedFactId, MAX_FACT_REVISIONS + 1)
			CellFactLoad(rows.take(MAX_FACT_REVISIONS), rows.size > MAX_FACT_REVISIONS)
		}
		val revisions = factLoad.revisions
		val walEventIds = revisions.map(CellCapturedFactRevisionEntity::sourceEventId).distinct()
		val admissions = walEventIds.chunked(SQL_ID_BATCH).flatMap { eventIds ->
			currentCoroutineContext().ensureActive()
			factDao.historyWalCarriers(eventIds, eventIds.size)
		}
		currentCoroutineContext().ensureActive()
		val scopedCursors = if (selectedFactId != null) {
			emptyList()
		} else {
			factDao.historyCursorsForScopes(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			runIds,
			logicalIds,
			MAX_CURSORS + 1,
		)
		}
		val factIds = revisions.map(CellCapturedFactRevisionEntity::logicalFactId).distinct()
		val (currentChildren, childOverflow) = loadChildren(factIds)
		val referencedCursors = factIds.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyCursors(ids, MAX_CURSORS + 1)
		}
		val cursors = (scopedCursors + referencedCursors).distinctBy { cursor ->
			Triple(cursor.writerProjectionId, cursor.writerProjectionVersion, cursor.logicalFactId)
		}
		val generations = factDao.historyDeletionGenerations(logicalIds, runIds, MAX_DELETION_GENERATIONS + 1)
		val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
		val (planHeaders, desiredPlans) = loadPlans(planRevisions)
		val registrationGenerations = revisions.map(CellCapturedFactRevisionEntity::registrationGeneration).distinct()
		val providers = registrationGenerations.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyProviderRegistrations(CELL_SOURCE, ids, MAX_PROVIDERS + 1)
		}
		val currentProviderRows = database.sourceBrokerDao().currentPhysicalRegistrationsBounded(
			CELL_SOURCE,
			MAX_PROVIDERS + 1,
		)
		val retiringProviderRows = database.sourceBrokerDao().pendingProviderRemovalsBounded(
			CELL_SOURCE,
			MAX_PROVIDERS + 1,
		)
		val currentProviderRegistrations = (currentProviderRows + retiringProviderRows)
			.distinctBy(ProviderRegistrationGenerationEntity::registrationGeneration)
		val authorizations = registrationGenerations.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyAuthorizations(CELL_SOURCE, ids, MAX_AUTHORIZATIONS + 1)
		}
		val demandIds = authorizations.mapNotNull { it.demandId }.distinct()
		val demands = demandIds.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			database.sourceBrokerDao().demandsByIds(ids)
		}
		val consents = loadConsents(sources)
		val scopePairs = scopePairs(segments)
		val digestToPair = scopePairs.associateBy { (logical, run) ->
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, logical, run,
			)
		}
		val fences = if (digestToPair.isEmpty()) {
			emptyList()
		} else {
			readDao.deletionFences(
			CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, digestToPair.keys.toList(),
		)
		}
		val lanes = readDao.productLanesForServiceRuns(CELL_SOURCE,
			SessionManifestPurposeCode.SESSION_CAPTURE, runIds)
		val failures = loadFailures(selectedFactId, revisions, completeness, lanes, runIds)
		val evidence = database.sourceEvidenceStateDao().get()
		currentCoroutineContext().ensureActive()
		val overflow = factLoad.overflow || childOverflow || listOf(
			runs.size to MAX_LOGICAL_MEMBERS,
			sessions.size to MAX_SESSIONS,
			manifests.size to MAX_MANIFESTS,
			sources.size to MAX_MANIFEST_SOURCES,
			completeness.size to MAX_COMPLETENESS,
			policies.size to MAX_POLICIES,
			consents.size to MAX_CONSENTS,
			cursors.size to MAX_CURSORS,
			generations.size to MAX_DELETION_GENERATIONS,
			planHeaders.size to MAX_PLANS,
			desiredPlans.size to MAX_PLANS,
			providers.size to MAX_PROVIDERS,
			authorizations.size to MAX_AUTHORIZATIONS,
			currentProviderRows.size to MAX_PROVIDERS,
			retiringProviderRows.size to MAX_PROVIDERS,
			currentProviderRegistrations.size to MAX_PROVIDERS,
			demands.size to MAX_DEMANDS,
			admissions.size to MAX_WAL_CARRIERS,
			fences.size to MAX_DELETION_FENCES,
			lanes.size to MAX_LANES,
			failures.size to MAX_TERMINAL_FAILURES,
		).any { (size, limit) -> size > limit }
		return CellHistorySnapshot(
			expansion = expansion,
			sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
			savedSessionCutoffs = sessions.associate { it.logicalTrackingId to database.savedSessionCutoff(it) },
			runs = runs.associateBy(SourceServiceRunEntity::serviceRunId),
			manifestsByRun = manifests.take(MAX_MANIFESTS).groupBy(SessionManifestVersionEntity::serviceRunId),
			sourcesByManifest = sources.take(MAX_MANIFEST_SOURCES).groupBy {
				CellManifestKey(it.logicalTrackingId, it.manifestRevision)
			},
			policies = policies.associateBy(SourcePolicyEntity::policyRevision),
			consents = consents.associateBy(SourceConsentEpochEntity::epoch),
			completenessByRun = completeness.take(MAX_COMPLETENESS)
				.groupBy { it.serviceRunId },
			revisions = revisions.take(MAX_FACT_REVISIONS),
			childrenByRevision = currentChildren.groupBy { it.logicalFactId to it.semanticRevision },
			cursors = cursors.take(MAX_CURSORS),
			deletionGenerations = generations.take(MAX_DELETION_GENERATIONS)
				.associateBy { it.logicalTrackingId to it.serviceRunId },
			deletedScopes = fences.mapNotNull { digestToPair[it.scopeIdentityDigest] }.toSet(),
			planHeaders = planHeaders.take(MAX_PLANS).associateBy { it.revision },
			desiredPlans = desiredPlans.take(MAX_PLANS).associateBy(SourceDesiredPlanEntity::revision),
			providerRegistrations = providers.take(MAX_PROVIDERS).associateBy { it.registrationGeneration },
			currentProviderRegistrations = currentProviderRegistrations.take(MAX_PROVIDERS),
			currentClockDomainId = bootClockDomainProvider?.current()
				?: currentProviderRegistrations.singleOrNull {
					it.status == ProviderRegistrationGenerationEntity.STATUS_ACTIVE
				}?.clockDomainId,
			authorizationsByRegistration = authorizations.take(MAX_AUTHORIZATIONS)
				.groupBy { it.registrationGeneration },
			demands = demands.take(MAX_DEMANDS).associateBy(SourceDemandEntity::demandId),
			admissions = admissions.take(MAX_WAL_CARRIERS),
			lanes = lanes,
			terminalFailures = failures.take(MAX_TERMINAL_FAILURES),
			evidenceState = evidence,
			overflow = overflow,
		)
	}

	suspend fun loadFactPages(
		runIds: List<String>, logicalIds: List<String>,
	): CellFactLoad {
		val facts = mutableListOf<CellCapturedFactRevisionEntity>()
		var cursor: FactCursor? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_FACT_REVISIONS - facts.size
			val pageLimit = minOf(FACT_PAGE_SIZE, remaining + 1)
			val page = database.cellCapturedFactDao().historyRevisionPage(
				runIds, logicalIds, pageLimit, cursor?.projectionId, cursor?.projectionVersion,
				cursor?.logicalFactId, cursor?.semanticRevision,
			)
			if (page.size > remaining) {
				return CellFactLoad(facts, overflow = true)
			}
			if (page.isEmpty()) {
				break
			}
			val next = FactCursor(page.last())
			check(cursor == null || next > cursor) { "Cell fact cursor did not advance" }
			facts += page
			cursor = next
			if (page.size < pageLimit) {
				break
			}
		}
		return CellFactLoad(facts, overflow = false)
	}

	private suspend fun loadChildren(factIds: List<String>): Pair<List<CellCapturedChildEntity>, Boolean> {
		val children = mutableListOf<CellCapturedChildEntity>()
		for (ids in factIds.chunked(CELL_CHILD_FACT_CHUNK)) {
			currentCoroutineContext().ensureActive()
			val rows = database.radioObservationChildDao().cellCurrentSessionChildrenByFactIds(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION, ids, ids.size * MAX_CELL_CHILDREN + 1)
			if (rows.size > ids.size * MAX_CELL_CHILDREN || children.size + rows.size > MAX_HISTORY_CHILDREN) {
				return children to true
			}
			children += rows
		}
		return children to false
	}

	private suspend fun loadPlans(
		revisions: List<Long>,
	): Pair<List<AcquisitionPlanRevisionEntity>, List<SourceDesiredPlanEntity>> = if (revisions.isEmpty()) {
		emptyList<AcquisitionPlanRevisionEntity>() to emptyList<SourceDesiredPlanEntity>()
	} else {
		val dao = database.cellCapturedFactDao()
		dao.historyAcquisitionPlanRevisions(revisions, MAX_PLANS + 1) to
			dao.historyDesiredPlans(CELL_SOURCE, revisions, MAX_PLANS + 1)
	}

	private suspend fun loadConsents(sources: List<SessionManifestSourceEntity>): List<SourceConsentEpochEntity> {
		val epochs = sources.filter(::isCellCaptureMembership).map(SessionManifestSourceEntity::consentEpoch).distinct()
		return if (epochs.isEmpty()) { emptyList() } else {
			database.sourcePolicyDao().consentEpochs(CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, epochs)
		}
	}

	private fun scopePairs(segments: List<SessionSegment>): List<Pair<String, String>> =
        segments.mapNotNull { segment ->
		val logical = segment.logicalTrackingId?.takeIf(String::isNotBlank)
		val run = segment.serviceRunId?.takeIf(String::isNotBlank)
		if (logical == null || run == null) { null } else { logical to run }
	}

	private suspend fun loadFailures(
		selectedFactId: String?, revisions: List<CellCapturedFactRevisionEntity>,
		completeness: List<SourceSessionCompletenessEntity>, lanes: List<SourceProductProjectionLaneEntity>,
		runIds: List<String>,
	): List<SourceProjectionFailureEntity> {
		val through = maxOfOrNull(revisions.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal),
			completeness.filter { it.sourceKind == CELL_SOURCE }.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull())
		val after = lanes.minOfOrNull { it.activationOrdinal }?.minus(1L)
		return when {
			selectedFactId != null -> revisions.map { it.sourceAdmissionOrdinal }.distinct().mapNotNull { ordinal ->
				database.sourceProjectionStateDao().failure(SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
					SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION, ordinal)?.takeIf { it.terminal }
			}
			after == null || through == null || after >= through -> emptyList()
			else -> database.trackingHistoryReadDao().terminalFailuresForServiceRuns(CELL_SOURCE,
				SessionManifestPurposeCode.SESSION_CAPTURE, runIds, after, through, MAX_TERMINAL_FAILURES + 1)
		}
	}

	private fun maxOfOrNull(left: Long?, right: Long?): Long? = when {
		left == null -> right
		right == null -> left
		else -> maxOf(left, right)
	}
}

/** Existing native paging and composition over exact physical membership. */
private class CellNativeHistoryReader(
	private val database: AppDatabase, private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val snapshots: CellHistorySnapshotReader,
) {
	/** Exact local Cell groups for one bounded caller-owned physical candidate generation. */
	suspend fun selectBySegmentIdsInTransaction(
		segmentIds: List<Long>,
	): CellComposedPage {
		require(segmentIds.size <= MAX_RESULTS)
		require(segmentIds.all { it > 0L } && segmentIds.distinct().size == segmentIds.size)
		if (segmentIds.isEmpty()) {
			return CellComposedPage.Available(emptyList())
		}
		val seeds = database.trackingHistoryReadDao().segments(segmentIds)
		val expansion = snapshots.expandMembership(seeds)
		val snapshot = snapshots.loadSnapshot(expansion)
		if (snapshot.overflow) {
			return CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
		return CellComposedPage.Available(
			CellHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in logicalIds }
				.sortedWith(cellCompositionOrder),
		)
	}

	@Suppress("CyclomaticComplexMethod")
	suspend fun loadRecentCellCompositions(
		limit: Int,
		accept: (ComposedCellEntry) -> Boolean,
	): CellComposedPage {
		val accepted = mutableListOf<ComposedCellEntry>()
		var scanned = 0
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null
		while (accepted.size < limit) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_CANDIDATE_SCAN - scanned
			if (remaining == 0) {
				return CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, remaining)
			val page = database.cellCapturedFactDao().logicalHistoryCandidatePageInWallRange(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				SourceDestinationOwnerEntity.SOURCE_CELL,
				0L,
				Long.MAX_VALUE,
				pageLimit,
				beforeStartTimeMs,
				beforeSegmentId,
			)
			if (page.isEmpty()) {
				break
			}
			if (page.size > pageLimit ||
				!page.hasValidRangeKeyset(beforeStartTimeMs, beforeSegmentId)
			) {
				return CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			}
			scanned += page.size
			val seeds = page.map { it.segment }
			val snapshot = snapshots.loadSnapshot(snapshots.expandMembership(seeds))
			if (snapshot.overflow) {
				return CellComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val candidateIds = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			accepted += CellHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in candidateIds && accept(it) }
				.sortedWith(cellCompositionOrder)
			val last = page.last()
			beforeStartTimeMs = last.logicalRecencyStartMs
			beforeSegmentId = last.logicalRecencySegmentId
			if (page.size < pageLimit) {
				break
			}
		}
		return CellComposedPage.Available(accepted.sortedWith(cellCompositionOrder).take(limit))
	}

	suspend fun loadLocalRange(bounds: CellRangeQueryBounds): LocalCellRangeBuild {
		val accepted = linkedMapOf<String, ComposedCellEntry>()
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null
		var candidateCount = 0
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_RANGE_CANDIDATES_PER_ORIGIN - candidateCount
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, remaining + 1)
			val candidates = database.cellCapturedFactDao().logicalHistoryCandidatePageInWallRange(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				SourceDestinationOwnerEntity.SOURCE_CELL,
				bounds.fromInclusiveMs,
				bounds.toExclusiveMs,
				pageLimit,
				beforeStartTimeMs,
				beforeSegmentId,
			)
			if (candidates.isEmpty()) {
				break
			}
			if (!candidates.hasValidRangeKeyset(beforeStartTimeMs, beforeSegmentId) ||
				candidates.size > remaining
			) {
				return LocalCellRangeBuild.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			}
			candidateCount += candidates.size
			val seeds = candidates.map { it.segment }
			val snapshot = snapshots.loadSnapshot(snapshots.expandMembership(seeds))
			if (snapshot.overflow) {
				return LocalCellRangeBuild.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
			}
			val requested = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			CellHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in requested }
				.forEach { accepted.putIfAbsent(it.logicalTrackingId, it) }
			val last = candidates.last()
			beforeStartTimeMs = last.logicalRecencyStartMs
			beforeSegmentId = last.logicalRecencySegmentId
			if (candidates.size < pageLimit) {
				break
			}
		}
		return LocalCellRangeBuild.Ready(accepted.values.toList())
	}

	suspend fun loadRecentLive(limit: Int): LiveCellHistoryPage {
		val accepted = linkedMapOf<String, ComposedCellEntry>()
		var beforeStart: Long? = null
		var beforeId: Long? = null
		var scanned = 0
		while (accepted.size < limit && scanned < MAX_CANDIDATE_SCAN) {
			currentCoroutineContext().ensureActive()
			val pageLimit = minOf(CANDIDATE_PAGE_SIZE, MAX_CANDIDATE_SCAN - scanned)
			val candidates = database.cellCapturedFactDao().logicalHistoryCandidatePage(
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
				SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
				SourceDestinationOwnerEntity.SOURCE_CELL,
				pageLimit,
				beforeStart,
				beforeId,
			)
			if (candidates.isEmpty()) {
				break
			}
			scanned += candidates.size
			val seeds = candidates.map { it.segment }
			val snapshot = snapshots.loadSnapshot(snapshots.expandMembership(seeds))
			if (snapshot.overflow) {
				return LiveCellHistoryPage(
					CellHistoryPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED),
					emptySet(),
				)
			}
			val requested = seeds.mapNotNull(SessionSegment::logicalTrackingId).toSet()
			CellHistoryComposer.composeRecent(snapshot, laneExecutionAuthority)
				.filter { it.logicalTrackingId in requested }
				.forEach { accepted.putIfAbsent(it.logicalTrackingId, it) }
			val last = candidates.last()
			beforeStart = last.logicalRecencyStartMs
			beforeId = last.logicalRecencySegmentId
			if (candidates.size < pageLimit) {
				break
			}
		}
		return buildRecentLivePage(accepted, limit, scanned)
	}

	private fun buildRecentLivePage(
		accepted: Map<String, ComposedCellEntry>, limit: Int, scanned: Int,
	): LiveCellHistoryPage {
		if (accepted.size < limit && scanned >= MAX_CANDIDATE_SCAN) {
			return LiveCellHistoryPage(
				CellHistoryPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED),
				emptySet(),
			)
		}
		val selected = accepted.values.sortedWith(
			compareByDescending<ComposedCellEntry> { it.recencyStartTimeMs }
				.thenByDescending { it.recencySegmentId },
		).take(limit)
		return LiveCellHistoryPage(
			CellHistoryPage.Available(selected.map(ComposedCellEntry::entry)),
			selected.mapTo(linkedSetOf(), ComposedCellEntry::logicalTrackingId),
		)
	}
}

/** Selected native/imported details and their deletion outcomes. */
private class CellHistoryDetailReader(
	private val database: AppDatabase, private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val ioDispatcher: CoroutineDispatcher, private val importedProductReader: ImportedCellProductReader,
	private val snapshots: CellHistorySnapshotReader,
) {
	/** Bounded selected import cohort; exact local equality alone suppresses a visible duplicate. */
	suspend fun selectedImportedInTransaction(
		identities: List<String>,
		range: com.adsamcik.tracker.stats.api.repository.HistoryRange,
	): Map<String, CellHistoryEntry> {
		require(identities.size in 1..MAX_RESULTS && identities.distinct().size == identities.size)
		val evaluations = identities.map { identity ->
			currentCoroutineContext().ensureActive()
			importedProductReader.selectIdentityInTransaction(PortableCellOpaqueIdentity(identity))
				?: throw ImportedCellHistoryCompositionFailure()
		}
		val collisions = evaluations.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.filter { it.localOriginHandle != null }
		val localPortable = collisions.associate { evaluation ->
			currentCoroutineContext().ensureActive()
			evaluation.candidate.identity to selectedLocalPortable(evaluation)
		}
		val visible = database.nativeLogicalIdsVisibleInRange(collisions.mapTo(linkedSetOf()) {
			requireNotNull(importedProductReader.localOriginLogicalTrackingId(it)) }, range)
		val visibleIdentities = visible.mapTo(linkedSetOf()) { logicalId ->
			PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.LOGICAL_ENTRY, logicalId).value }
		val composed = CellHistoryOriginComposer.compose(emptyList(), visibleIdentities,
			collisions.mapTo(linkedSetOf()) { it.candidate.identity }, evaluations, localPortable,
			identities.size)
		val byIdentity = evaluations.associateBy { it.candidate.identity }
		return composed.associate { entry ->
			val selected = entry.selection as? ImportedCellHistorySelection
				?: throw ImportedCellHistoryCompositionFailure()
			selected.identity.value to database.withImportedCellDetails(
				requireNotNull(byIdentity[selected.identity.value]), entry)
		}
	}

	private suspend fun selectedLocalPortable(
		evaluation: ImportedCellProductEvaluation.Readable,
	): PortableCapturedCellEntryV1 =
		(importedProductReader.readLocalOriginInTransaction(evaluation, laneExecutionAuthority)
			as? ReadLocalPortableCapturedCellResult.Ready)?.entry ?: throw ImportedCellHistoryCompositionFailure()

	suspend fun local(selection: LocalCellHistorySelection): CellHistoryQuery = withContext(ioDispatcher) {
		database.withTransaction { localInTransaction(selection) }
	}

	private suspend fun localInTransaction(selection: LocalCellHistorySelection): CellHistoryQuery {
		val logicalIds = database.importedCellDao().liveLogicalTrackingIds(ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1)
		if (logicalIds.size > ImportedCellDao.MAX_LIVE_OWNER_ROWS) {
			return invalidLocal(selection, CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val matching = logicalIds.filter { logicalId ->
			PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.LOGICAL_ENTRY, logicalId).value ==
				selection.identity.value }
		if (matching.isEmpty()) { return CellHistoryQuery.NotFound }
		if (matching.size != 1) { return invalidLocal(selection, CellHistoryCause.ORIGIN_IDENTITY_CONFLICT) }
		return localLogicalEntry(matching.single(), selection)
	}

	private suspend fun localLogicalEntry(logicalId: String, selection: LocalCellHistorySelection): CellHistoryQuery {
		val runs = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
			listOf(logicalId), MAX_LOGICAL_MEMBERS + 1, null, null, null)
		if (runs.size > MAX_LOGICAL_MEMBERS) { return invalidLocal(selection, CellHistoryCause.READ_BUDGET_EXCEEDED) }
		val run = runs.maxWithOrNull(compareBy(SourceServiceRunEntity::startedAtMs,
            SourceServiceRunEntity::serviceRunId))
			?: return invalidLocal(selection)
		val segmentId = run.sessionSegmentId ?: return invalidLocal(selection)
		return localSegment(logicalId, run, segmentId, selection)
	}

	private suspend fun localSegment(
		logicalId: String, run: SourceServiceRunEntity, segmentId: Long, selection: LocalCellHistorySelection,
	): CellHistoryQuery {
		val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
			?: return deletedLocal(logicalId, selection)
		if (seed.id != segmentId || seed.logicalTrackingId != logicalId || seed.serviceRunId != run.serviceRunId) {
			return invalidLocal(selection)
		}
		val snapshot = snapshots.loadSnapshot(snapshots.expandMembership(listOf(seed)))
		val entry = CellHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
			?: return invalidLocal(selection)
		return if (entry.selection == selection) { CellHistoryQuery.Found(entry) } else { invalidLocal(selection) }
	}

	private suspend fun deletedLocal(logicalId: String, selection: LocalCellHistorySelection): CellHistoryQuery {
		val deleted = try {
			database.authenticateDeletedCapturedCellSelectionInTransaction(
				logicalId, PortableCellOpaqueIdentity(selection.identity.value))
		} catch (_: RuntimeException) { DeletedCapturedCellSelectionAuthentication.Unverifiable }
		return when (deleted) {
			DeletedCapturedCellSelectionAuthentication.Absent -> invalidLocal(selection)
			DeletedCapturedCellSelectionAuthentication.Unverifiable ->
				invalidLocal(selection, CellHistoryCause.FACT_INTEGRITY_FAILED)
			is DeletedCapturedCellSelectionAuthentication.Exact ->
				CellHistoryQuery.Found(deletedLocalSelection(selection, deleted.receipt))
		}
	}

	private fun invalidLocal(
		selection: LocalCellHistorySelection, cause: CellHistoryCause = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID,
	): CellHistoryQuery = CellHistoryQuery.Found(unverifiableLocalSelection(selection, cause))

	/** Existing capture proof inputs, scoped to one current parent rather than a complete logical entry. */
	suspend fun measuredSnapshotInTransaction(factId: String): CellHistorySnapshot? {
		require(factId.isNotBlank())
		val cursor = database.cellCapturedFactDao().cursor(SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION, factId) ?: return null
		val seed = database.trackingHistoryReadDao().segments(listOf(cursor.sessionSegmentId)).singleOrNull() ?:
            return null
		return snapshots.loadSnapshot(CellMembershipExpansion(listOf(seed), emptyMap()), factId)
	}

	suspend fun sessionInTransaction(segmentId: Long): CellHistoryQuery {
		require(segmentId > 0L)
		val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
			?: return CellHistoryQuery.NotFound
		val snapshot = snapshots.loadSnapshot(snapshots.expandMembership(listOf(seed)))
		return CellHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
			?.let(CellHistoryQuery::Found) ?: CellHistoryQuery.NotFound
	}

	suspend fun imported(selection: ImportedCellHistorySelection): CellHistoryQuery = withContext(ioDispatcher) {
		try { database.withTransaction { importedInTransaction(selection) } }
		catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
		catch (_: RuntimeException) {
			CellHistoryQuery.Found(unverifiableImportedSelection(selection,
                CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE))
		}
	}

	private suspend fun importedInTransaction(selection: ImportedCellHistorySelection): CellHistoryQuery {
		val evaluation = importedProductReader.selectIdentityInTransaction(
			PortableCellOpaqueIdentity(selection.identity.value))
			?: return deletedImported(selection)
		if (evaluation.candidate.importRevision != selection.importRevision ||
			evaluation.candidate.contentChecksum != selection.contentChecksum.value) {
			return CellHistoryQuery.Found(unverifiableImportedSelection(selection,
                CellHistoryCause.IMPORTED_SELECTION_STALE))
		}
		val conflict = importedOriginConflict(evaluation)
		return CellHistoryQuery.Found(database.withImportedCellDetails(evaluation, evaluation.toPublicCellEntry(
			overrideFailure = CellHistoryCause.ORIGIN_IDENTITY_CONFLICT.takeIf { conflict })))
	}

	private suspend fun deletedImported(selection: ImportedCellHistorySelection): CellHistoryQuery =
		when (val deleted = database.authenticateDeletedImportedCellSelectionInTransaction(
			PortableCellOpaqueIdentity(selection.identity.value), selection.importRevision,
			com.adsamcik.tracker.shared.base.database.PortableCellDigest(selection.contentChecksum.value))) {
			DeletedImportedCellSelectionAuthentication.Absent -> CellHistoryQuery.NotFound
			is DeletedImportedCellSelectionAuthentication.Exact -> CellHistoryQuery.Found(deletedImportedSelection(
				selection, deleted.receipt.startTimeMs, deleted.receipt.endTimeMs))
			DeletedImportedCellSelectionAuthentication.Stale -> CellHistoryQuery.Found(unverifiableImportedSelection(
				selection, CellHistoryCause.IMPORTED_SELECTION_STALE))
			DeletedImportedCellSelectionAuthentication.Unverifiable ->
                CellHistoryQuery.Found(unverifiableImportedSelection(
				selection, CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE))
		}

	private suspend fun importedOriginConflict(evaluation: ImportedCellProductEvaluation): Boolean = when (evaluation) {
		is ImportedCellProductEvaluation.Readable -> evaluation.localOriginHandle?.let {
			when (val local = importedProductReader.readLocalOriginInTransaction(evaluation, laneExecutionAuthority)) {
				is ReadLocalPortableCapturedCellResult.Ready -> local.entry != evaluation.entry
				is ReadLocalPortableCapturedCellResult.Outcome -> true
				null -> false
			}
		} == true
		is ImportedCellProductEvaluation.Unverifiable -> evaluation.localOriginHandle != null
	}

}

/** Origin reconciliation for recent and range queries within the caller's snapshot. */
private class CellHistoryMergeReader(
	private val database: AppDatabase, private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val importedProductReader: ImportedCellProductReader, private val native: CellNativeHistoryReader,
) {
	suspend fun recentCellHistoryInTransaction(limit: Int): CellSourceComposedPage {
		require(limit in 1..MAX_RESULTS)
		val local = when (val page = native.loadRecentCellCompositions(limit) { true }) {
			is CellComposedPage.Failed -> return CellSourceComposedPage.Failed(page.cause)
			is CellComposedPage.Available -> page.entries
		}
		local.asSequence().flatMap { it.entry.causes.asSequence() }.firstOrNull { it.isIntegrityFailure }
			?.let { return CellSourceComposedPage.Failed(it) }
		return try {
			composeSourceRecent(local, importedProductReader.selectRecentInTransaction(limit), limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
		catch (_: RuntimeException) { CellSourceComposedPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE) }
	}

	private suspend fun composeSourceRecent(
		local: List<ComposedCellEntry>, imported: List<ImportedCellProductEvaluation>, limit: Int,
	): CellSourceComposedPage {
		val failure = importedSourceFailure(imported)
		return when {
			failure != null -> CellSourceComposedPage.Failed(failure)
			local.isEmpty() && imported.isEmpty() -> CellSourceComposedPage.Available(emptyList())
			else -> composeAvailableSourceRecent(local, imported, limit)
		}
	}

	private suspend fun composeAvailableSourceRecent(
		local: List<ComposedCellEntry>, imported: List<ImportedCellProductEvaluation>, limit: Int,
	): CellSourceComposedPage {
		val collisions = imported.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.filter { it.localOriginHandle != null }.associateBy { it.candidate.identity }
		if (collisions.size > MAX_RECENT_LOCAL_ORIGIN_COMPARISONS) {
			return CellSourceComposedPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val localPortable = loadLocalPortableCollisions(collisions)
		val localBySelection = localSelections(local)
		val recencies = when (val loaded = importedRecencies(imported)) {
			is CellImportedRecencies.Failed -> return CellSourceComposedPage.Failed(loaded.cause)
			is CellImportedRecencies.Ready -> loaded.bySelection
		}
		val visible = local.mapTo(linkedSetOf()) {
			PortableCellOpaqueIdentity.derive(PortableCellIdentityKind.LOGICAL_ENTRY, it.logicalTrackingId).value }
		val entries = CellHistoryOriginComposer.compose(local.map(ComposedCellEntry::entry), visible,
			imported.filter { it.localOriginHandle != null }.mapTo(linkedSetOf()) { it.candidate.identity },
			imported, localPortable, local.size + imported.size)
		return CellSourceComposedPage.Available(combineSourceEntries(entries, localBySelection, imported, recencies)
			.sortedWith(cellSourceCompositionOrder).take(limit))
	}

	private fun importedSourceFailure(imported: List<ImportedCellProductEvaluation>): CellHistoryCause? =
		if (imported.any { it is ImportedCellProductEvaluation.Unverifiable &&
			it.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW }) {
			CellHistoryCause.READ_BUDGET_EXCEEDED
		} else { imported.filterIsInstance<ImportedCellProductEvaluation.Unverifiable>().firstOrNull()?.toSourceFailure() }

	private fun localSelections(local: List<ComposedCellEntry>): Map<CellHistoryEntrySelection, ComposedCellEntry> {
		val bySelection = local.mapNotNull { entry -> entry.entry.selection?.let { it to entry } }.toMap()
		if (bySelection.size != local.size) { throw ImportedCellHistoryCompositionFailure() }
		return bySelection
	}

	private fun importedRecencies(imported: List<ImportedCellProductEvaluation>): CellImportedRecencies {
		val recencies = linkedMapOf<ImportedCellHistorySelection, CellSourceRecency>()
		for (evaluation in imported) {
			val readable = evaluation as? ImportedCellProductEvaluation.Readable
				?: return CellImportedRecencies.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
			val newest = readable.entry.runs.maxWithOrNull(
				compareBy<PortableCapturedCellRunV1>(PortableCapturedCellRunV1::startTimeMs, { it.identity.value }))
				?: return CellImportedRecencies.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
			if (recencies.put(importedSelection(readable), CellSourceRecency(newest.startTimeMs,
                newest.identity)) != null) {
				return CellImportedRecencies.Failed(CellHistoryCause.ORIGIN_IDENTITY_CONFLICT)
			}
		}
		return CellImportedRecencies.Ready(recencies)
	}

	private suspend fun combineSourceEntries(
		entries: List<CellHistoryEntry>, local: Map<CellHistoryEntrySelection, ComposedCellEntry>,
		imported: List<ImportedCellProductEvaluation>, recencies: Map<ImportedCellHistorySelection, CellSourceRecency>,
	): List<CellSourceComposedEntry> {
		val bySelection = imported.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.associateBy(::importedSelection)
		return entries.map { entry -> when (val origin = entry.origin) {
			CellHistoryOrigin.Local -> CellSourceComposedEntry.Local(
				local[entry.selection] ?: throw ImportedCellHistoryCompositionFailure())
			is CellHistoryOrigin.Imported -> CellSourceComposedEntry.Imported(
				entry = bySelection[origin.selection]?.let { database.withImportedCellDetails(it, entry) } ?: entry,
				selection = origin.selection,
				recency = recencies[origin.selection] ?: throw ImportedCellHistoryCompositionFailure())
		} }
	}

	private fun importedSelection(readable: ImportedCellProductEvaluation.Readable): ImportedCellHistorySelection =
		ImportedCellHistorySelection(ImportedCellHistoryIdentity(readable.candidate.identity),
			readable.candidate.importRevision, ImportedCellHistoryDigest(readable.candidate.contentChecksum))

	suspend fun composeRange(scope: CellHistoryRangeScope): CellHistoryRangeBuild {
		val bounds = try { scope.queryBounds() }
		catch (_: ArithmeticException) { return CellHistoryRangeBuild.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED) }
		val live = when (val result = native.loadLocalRange(bounds)) {
			is LocalCellRangeBuild.Failed -> return CellHistoryRangeBuild.Failed(result.cause)
			is LocalCellRangeBuild.Ready -> result.entries
		}
		return composeImportedRange(scope, bounds, live)
	}

	private suspend fun composeImportedRange(
		scope: CellHistoryRangeScope, bounds: CellRangeQueryBounds, live: List<ComposedCellEntry>,
	): CellHistoryRangeBuild {
		val imported = try { readImportedRange(scope, bounds) }
		catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
		catch (_: RuntimeException) { return CellHistoryRangeBuild.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE) }
		if (imported.any { it is ImportedCellProductEvaluation.Unverifiable &&
			it.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW }) {
			return CellHistoryRangeBuild.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
		return composeQualifiedRange(scope, live, imported)
	}

	private suspend fun readImportedRange(
		scope: CellHistoryRangeScope, bounds: CellRangeQueryBounds,
	): List<ImportedCellProductEvaluation> = when (scope) {
		is CellHistoryRangeScope.WallTime -> importedProductReader.selectWallRangeInTransaction(
			bounds.fromInclusiveMs, bounds.toExclusiveMs, MAX_RANGE_CANDIDATES_PER_ORIGIN)
		is CellHistoryRangeScope.StructuralDays -> importedProductReader.selectStructuralRangeInTransaction(
			bounds.fromInclusiveMs, bounds.toExclusiveMs, MAX_RANGE_CANDIDATES_PER_ORIGIN)
	}

	private suspend fun composeQualifiedRange(
		scope: CellHistoryRangeScope, live: List<ComposedCellEntry>, imported: List<ImportedCellProductEvaluation>,
	): CellHistoryRangeBuild {
		val memberships = try { structuralMemberships(scope, imported) }
		catch (_: RuntimeException) { return CellHistoryRangeBuild.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE) }
		val collisions = imported.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.filter { it.localOriginHandle != null }.associateBy { it.candidate.identity }
		val localPortable = try { loadLocalPortableCollisions(collisions) }
		catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
		catch (_: RuntimeException) { return CellHistoryRangeBuild.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE) }
		val visible = live.mapNotNull { (it.entry.selection as? LocalCellHistorySelection)?.identity?.value }.toSet()
		val composed = try {
			if (live.isEmpty() && imported.isEmpty()) { emptyList() } else {
				CellHistoryOriginComposer.compose(live.map(ComposedCellEntry::entry), visible,
					imported.filter { it.localOriginHandle != null }.mapTo(linkedSetOf()) { it.candidate.identity },
					imported, localPortable, live.size + imported.size)
			}
		} catch (_: ImportedCellHistoryCompositionFailure) {
			return CellHistoryRangeBuild.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		return CellHistoryRangeBuild.Ready(composed.mapNotNull { it.toRangeEntry(scope, memberships) })
	}

	private fun structuralMemberships(
		scope: CellHistoryRangeScope, imported: List<ImportedCellProductEvaluation>,
	): Map<String, ImportedCellStructuralMembership?> {
		if (scope !is CellHistoryRangeScope.StructuralDays) { return emptyMap() }
		return imported.associate { evaluation ->
			val readable = evaluation as? ImportedCellProductEvaluation.Readable
				?: throw ImportedCellHistoryCompositionFailure()
			readable.candidate.identity to readable.structuralMembership(scope)
		}
	}

	suspend fun composeRecent(limit: Int): CellHistoryPage {
		val live = native.loadRecentLive(limit)
		if (live.page is CellHistoryPage.Failed) {
			return live.page
		}
		return composeRecentImported(live, limit)
	}

	private suspend fun composeRecentImported(live: LiveCellHistoryPage, limit: Int): CellHistoryPage {
		val imported = try {
			importedProductReader.selectRecentInTransaction(limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellHistoryPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val directLocalCollisionIds = imported.filter { it.localOriginHandle != null }
			.mapTo(linkedSetOf()) { it.candidate.identity }
		val localCollisions = imported.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.filter { it.localOriginHandle != null }
			.associateBy { it.candidate.identity }
		if (localCollisions.size > MAX_RECENT_LOCAL_ORIGIN_COMPARISONS) {
			return CellHistoryPage.Failed(CellHistoryCause.READ_BUDGET_EXCEEDED)
		}
		val localPortableEntries = try {
			loadLocalPortableCollisions(localCollisions)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellHistoryPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val visibleLocalEntryIdentities = live.logicalTrackingIds.mapTo(linkedSetOf()) { logicalId ->
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
		}
		return try {
			CellHistoryPage.Available(
				CellHistoryOriginComposer.compose(
					live = (live.page as CellHistoryPage.Available).entries,
					visibleLocalEntryIdentities = visibleLocalEntryIdentities,
					localCollisionIdentities = directLocalCollisionIds,
					imported = imported,
					localPortableEntriesByIdentity = localPortableEntries,
					limit = limit,
				),
			)
		} catch (_: ImportedCellHistoryCompositionFailure) {
			CellHistoryPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	suspend fun loadLocalPortableCollisions(
		evaluationsByEntryIdentity: Map<String, ImportedCellProductEvaluation.Readable>,
	): Map<String, PortableCapturedCellEntryV1> {
		if (evaluationsByEntryIdentity.isEmpty()) {
			return emptyMap()
		}
		require(evaluationsByEntryIdentity.size <= MAX_RANGE_CANDIDATES_PER_ORIGIN)
		val entries = linkedMapOf<String, PortableCapturedCellEntryV1>()
		for ((entryIdentity, evaluation) in evaluationsByEntryIdentity) {
			currentCoroutineContext().ensureActive()
			when (val result = importedProductReader.readLocalOriginInTransaction(
				evaluation,
				laneExecutionAuthority,
			)) {
				is ReadLocalPortableCapturedCellResult.Ready -> {
					if (result.entry.identity.value != entryIdentity ||
						entries.put(entryIdentity, result.entry) != null
					) {
						throw ImportedCellHistoryCompositionFailure()
					}
				}
				is ReadLocalPortableCapturedCellResult.Outcome -> Unit
				null -> throw ImportedCellHistoryCompositionFailure()
			}
		}
		return entries
	}
}

/** Bounded imported candidate scan with one cumulative read budget. */
private class CellImportedHistoryScanReader(
	private val database: AppDatabase, private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	private val importedProductReader: ImportedCellProductReader,
	private val sharedHistoryPageCheckpoint: suspend (completedPageCount: Int) -> Unit,
) {
	suspend fun recentImportedEligibleForSharedHistoryInTransaction(
		limit: Int,
	): ImportedHistoryEligiblePage<CellImportedHistoryEligibleEntry> {
		require(limit in 1..MAX_RESULTS)
		return try {
			importedProductReader.withRecentScanInTransaction { scope ->
				recentImportedEligibleInScope(scope, limit)
			}
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (failure: ImportedCellProductScanContextFailure) {
			importedEligibleUnavailable(
				if (failure.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW) {
					SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
				} else {
					SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE
				},
			)
		} catch (_: RuntimeException) {
			importedEligibleUnavailable(
				SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE,
			)
		}
	}

	private suspend fun recentImportedEligibleInScope(
		scope: ImportedCellProductScanScope, limit: Int,
	): ImportedHistoryEligiblePage<CellImportedHistoryEligibleEntry> {
		val state = CellImportedScanState()
		while (true) {
			currentCoroutineContext().ensureActive()
			sharedHistoryPageCheckpoint(state.completedPageCount)
			val (page, pageLimit) = nextImportedPage(scope, state) ?: break
			for (evaluation in page.evaluations) {
				currentCoroutineContext().ensureActive()
				processImportedEvaluation(evaluation, state)?.let { return it }
			}
			if (state.completePage(page, pageLimit, limit)) { break }
		}
		return ImportedHistoryEligiblePage.Available(state.accepted.toList())
	}

	private suspend fun nextImportedPage(
		scope: ImportedCellProductScanScope, state: CellImportedScanState,
	): Pair<ImportedCellProductScanPage, Int>? {
		val remaining = MAX_SHARED_HISTORY_IMPORTED_CANDIDATES - state.scanned
		if (remaining == 0) {
			if (scope.hasRecentCandidateInTransaction(state.beforeStartTimeMs, state.beforeIdentity)) {
				throw ImportedCellProductScanContextFailure(ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
			}
			return null
		}
		val pageLimit = minOf(SHARED_HISTORY_IMPORTED_PAGE_SIZE, remaining)
		val page = scope.selectRecentPageInTransaction(state.remainingReadBudget, pageLimit,
			state.beforeStartTimeMs, state.beforeIdentity)
		if (page.evaluations.isEmpty()) { return null }
		state.consume(page.usage)
		state.scanned += page.evaluations.size
		return page to pageLimit
	}

	private suspend fun processImportedEvaluation(
		evaluation: ImportedCellProductEvaluation, state: CellImportedScanState,
	): ImportedHistoryEligiblePage.Unavailable? = when (evaluation) {
		is ImportedCellProductEvaluation.Readable -> processReadableImport(evaluation, state)
		is ImportedCellProductEvaluation.Unverifiable -> importedEligibleUnavailable(
			if (evaluation.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW) {
				SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
			} else { SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE })
	}

	private suspend fun processReadableImport(
		readable: ImportedCellProductEvaluation.Readable, state: CellImportedScanState,
	): ImportedHistoryEligiblePage.Unavailable? {
		val newest = readable.entry.runs.maxWithOrNull(
			compareBy<PortableCapturedCellRunV1>(PortableCapturedCellRunV1::startTimeMs, { it.identity.value }))
			?:
				return importedEligibleUnavailable(
					SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE)
		if (newest.startTimeMs !in readable.entry.startTimeMs..readable.entry.endTimeMs) {
			return importedEligibleUnavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_RECENCY_AUTHORITY_UNAVAILABLE)
		}
		if (isNativeDuplicate(readable, state)) { return null }
		return appendPublicImport(readable, newest, state)
	}

	private suspend fun isNativeDuplicate(
		readable: ImportedCellProductEvaluation.Readable, state: CellImportedScanState,
	): Boolean {
		if (readable.localOriginHandle == null) { return false }
		state.localOriginComparisons += 1
		if (state.localOriginComparisons > MAX_SHARED_HISTORY_LOCAL_ORIGIN_COMPARISONS) {
			throw ImportedCellProductScanContextFailure(ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
		}
		val local = importedProductReader.readLocalOriginInTransaction(readable, laneExecutionAuthority)
		check(local is ReadLocalPortableCapturedCellResult.Ready)
		val exact = PortableCellOriginComparison.areExactFullV1Duplicates(local.entry, readable.entry)
		check(exact)
		return exact && readable.isReExportable
	}

	private suspend fun appendPublicImport(
		readable: ImportedCellProductEvaluation.Readable, newest: PortableCapturedCellRunV1,
            state: CellImportedScanState,
	): ImportedHistoryEligiblePage.Unavailable? {
		val entry = database.withImportedCellDetails(readable, readable.toPublicCellEntry())
		publicEntryFailure(entry)?.let { return importedEligibleUnavailable(it) }
		state.consume(ImportedCellProductReadUsage(0, 0, 0, 0, entry.observations.size, 0L))
		val carrier = try {
			CellImportedHistoryEligibleEntry(entry,
				ImportedCellHistorySelection(ImportedCellHistoryIdentity(readable.candidate.identity),
					readable.candidate.importRevision, ImportedCellHistoryDigest(readable.candidate.contentChecksum)),
				ImportedHistoryRecency(HistorySource.CELL, newest.startTimeMs,
					ImportedHistoryRecencyTieIdentity(newest.identity.value)))
		} catch (_: IllegalArgumentException) {
			return importedEligibleUnavailable(SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE)
		}
		state.accepted += carrier
		return null
	}

	private fun publicEntryFailure(entry: CellHistoryEntry): SourceAwareHistoryPageUnavailableReason? =
		if (entry.state == CellHistoryProductState.UNVERIFIABLE || entry.state == CellHistoryProductState.FAILED ||
			entry.causes.any { it.isIntegrityFailure }) {
			if (CellHistoryCause.READ_BUDGET_EXCEEDED in entry.causes) {
				SourceAwareHistoryPageUnavailableReason.SOURCE_READ_BUDGET_EXCEEDED
			} else { SourceAwareHistoryPageUnavailableReason.SOURCE_INTEGRITY_FAILURE }
		} else { null }

}

private class CellImportedScanState {
	val accepted = mutableListOf<CellImportedHistoryEligibleEntry>()
	var scanned = 0
	var localOriginComparisons = 0
	var beforeStartTimeMs: Long? = null
	var beforeIdentity: String? = null
	var completedPageCount = 0
	var remainingReadBudget = ImportedCellProductReadBudget.sharedHistory()
		private set

	fun consume(usage: ImportedCellProductReadUsage) {
		remainingReadBudget = remainingReadBudget.consume(usage)
			?: throw ImportedCellProductScanContextFailure(ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
	}

	fun completePage(page: ImportedCellProductScanPage, pageLimit: Int, limit: Int): Boolean {
		accepted.sortWith { left, right -> importedHistoryRecencyOrder.compare(left.recency, right.recency) }
		if (accepted.size > limit) { accepted.subList(limit, accepted.size).clear() }
		val last = page.evaluations.last().candidate
		beforeStartTimeMs = last.startTimeMs
		beforeIdentity = last.identity
		completedPageCount += 1
		return page.evaluations.size < pageLimit
	}
}

private sealed interface CellImportedRecencies {
	data class Ready(val bySelection: Map<ImportedCellHistorySelection, CellSourceRecency>) : CellImportedRecencies
	data class Failed(val cause: CellHistoryCause) : CellImportedRecencies
}

private data class CellFactLoad(val revisions: List<CellCapturedFactRevisionEntity>, val overflow: Boolean)

private data class LiveCellHistoryPage(
	val page: CellHistoryPage,
	val logicalTrackingIds: Set<String>,
)

private data class ServiceRunCursor(
	val logicalId: String, val startedAtMs: Long, val serviceRunId: String,
) : Comparable<ServiceRunCursor> {
	constructor(run: SourceServiceRunEntity) : this(run.logicalTrackingId, run.startedAtMs, run.serviceRunId)
	override fun compareTo(other: ServiceRunCursor): Int = compareValuesBy(this, other,
		ServiceRunCursor::logicalId, ServiceRunCursor::startedAtMs, ServiceRunCursor::serviceRunId)
}

private data class FactCursor(
	val projectionId: String, val projectionVersion: Int, val logicalFactId: String,
	val semanticRevision: Long,
) : Comparable<FactCursor> {
	constructor(fact: CellCapturedFactRevisionEntity) : this(fact.writerProjectionId,
		fact.writerProjectionVersion, fact.logicalFactId, fact.semanticRevision)
	override fun compareTo(other: FactCursor): Int = compareValuesBy(this, other,
		FactCursor::projectionId, FactCursor::projectionVersion, FactCursor::logicalFactId,
		FactCursor::semanticRevision)
}

private const val MAX_RESULTS = 100
private const val MAX_RECENT_LOCAL_ORIGIN_COMPARISONS = 4
private const val MAX_SHARED_HISTORY_LOCAL_ORIGIN_COMPARISONS = 4
private const val MAX_SHARED_HISTORY_IMPORTED_CANDIDATES = 256
private const val SHARED_HISTORY_IMPORTED_PAGE_SIZE = 32
private const val MAX_RANGE_CANDIDATES_PER_ORIGIN = 256
private const val CANDIDATE_PAGE_SIZE = 32
private const val MAX_CANDIDATE_SCAN = 128
private const val MEMBER_PAGE_SIZE = 32
private const val MAX_LOGICAL_MEMBERS = 128
private const val FACT_PAGE_SIZE = 256
private const val MAX_FACT_REVISIONS = 4_096
private const val CELL_CHILD_FACT_CHUNK = 32
private const val MAX_CELL_CHILDREN = 256
private const val MAX_HISTORY_CHILDREN = 65_536
private const val MAX_CURSORS = 4_096
private const val MAX_SESSIONS = 128
private const val MAX_MANIFESTS = 512
private const val MAX_MANIFEST_SOURCES = 4_096
private const val MAX_COMPLETENESS = 512
private const val MAX_POLICIES = 512
private const val MAX_CONSENTS = 512
private const val MAX_DELETION_GENERATIONS = 128
private const val MAX_DELETION_FENCES = 128
private const val MAX_LANES = 128
private const val MAX_PLANS = 512
private const val MAX_PROVIDERS = 512
private const val MAX_AUTHORIZATIONS = 4_096
private const val MAX_DEMANDS = 4_096
private const val MAX_WAL_CARRIERS = 4_096
private const val MAX_TERMINAL_FAILURES = 512
private const val SQL_ID_BATCH = 400

private fun unverifiableImportedSelection(
	selection: ImportedCellHistorySelection,
	cause: CellHistoryCause,
) = CellHistoryEntry(
	key = CellHistoryEntryKey("cell-imported:${selection.identity.value}"),
	startTime = EpochMs(0L),
	endTime = EpochMs(0L),
	storedZoneIds = emptySet(),
	state = CellHistoryProductState.UNVERIFIABLE,
	coverage = CellHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(cause),
	origin = CellHistoryOrigin.Imported(selection),
	selection = selection,
)

private fun deletedImportedSelection(
	selection: ImportedCellHistorySelection,
	startTimeMs: Long,
	endTimeMs: Long,
) = CellHistoryEntry(
	key = CellHistoryEntryKey("cell-imported:${selection.identity.value}"),
	startTime = EpochMs(startTimeMs),
	endTime = EpochMs(endTimeMs),
	storedZoneIds = emptySet(),
	state = CellHistoryProductState.DELETED,
	coverage = CellHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(CellHistoryCause.DELETED),
	origin = CellHistoryOrigin.Imported(selection),
	selection = selection,
)

private fun unverifiableLocalSelection(
	selection: LocalCellHistorySelection,
	cause: CellHistoryCause,
) = CellHistoryEntry(
	key = CellHistoryEntryKey("cell-local:${selection.identity.value}"),
	startTime = EpochMs(0L),
	endTime = EpochMs(0L),
	storedZoneIds = emptySet(),
	state = CellHistoryProductState.FAILED,
	coverage = CellHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(cause),
	origin = CellHistoryOrigin.Local,
	selection = selection,
)

private fun deletedLocalSelection(
	selection: LocalCellHistorySelection,
	receipt: CellCapturedEntryDeletionReceiptEntity,
) = CellHistoryEntry(
	key = CellHistoryEntryKey("cell-local:${selection.identity.value}"),
	startTime = EpochMs(receipt.startTimeMs),
	endTime = EpochMs(receipt.endTimeMs),
	storedZoneIds = emptySet(),
	state = CellHistoryProductState.DELETED,
	coverage = CellHistoryCoverage.NONE,
	observations = emptyList(),
	causes = setOf(CellHistoryCause.DELETED),
	origin = CellHistoryOrigin.Local,
	selection = selection,
)

private sealed interface CellHistoryRangeBuild {
	data class Ready(val entries: List<CellHistoryRangeEntry>) : CellHistoryRangeBuild
	data class Failed(val cause: CellHistoryCause) : CellHistoryRangeBuild
}

private sealed interface LocalCellRangeBuild {
	data class Ready(val entries: List<ComposedCellEntry>) : LocalCellRangeBuild
	data class Failed(val cause: CellHistoryCause) : LocalCellRangeBuild
}

private data class CellRangeQueryBounds(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
)

private data class ImportedCellStructuralMembership(
	val days: Set<CellHistoryStructuralDay>,
	val completeness: CellHistoryStructuralDayCompleteness,
)

private fun ImportedCellProductEvaluation.Readable.structuralMembership(
	scope: CellHistoryRangeScope.StructuralDays,
): ImportedCellStructuralMembership? {
	if (entryDeleted || retentionLimited) { return null }
	val visibleRuns = entry.runs.filterNot { it.identity.value in deletedRunIdentities }
	val collected = CellStructuralDayAccumulator(deletedRunIdentities.isNotEmpty() ||
        visibleRuns.any { it.retentionLoss })
	for (observation in visibleRuns.flatMap { it.observations }) { collected.addImported(observation, scope) }
	return collected.days.takeIf { it.isNotEmpty() }?.let {
		ImportedCellStructuralMembership(it, collected.completeness()) }
}

/** Shared day projection preserving original observation bounds and source-specific gap handling. */
private class CellStructuralDayAccumulator(initialPartial: Boolean = false) {
	val days = linkedSetOf<CellHistoryStructuralDay>()
	private var ambiguous = false
	private var partial = initialPartial

	fun addImported(observation: PortableCapturedCellObservationV1, scope: CellHistoryRangeScope.StructuralDays) {
		val earliest = minOf(observation.coverageStartTimeMs,
			Math.subtractExact(observation.observedTimeMs, observation.wallTimeUncertaintyMs))
		val latest = Math.addExact(observation.observedTimeMs, observation.wallTimeUncertaintyMs)
		val zone = ZoneId.of(observation.storedZoneId)
		val first = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate().toEpochDay()
		val last = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate().toEpochDay()
		if (last < first || last - first >= MAX_STRUCTURAL_DAYS_PER_RANGE_ENTRY) {
			throw ImportedCellHistoryCompositionFailure()
		}
		include(first, last, observation.storedZoneId, scope, limitIncludedSpan = false)
	}

	fun addNative(observation: CellHistoryObservation, scope: CellHistoryRangeScope) {
		try {
			val earliest = minOf(observation.intervalStartTime.raw,
				Math.subtractExact(observation.observedTime.raw, observation.wallTimeUncertaintyMs))
			val latest = Math.addExact(observation.observedTime.raw, observation.wallTimeUncertaintyMs)
			val zone = ZoneId.of(observation.storedZoneId)
			val first = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate().toEpochDay()
			val last = Instant.ofEpochMilli(latest).atZone(zone).toLocalDate().toEpochDay()
			if (last < first) { partial = true } else {
				include(first, last, observation.storedZoneId, scope, limitIncludedSpan = true)
			}
		} catch (_: RuntimeException) { partial = true }
	}

	private fun include(
		first: Long, last: Long, zoneId: String, scope: CellHistoryRangeScope, limitIncludedSpan: Boolean,
	) {
		if (last > first) { ambiguous = true }
		val firstIncluded = clipFirst(scope, first)
		val lastIncluded = clipLast(scope, last)
		if (lastIncluded < firstIncluded) { return }
		if (limitIncludedSpan && lastIncluded - firstIncluded >= MAX_STRUCTURAL_DAYS_PER_RANGE_ENTRY) {
			partial = true
			return
		}
		appendDays(firstIncluded, lastIncluded, zoneId)
	}

	private fun clipFirst(scope: CellHistoryRangeScope, day: Long): Long = when (scope) {
		is CellHistoryRangeScope.StructuralDays -> maxOf(day, scope.firstEpochDay)
		is CellHistoryRangeScope.WallTime -> day
	}

	private fun clipLast(scope: CellHistoryRangeScope, day: Long): Long = when (scope) {
		is CellHistoryRangeScope.StructuralDays -> minOf(day, scope.lastEpochDayInclusive)
		is CellHistoryRangeScope.WallTime -> day
	}

	private fun appendDays(first: Long, last: Long, zoneId: String) {
		var day = first
		while (day <= last) {
			days += CellHistoryStructuralDay(day, zoneId)
			if (day == Long.MAX_VALUE) { break }
			day += 1L
		}
	}

	fun completeness(): CellHistoryStructuralDayCompleteness = when {
		days.isEmpty() -> CellHistoryStructuralDayCompleteness.UNAVAILABLE
		partial -> CellHistoryStructuralDayCompleteness.PARTIAL
		ambiguous && days.size > 1 -> CellHistoryStructuralDayCompleteness.AMBIGUOUS
		ambiguous -> CellHistoryStructuralDayCompleteness.PARTIAL
		else -> CellHistoryStructuralDayCompleteness.EXACT
	}
}

private class CellHistoryRangeContinuationSnapshot(
	val scope: CellHistoryRangeScope,
	val remaining: List<CellHistoryRangeEntry>,
	val issuer: Any,
) : CellHistoryRangeContinuation {
	override fun toString(): String = "CellHistoryRangeContinuation"
}

private fun List<CellHistoryRangeEntry>.toRangePage(
	scope: CellHistoryRangeScope,
	limit: Int,
	issuer: Any,
): CellHistoryRangePage {
	val page = take(limit)
	val remaining = drop(limit)
	return CellHistoryRangePage.Available(
		entries = page,
		continuation = remaining.takeIf(List<CellHistoryRangeEntry>::isNotEmpty)?.let {
			CellHistoryRangeContinuationSnapshot(scope, it, issuer)
		},
	)
}

private fun CellHistoryRangeScope.queryBounds(): CellRangeQueryBounds = when (this) {
	is CellHistoryRangeScope.WallTime -> CellRangeQueryBounds(
		fromInclusiveMs = fromInclusive.raw,
		toExclusiveMs = toExclusive.raw,
	)
	is CellHistoryRangeScope.StructuralDays -> {
		val firstDayStart = Math.multiplyExact(firstEpochDay, MILLIS_PER_DAY)
		val lastDayEnd = Math.multiplyExact(
			Math.addExact(lastEpochDayInclusive, 1L),
			MILLIS_PER_DAY,
		)
		CellRangeQueryBounds(
			fromInclusiveMs = maxOf(0L, firstDayStart - MAX_ZONE_OFFSET_MILLIS),
			toExclusiveMs = Math.addExact(lastDayEnd, MAX_ZONE_OFFSET_MILLIS),
		)
	}
}

private fun List<CellLogicalHistoryCandidate>.hasValidRangeKeyset(
	beforeStartTimeMs: Long?,
	beforeSegmentId: Long?,
): Boolean {
	if ((beforeStartTimeMs == null) != (beforeSegmentId == null) ||
		mapNotNull { it.segment.logicalTrackingId }.distinct().size != size
	) {
		return false
	}
	val cursors = buildList {
		if (beforeStartTimeMs != null && beforeSegmentId != null) {
			add(beforeStartTimeMs to beforeSegmentId)
		}
		addAll(this@hasValidRangeKeyset.map { it.logicalRecencyStartMs to it.logicalRecencySegmentId })
	}
	return cursors.zipWithNext().all { (left, right) ->
		left.first > right.first || left.first == right.first && left.second > right.second
	}
}

private fun CellHistoryEntry.toRangeEntry(
	scope: CellHistoryRangeScope, importedStructuralMemberships: Map<String, ImportedCellStructuralMembership?>,
): CellHistoryRangeEntry? {
	if (isOutsideWallRange(scope)) { return null }
	val imported = origin as? CellHistoryOrigin.Imported
	if (scope is CellHistoryRangeScope.StructuralDays && imported != null) {
		return importedRangeEntry(imported, importedStructuralMemberships)
	}
	return nativeRangeEntry(scope)
}

private fun CellHistoryEntry.isOutsideWallRange(scope: CellHistoryRangeScope): Boolean =
	scope is CellHistoryRangeScope.WallTime &&
		(startTime.raw >= scope.toExclusive.raw || endTime.raw <= scope.fromInclusive.raw)

private fun CellHistoryEntry.importedRangeEntry(
	origin: CellHistoryOrigin.Imported, memberships: Map<String, ImportedCellStructuralMembership?>,
): CellHistoryRangeEntry? {
	val identity = origin.selection.identity.value
	if (!memberships.containsKey(identity)) { throw ImportedCellHistoryCompositionFailure() }
	val membership = memberships[identity] ?: return null
	return CellHistoryRangeEntry(this, membership.days, membership.completeness)
}

private fun CellHistoryEntry.nativeRangeEntry(scope: CellHistoryRangeScope): CellHistoryRangeEntry? {
	if (observations.isEmpty()) {
		return if (scope is CellHistoryRangeScope.WallTime) {
			CellHistoryRangeEntry(this, emptySet(), CellHistoryStructuralDayCompleteness.UNAVAILABLE)
		} else { null }
	}
	val collected = CellStructuralDayAccumulator()
	observations.forEach { collected.addNative(it, scope) }
	return if (scope is CellHistoryRangeScope.StructuralDays && collected.days.isEmpty()) { null } else {
		CellHistoryRangeEntry(this, collected.days, collected.completeness())
	}
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val MAX_ZONE_OFFSET_MILLIS = 18L * 60L * 60L * 1_000L
private const val MAX_STRUCTURAL_DAYS_PER_RANGE_ENTRY = 370L

internal sealed interface CellComposedPage {
	data class Available(val entries: List<ComposedCellEntry>) : CellComposedPage
	data class Failed(val cause: CellHistoryCause) : CellComposedPage
}

internal sealed interface CellSourceComposedEntry {
	val entry: CellHistoryEntry
	val recency: CellSourceRecency

	data class Local(
		val group: ComposedCellEntry,
	) : CellSourceComposedEntry {
		override val entry: CellHistoryEntry get() = group.entry
		override val recency: CellSourceRecency = CellSourceRecency(
			memberStartTimeMs = group.recencyStartTimeMs,
			tieIdentity = group.recencyTieIdentity,
		)
	}

	data class Imported(
		override val entry: CellHistoryEntry,
		val selection: ImportedCellHistorySelection,
		override val recency: CellSourceRecency,
	) : CellSourceComposedEntry {
		init {
			require(entry.origin == CellHistoryOrigin.Imported(selection))
			require(entry.selection == selection)
		}
	}
}

internal sealed interface CellSourceComposedPage {
	data class Available(
		val entries: List<CellSourceComposedEntry>,
	) : CellSourceComposedPage

	data class Failed(val cause: CellHistoryCause) : CellSourceComposedPage
}

private fun importedEligibleUnavailable(
	reason: SourceAwareHistoryPageUnavailableReason,
): ImportedHistoryEligiblePage.Unavailable = ImportedHistoryEligiblePage.Unavailable(reason)

/** Authenticated newest physical member ordering without exposing its native database identity. */
internal data class CellSourceRecency(
	val memberStartTimeMs: Long,
	val tieIdentity: PortableCellOpaqueIdentity,
) {
	init {
		require(memberStartTimeMs >= 0L)
	}
}

internal val cellCompositionOrder =
	compareByDescending<ComposedCellEntry> { it.recencyStartTimeMs }
		.thenByDescending { it.recencySegmentId }

internal val cellSourceCompositionOrder =
	compareByDescending<CellSourceComposedEntry> { it.recency.memberStartTimeMs }
		.thenByDescending { it.recency.tieIdentity.value }
		.thenBy { it is CellSourceComposedEntry.Imported }

private fun ImportedCellProductEvaluation.Unverifiable.toSourceFailure(): CellHistoryCause =
	if (localOriginHandle != null) {
		CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
	} else {
		when (reason) {
			ImportedCellProductFailure.DEPENDENCY_OVERFLOW ->
				CellHistoryCause.READ_BUDGET_EXCEEDED
			ImportedCellProductFailure.STALE_COLLECTED_DATA_EPOCH ->
				CellHistoryCause.IMPORTED_PRIVACY_EPOCH_MISMATCH
			ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT ->
				CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
			ImportedCellProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
			ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			ImportedCellProductFailure.VALUE_OVERFLOW,
			-> CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE
		}
	}

internal fun isCellCaptureMembership(source: SessionManifestSourceEntity): Boolean =
	source.sourceKind == CELL_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		source.persistenceEligible
