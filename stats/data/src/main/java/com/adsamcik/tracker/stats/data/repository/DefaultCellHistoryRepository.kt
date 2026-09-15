package com.adsamcik.tracker.stats.data.repository

import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.ImportedCellProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedCellProductReader
import com.adsamcik.tracker.shared.base.database.PortableCapturedCellEntryV1
import com.adsamcik.tracker.shared.base.database.PortableCellIdentityKind
import com.adsamcik.tracker.shared.base.database.PortableCellOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.ReadLocalPortableCapturedCellResult
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.LogicalTrackingSessionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SessionManifestSourceEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestVersionEntity
import com.adsamcik.tracker.shared.base.database.data.SessionSegment
import com.adsamcik.tracker.shared.base.database.data.SourceConsentEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDemandEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDesiredPlanEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourcePolicyEntity
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import com.adsamcik.tracker.shared.base.database.data.SourceServiceRunEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.CellHistoryCause
import com.adsamcik.tracker.stats.api.repository.CellHistoryCoverage
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntry
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.CellHistoryEntrySelection
import com.adsamcik.tracker.stats.api.repository.CellHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.CellHistoryPage
import com.adsamcik.tracker.stats.api.repository.CellHistoryProductState
import com.adsamcik.tracker.stats.api.repository.CellHistoryQuery
import com.adsamcik.tracker.stats.api.repository.CellHistoryRepository
import com.adsamcik.tracker.stats.api.repository.ImportedCellHistorySelection
import com.adsamcik.tracker.stats.api.repository.LocalCellHistorySelection
import com.adsamcik.tracker.stats.api.value.EpochMs
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-local Cell facade. Every dependency for a call is read in one bounded Room transaction. */
internal class DefaultCellHistoryRepository @Inject constructor(
	private val database: AppDatabase,
	private val laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CellHistoryRepository {
	private val importedProductReader = ImportedCellProductReader(database)

	override suspend fun detail(selection: CellHistoryEntrySelection): CellHistoryQuery =
		when (selection) {
			is LocalCellHistorySelection -> local(selection)
			is ImportedCellHistorySelection -> imported(selection)
			else -> CellHistoryQuery.NotFound
		}

	private suspend fun local(selection: LocalCellHistorySelection): CellHistoryQuery =
		withContext(ioDispatcher) {
			database.withTransaction {
				val logicalIds = database.importedCellDao().liveLogicalTrackingIds(
					ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1,
				)
				if (logicalIds.size > ImportedCellDao.MAX_LIVE_OWNER_ROWS) {
					return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(selection, CellHistoryCause.READ_BUDGET_EXCEEDED),
					)
				}
				val matching = logicalIds.filter { logicalId ->
					PortableCellOpaqueIdentity.derive(
						PortableCellIdentityKind.LOGICAL_ENTRY,
						logicalId,
					).value == selection.identity.value
				}
				if (matching.isEmpty()) return@withTransaction CellHistoryQuery.NotFound
				if (matching.size != 1) {
					return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(selection, CellHistoryCause.ORIGIN_IDENTITY_CONFLICT),
					)
				}
				val logicalId = matching.single()
				val runs = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
					listOf(logicalId),
					MAX_LOGICAL_MEMBERS + 1,
					null,
					null,
					null,
				)
				if (runs.size > MAX_LOGICAL_MEMBERS) {
					return@withTransaction CellHistoryQuery.Found(
						unverifiableLocalSelection(selection, CellHistoryCause.READ_BUDGET_EXCEEDED),
					)
				}
				val segmentId = runs.firstNotNullOfOrNull(SourceServiceRunEntity::sessionSegmentId)
					?: return@withTransaction CellHistoryQuery.NotFound
				val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
					?: return@withTransaction CellHistoryQuery.NotFound
				val snapshot = loadSnapshot(expandMembership(listOf(seed)))
				CellHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
					?.let(CellHistoryQuery::Found) ?: CellHistoryQuery.NotFound
			}
		}

	override suspend fun session(segmentId: Long): CellHistoryQuery {
		require(segmentId > 0L)
		return withContext(ioDispatcher) {
			database.withTransaction {
				val seed = database.trackingHistoryReadDao().segments(listOf(segmentId)).singleOrNull()
					?: return@withTransaction CellHistoryQuery.NotFound
				val snapshot = loadSnapshot(expandMembership(listOf(seed)))
				CellHistoryComposer.composeSelected(seed, snapshot, laneExecutionAuthority)
					?.let(CellHistoryQuery::Found) ?: CellHistoryQuery.NotFound
			}
		}
	}

	override suspend fun imported(selection: ImportedCellHistorySelection): CellHistoryQuery =
		withContext(ioDispatcher) {
			try {
				database.withTransaction {
					val evaluation = importedProductReader.selectIdentityInTransaction(
						PortableCellOpaqueIdentity(selection.identity.value),
					) ?: return@withTransaction CellHistoryQuery.NotFound
					if (evaluation.candidate.importRevision != selection.importRevision ||
						evaluation.candidate.contentChecksum != selection.contentChecksum.value
					) {
						return@withTransaction CellHistoryQuery.Found(
							unverifiableImportedSelection(
								selection,
								CellHistoryCause.IMPORTED_SELECTION_STALE,
							),
						)
					}
					val readable = evaluation as? ImportedCellProductEvaluation.Readable
					val originConflict = readable?.let { imported ->
						when (val local = importedProductReader.readLocalOriginInTransaction(
							imported,
							laneExecutionAuthority,
						)) {
							is ReadLocalPortableCapturedCellResult.Ready ->
								local.entry != imported.entry
							is ReadLocalPortableCapturedCellResult.Outcome -> true
							null -> false
						}
					} == true
					CellHistoryQuery.Found(
						evaluation.toPublicCellEntry(
							overrideFailure = CellHistoryCause.ORIGIN_IDENTITY_CONFLICT
								.takeIf { originConflict },
						),
					)
				}
			} catch (cancelled: kotlinx.coroutines.CancellationException) {
				throw cancelled
			} catch (_: RuntimeException) {
				CellHistoryQuery.Found(
					unverifiableImportedSelection(
						selection,
						CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE,
					),
				)
			}
		}

	override suspend fun recent(limit: Int): CellHistoryPage {
		require(limit in 1..MAX_RESULTS)
		return withContext(ioDispatcher) {
			database.withTransaction { composeRecent(limit) }
		}
	}

	private suspend fun composeRecent(limit: Int): CellHistoryPage {
		val live = loadRecentLive(limit)
		if (live.page is CellHistoryPage.Failed) return live.page
		val imported = try {
			importedProductReader.selectRecentInTransaction(limit)
		} catch (cancelled: kotlinx.coroutines.CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return CellHistoryPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
		val localCollisions = imported.filterIsInstance<ImportedCellProductEvaluation.Readable>()
			.filter { it.localOriginHandle != null }
			.associateBy { it.candidate.identity }
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
					localCollisionIdentities = localCollisions.keys,
					imported = imported,
					localPortableEntriesByIdentity = localPortableEntries,
					limit = limit,
				),
			)
		} catch (_: ImportedCellHistoryCompositionFailure) {
			CellHistoryPage.Failed(CellHistoryCause.IMPORTED_EVIDENCE_UNVERIFIABLE)
		}
	}

	private suspend fun loadLocalPortableCollisions(
		evaluationsByEntryIdentity: Map<String, ImportedCellProductEvaluation.Readable>,
	): Map<String, PortableCapturedCellEntryV1> {
		if (evaluationsByEntryIdentity.isEmpty()) return emptyMap()
		require(evaluationsByEntryIdentity.size <= MAX_RESULTS)
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
					) throw ImportedCellHistoryCompositionFailure()
				}
				is ReadLocalPortableCapturedCellResult.Outcome -> Unit
				null -> throw ImportedCellHistoryCompositionFailure()
			}
		}
		return entries
	}

	private suspend fun loadRecentLive(limit: Int): LiveCellHistoryPage {
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
				pageLimit,
				beforeStart,
				beforeId,
			)
			if (candidates.isEmpty()) break
			scanned += candidates.size
			val seeds = candidates.map { it.segment }
			val snapshot = loadSnapshot(expandMembership(seeds))
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
			if (candidates.size < pageLimit) break
		}
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

	private suspend fun expandMembership(seeds: List<SessionSegment>): CellMembershipExpansion {
		val logicalIds = seeds.mapNotNull(SessionSegment::logicalTrackingId)
			.filter(String::isNotBlank).distinct()
		if (logicalIds.isEmpty()) return CellMembershipExpansion(seeds, emptyMap())
		val runs = mutableListOf<SourceServiceRunEntity>()
		var cursor: ServiceRunCursor? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = MAX_LOGICAL_MEMBERS - runs.size
			val pageLimit = minOf(MEMBER_PAGE_SIZE, remaining + 1)
			val page = database.trackingHistoryReadDao().logicalEntryServiceRunPage(
				logicalIds, pageLimit, cursor?.logicalId, cursor?.startedAtMs, cursor?.serviceRunId,
			)
			if (page.size > remaining) {
				return CellMembershipExpansion(seeds,
					logicalIds.associateWith { CellHistoryCause.READ_BUDGET_EXCEEDED }, overflow = true)
			}
			if (page.isEmpty()) break
			val next = ServiceRunCursor(page.last())
			check(cursor == null || next > cursor) { "Cell member cursor did not advance" }
			runs += page
			cursor = next
			if (page.size < pageLimit) break
		}
		currentCoroutineContext().ensureActive()
		val segmentIds = runs.mapNotNull(SourceServiceRunEntity::sessionSegmentId).distinct()
		val loaded = if (segmentIds.isEmpty()) emptyList() else database.trackingHistoryReadDao().segments(segmentIds)
		val segments = (loaded + seeds).distinctBy(SessionSegment::id)
		val byId = segments.associateBy(SessionSegment::id)
		val failures = linkedMapOf<String, CellHistoryCause>()
		logicalIds.forEach { logical ->
			if (runs.none { it.logicalTrackingId == logical }) {
				failures[logical] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		runs.forEach { run ->
			val segment = run.sessionSegmentId?.let(byId::get)
			if (segment == null || segment.logicalTrackingId != run.logicalTrackingId ||
				segment.serviceRunId != run.serviceRunId
			) failures[run.logicalTrackingId] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
		}
		seeds.forEach { seed ->
			val logical = seed.logicalTrackingId ?: return@forEach
			val run = runs.singleOrNull { it.serviceRunId == seed.serviceRunId }
			if (run == null || run.logicalTrackingId != logical || run.sessionSegmentId != seed.id) {
				failures[logical] = CellHistoryCause.PHYSICAL_MEMBERSHIP_INVALID
			}
		}
		return CellMembershipExpansion(segments, failures)
	}

	@Suppress("LongMethod")
	private suspend fun loadSnapshot(expansion: CellMembershipExpansion): CellHistorySnapshot {
		if (expansion.overflow) return CellHistorySnapshot.empty(expansion).copy(overflow = true)
		val segments = expansion.segments
		val runIds = segments.mapNotNull(SessionSegment::serviceRunId).filter(String::isNotBlank).distinct()
		if (runIds.isEmpty()) return CellHistorySnapshot.empty(expansion)
		val logicalIds = segments.mapNotNull(SessionSegment::logicalTrackingId).filter(String::isNotBlank).distinct()
		val readDao = database.trackingHistoryReadDao()
		val factDao = database.cellCapturedFactDao()
		val runs = readDao.serviceRuns(runIds)
		val manifests = readDao.manifests(runIds, MAX_MANIFESTS + 1)
		val sources = readDao.manifestSources(runIds, MAX_MANIFEST_SOURCES + 1)
		val policies = readDao.policiesForServiceRuns(CELL_SOURCE, runIds)
		val completeness = readDao.completeness(runIds, MAX_COMPLETENESS + 1)
		val sessions = database.sourceSessionDao().sessions(logicalIds)
		val factLoad = loadFactPages(runIds, logicalIds)
		val revisions = factLoad.revisions
		currentCoroutineContext().ensureActive()
		val scopedCursors = factDao.historyCursorsForScopes(
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_ID,
			SourceDestinationOwnerEntity.CELL_FACT_PROJECTION_VERSION,
			runIds,
			logicalIds,
			MAX_CURSORS + 1,
		)
		val factIds = revisions.map(CellCapturedFactRevisionEntity::logicalFactId).distinct()
		val referencedCursors = factIds.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyCursors(ids, MAX_CURSORS + 1)
		}
		val cursors = (scopedCursors + referencedCursors).distinctBy { cursor ->
			Triple(cursor.writerProjectionId, cursor.writerProjectionVersion, cursor.logicalFactId)
		}
		val generations = factDao.historyDeletionGenerations(logicalIds, runIds, MAX_DELETION_GENERATIONS + 1)
		val planRevisions = manifests.map(SessionManifestVersionEntity::acquisitionPlanRevision).distinct()
		val planHeaders = if (planRevisions.isEmpty()) emptyList() else
			factDao.historyAcquisitionPlanRevisions(planRevisions, MAX_PLANS + 1)
		val desiredPlans = if (planRevisions.isEmpty()) emptyList() else
			factDao.historyDesiredPlans(CELL_SOURCE, planRevisions, MAX_PLANS + 1)
		val registrationGenerations = revisions.map(CellCapturedFactRevisionEntity::registrationGeneration).distinct()
		val providers = registrationGenerations.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyProviderRegistrations(CELL_SOURCE, ids, MAX_PROVIDERS + 1)
		}
		val authorizations = registrationGenerations.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			factDao.historyAuthorizations(CELL_SOURCE, ids, MAX_AUTHORIZATIONS + 1)
		}
		val demandIds = authorizations.mapNotNull { it.demandId }.distinct()
		val demands = demandIds.chunked(SQL_ID_BATCH).flatMap { ids ->
			currentCoroutineContext().ensureActive()
			database.sourceBrokerDao().demandsByIds(ids)
		}
		val consentEpochs = sources.filter(::isCellCaptureMembership)
			.map(SessionManifestSourceEntity::consentEpoch).distinct()
		val consents = if (consentEpochs.isEmpty()) emptyList() else database.sourcePolicyDao().consentEpochs(
			CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, consentEpochs,
		)
		val scopePairs = segments.mapNotNull { segment ->
			val logical = segment.logicalTrackingId?.takeIf(String::isNotBlank)
			val run = segment.serviceRunId?.takeIf(String::isNotBlank)
			if (logical == null || run == null) null else logical to run
		}
		val digestToPair = scopePairs.associateBy { (logical, run) ->
			SourceDeletionFenceEntity.logicalServiceRunIdentity(
				CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE, logical, run,
			)
		}
		val fences = if (digestToPair.isEmpty()) emptyList() else readDao.deletionFences(
			CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE,
			SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN, digestToPair.keys.toList(),
		)
		val lanes = readDao.productLanesForServiceRuns(CELL_SOURCE,
			SessionManifestPurposeCode.SESSION_CAPTURE, runIds)
		val through = maxOfOrNull(
			revisions.maxOfOrNull(CellCapturedFactRevisionEntity::sourceAdmissionOrdinal),
			completeness.filter { it.sourceKind == CELL_SOURCE }
				.mapNotNull { it.lastAdmissionOrdinal }.maxOrNull(),
		)
		val after = lanes.minOfOrNull { it.activationOrdinal }?.minus(1L)
		val failures = if (after == null || through == null || after >= through) emptyList() else
			readDao.terminalFailuresForServiceRuns(CELL_SOURCE, SessionManifestPurposeCode.SESSION_CAPTURE,
				runIds, after, through, MAX_TERMINAL_FAILURES + 1)
		val evidence = database.sourceEvidenceStateDao().get()
		currentCoroutineContext().ensureActive()
		val overflow = factLoad.overflow || runs.size > MAX_LOGICAL_MEMBERS ||
			sessions.size > MAX_SESSIONS || manifests.size > MAX_MANIFESTS ||
			sources.size > MAX_MANIFEST_SOURCES || completeness.size > MAX_COMPLETENESS ||
			policies.size > MAX_POLICIES || consents.size > MAX_CONSENTS ||
			cursors.size > MAX_CURSORS || generations.size > MAX_DELETION_GENERATIONS ||
			planHeaders.size > MAX_PLANS || desiredPlans.size > MAX_PLANS ||
			providers.size > MAX_PROVIDERS || authorizations.size > MAX_AUTHORIZATIONS ||
			demands.size > MAX_DEMANDS ||
			fences.size > MAX_DELETION_FENCES || lanes.size > MAX_LANES ||
			failures.size > MAX_TERMINAL_FAILURES
		return CellHistorySnapshot(
			expansion = expansion,
			sessions = sessions.associateBy(LogicalTrackingSessionEntity::logicalTrackingId),
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
			cursors = cursors.take(MAX_CURSORS),
			deletionGenerations = generations.take(MAX_DELETION_GENERATIONS)
				.associateBy { it.logicalTrackingId to it.serviceRunId },
			deletedScopes = fences.mapNotNull { digestToPair[it.scopeIdentityDigest] }.toSet(),
			planHeaders = planHeaders.take(MAX_PLANS).associateBy { it.revision },
			desiredPlans = desiredPlans.take(MAX_PLANS).associateBy(SourceDesiredPlanEntity::revision),
			providerRegistrations = providers.take(MAX_PROVIDERS).associateBy { it.registrationGeneration },
			authorizationsByRegistration = authorizations.take(MAX_AUTHORIZATIONS)
				.groupBy { it.registrationGeneration },
			demands = demands.take(MAX_DEMANDS).associateBy(SourceDemandEntity::demandId),
			lanes = lanes,
			terminalFailures = failures.take(MAX_TERMINAL_FAILURES),
			evidenceState = evidence,
			overflow = overflow,
		)
	}

	private suspend fun loadFactPages(
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
			if (page.size > remaining) return CellFactLoad(facts, overflow = true)
			if (page.isEmpty()) break
			val next = FactCursor(page.last())
			check(cursor == null || next > cursor) { "Cell fact cursor did not advance" }
			facts += page
			cursor = next
			if (page.size < pageLimit) break
		}
		return CellFactLoad(facts, overflow = false)
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

	private fun maxOfOrNull(left: Long?, right: Long?): Long? = when {
		left == null -> right
		right == null -> left
		else -> maxOf(left, right)
	}

	private companion object {
		const val MAX_RESULTS = 100
		const val CANDIDATE_PAGE_SIZE = 32
		const val MAX_CANDIDATE_SCAN = 128
		const val MEMBER_PAGE_SIZE = 32
		const val MAX_LOGICAL_MEMBERS = 128
		const val FACT_PAGE_SIZE = 256
		const val MAX_FACT_REVISIONS = 4_096
		const val MAX_CURSORS = 4_096
		const val MAX_SESSIONS = 128
		const val MAX_MANIFESTS = 512
		const val MAX_MANIFEST_SOURCES = 4_096
		const val MAX_COMPLETENESS = 512
		const val MAX_POLICIES = 512
		const val MAX_CONSENTS = 512
		const val MAX_DELETION_GENERATIONS = 128
		const val MAX_DELETION_FENCES = 128
		const val MAX_LANES = 128
		const val MAX_PLANS = 512
		const val MAX_PROVIDERS = 512
		const val MAX_AUTHORIZATIONS = 4_096
		const val MAX_DEMANDS = 4_096
		const val MAX_TERMINAL_FAILURES = 512
		const val SQL_ID_BATCH = 400
	}
}

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

internal fun isCellCaptureMembership(source: SessionManifestSourceEntity): Boolean =
	source.sourceKind == CELL_SOURCE && source.purpose == SessionManifestPurposeCode.SESSION_CAPTURE &&
		source.persistenceEligible
