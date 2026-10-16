package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellHistoryPreflight
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveCompletenessOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveFactOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveRunOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellOpaqueOwnerRow
import com.adsamcik.tracker.shared.base.database.data.CellCapturedFactRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellIdentity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Authenticates imported Cell product facts without creating local provider, session, manifest,
 * policy, consent, WAL, or writer authority. Callers own the enclosing Room transaction.
 */
// This source reader owns one caller-scoped snapshot, its paging budget and exact Cell ownership audit.
@Suppress("LargeClass", "TooManyFunctions")
class ImportedCellProductReader(
	private val database: AppDatabase,
	private val batchCheckpoint: suspend (completedBatchCount: Int) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
	private val ownerChunkCheckpoint: suspend (completedChunkCount: Int) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
) {
	private val scanContextIssuer = Any()

	/** Native group resolved by this source reader, for shared history's exact duplicate check. */
	fun localOriginLogicalTrackingId(evaluation: ImportedCellProductEvaluation.Readable): String? =
		evaluation.localOriginHandle?.logicalTrackingId

	/** Optional measured-duplicate discovery keeps the opaque local handle inside its source owner. */
	suspend fun measuredChildOriginCandidatesInTransaction(evaluation: ImportedCellProductEvaluation.Readable,
		observedTimeMs: Long, itemIndex: Int, limit: Int): List<CellCapturedFactRevisionEntity> {
		require(limit in 1..101)
		val logicalId = evaluation.localOriginHandle?.logicalTrackingId ?: return emptyList()
		return database.cellCapturedFactDao().measuredChildOriginCandidates(logicalId, observedTimeMs, itemIndex, limit)
	}

	/**
	 * Reads the original matching native Cell hierarchy from a reader-issued collision handle, or
	 * null when no local origin is present.
	 */
	suspend fun readLocalOriginInTransaction(
		evaluation: ImportedCellProductEvaluation.Readable,
		laneExecutionAuthority: SourceProductLaneExecutionAuthority,
	): ReadLocalPortableCapturedCellResult? {
		val logicalTrackingId = evaluation.localOriginHandle?.logicalTrackingId ?: return null
		return RoomReadLocalPortableCapturedCell(
			database,
			laneExecutionAuthority,
		).readInTransaction(logicalTrackingId)
	}

	/**
	 * Evaluates the latest saved imported Cell revision for the exact identity, or returns null when
	 * absent.
	 */
	suspend fun selectIdentityInTransaction(
		identity: PortableCellOpaqueIdentity,
	): ImportedCellProductEvaluation? {
		val candidate = database.importedCellDao().latestHistoryCandidate(identity.value) ?: return null
		return evaluateCandidates(listOf(candidate), null, null, 1).single()
	}

	/**
	 * Evaluates bounded recent imported Cell candidates inside the caller's transaction.
	 */
	suspend fun selectRecentInTransaction(limit: Int): List<ImportedCellProductEvaluation> {
		return selectRecentPageInTransaction(
			limit = limit,
			beforeStartTimeMs = null,
			beforeIdentity = null,
		)
	}

	private suspend fun selectRecentPageInTransaction(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedCellProductEvaluation> {
		val candidates = loadRecentCandidates(limit, beforeStartTimeMs, beforeIdentity)
		val context = try {
			loadScanSnapshot()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedCellProductScanContextFailure) {
			return candidates.unverifiable(failure.reason)
		}
		return evaluateCandidatesWithBudget(
			candidates = candidates,
			beforeStartTimeMs = beforeStartTimeMs,
			beforeIdentity = beforeIdentity,
			maximumSize = limit,
			context = context,
			budget = ImportedCellProductReadBudget.unbounded(),
		).evaluations
	}

	/**
	 * Runs the supplied block with one transaction-scoped imported Cell scan and invalidates its
	 * context when the block ends.
	 */
	suspend fun <T> withRecentScanInTransaction(
		block: suspend (ImportedCellProductScanScope) -> T,
	): T {
		if (!database.inTransaction()) {
			throw ImportedCellProductScanContextFailure(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val scope = ImportedCellProductScanScope(
			reader = this,
			issuer = scanContextIssuer,
			snapshot = loadScanSnapshot(),
		)
		return try {
			block(scope)
		} finally {
			scope.invalidate()
		}
	}

	internal suspend fun selectRecentPageInScope(
		scope: ImportedCellProductScanScope,
		budget: ImportedCellProductReadBudget,
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): ImportedCellProductScanPage {
		val context = scope.requireActive(
			expectedIssuer = scanContextIssuer,
			inTransaction = database.inTransaction(),
		)
		val candidates = loadRecentCandidates(limit, beforeStartTimeMs, beforeIdentity)
		return evaluateCandidatesWithBudget(
			candidates = candidates,
			beforeStartTimeMs = beforeStartTimeMs,
			beforeIdentity = beforeIdentity,
			maximumSize = limit,
			context = context,
			budget = budget,
		)
	}

	internal suspend fun hasRecentCandidateInScope(
		scope: ImportedCellProductScanScope,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): Boolean {
		scope.requireActive(
			expectedIssuer = scanContextIssuer,
			inTransaction = database.inTransaction(),
		)
		require((beforeStartTimeMs == null) == (beforeIdentity == null))
		require(beforeStartTimeMs?.let { it >= 0L } != false)
		require(beforeIdentity?.let { ImportedCellIdentity.isDigest(it) } != false)
		return database.importedCellDao().hasRecentHistoryCandidate(
			beforeStartTimeMs,
			beforeIdentity,
		)
	}

	private suspend fun loadRecentCandidates(
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): List<ImportedCellHistoryCandidate> {
		require(limit in 1..ImportedCellDao.MAX_HISTORY_ENTRY_CANDIDATES)
		require((beforeStartTimeMs == null) == (beforeIdentity == null))
		require(beforeStartTimeMs?.let { it >= 0L } != false)
		require(beforeIdentity?.let { ImportedCellIdentity.isDigest(it) } != false)
		val candidates = database.importedCellDao().recentHistoryCandidatePage(
			limit = limit,
			beforeStartTimeMs = beforeStartTimeMs,
			beforeIdentity = beforeIdentity,
		)
		return candidates
	}

	/**
	 * Evaluates bounded imported Cell candidates overlapping the half-open UTC millisecond range.
	 */
	suspend fun selectWallRangeInTransaction(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		maximumCandidates: Int,
	): List<ImportedCellProductEvaluation> {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		return selectRangeInTransaction(maximumCandidates) {
				limit, beforeStartTimeMs, beforeIdentity ->
			database.importedCellDao().historyCandidatePageInWallRange(
				fromInclusiveMs,
				toExclusiveMs,
				limit,
				beforeStartTimeMs,
				beforeIdentity,
			)
		}
	}

	/** Export discovery uses saved scans and gaps while preserving the original header cursor. */
	suspend fun selectExportWallRangePageInTransaction(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeStartTimeMs: Long? = null,
		beforeIdentity: String? = null,
	): List<ImportedCellProductEvaluation> {
		require(database.inTransaction())
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		require(limit in 1..ImportedCellDao.MAX_HISTORY_ENTRY_CANDIDATES)
		require((beforeStartTimeMs == null) == (beforeIdentity == null))
		require(beforeStartTimeMs?.let { it >= 0L } != false)
		require(beforeIdentity?.let { ImportedCellIdentity.isDigest(it) } != false)
		val candidates = database.importedCellDao().exportCandidatePageInWallRange(
			fromInclusiveMs, toExclusiveMs, limit, beforeStartTimeMs, beforeIdentity,
		)
		return evaluateCandidates(candidates, beforeStartTimeMs, beforeIdentity, limit)
	}

	/**
	 * Loads bounded imported Cell candidates from broad UTC bounds for subsequent saved-zone/day
	 * qualification.
	 */
	suspend fun selectStructuralRangeInTransaction(
		broadFromInclusiveMs: Long,
		broadToExclusiveMs: Long,
		maximumCandidates: Int,
	): List<ImportedCellProductEvaluation> {
		require(broadFromInclusiveMs >= 0L && broadToExclusiveMs > broadFromInclusiveMs)
		return selectRangeInTransaction(maximumCandidates) {
				limit, beforeStartTimeMs, beforeIdentity ->
			database.importedCellDao().historyCandidatePageForStructuralDays(
				broadFromInclusiveMs,
				broadToExclusiveMs,
				limit,
				beforeStartTimeMs,
				beforeIdentity,
			)
		}
	}

	private suspend fun selectRangeInTransaction(
		maximumCandidates: Int,
		loadPage: suspend (Int, Long?, String?) -> List<ImportedCellHistoryCandidate>,
	): List<ImportedCellProductEvaluation> {
		require(maximumCandidates in 1..MAX_RANGE_CANDIDATES)
		val candidates = mutableListOf<ImportedCellHistoryCandidate>()
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = maximumCandidates - candidates.size
			val pageLimit = minOf(
				ImportedCellDao.MAX_HISTORY_ENTRY_CANDIDATES,
				remaining + 1,
			)
			val page = loadPage(pageLimit, beforeStartTimeMs, beforeIdentity)
			if (page.isEmpty()) {
				break
			}
			if (!isValidCandidatePage(
					page,
					beforeStartTimeMs,
					beforeIdentity,
					pageLimit,
				)
			) {
				return page.take(1).unverifiable(
					ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			if (page.size > remaining) {
				return page.take(1).unverifiable(ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
			}
			candidates += page
			val last = page.last()
			beforeStartTimeMs = last.startTimeMs
			beforeIdentity = last.identity
			if (page.size < pageLimit) {
				break
			}
		}
		return evaluateCandidates(
			candidates,
			null,
			null,
			maximumCandidates,
		)
	}

	private suspend fun evaluateCandidates(
		candidates: List<ImportedCellHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		maximumSize: Int,
		context: ImportedCellProductScanSnapshot? = null,
	): List<ImportedCellProductEvaluation> = evaluateCandidatesWithBudget(
		candidates = candidates,
		beforeStartTimeMs = beforeStartTimeMs,
		beforeIdentity = beforeIdentity,
		maximumSize = maximumSize,
		context = context,
		budget = ImportedCellProductReadBudget.unbounded(),
	).evaluations

	// Preserve early rejection at the invalid-page, scan-context and exhausted-budget checkpoints.
	@Suppress("ReturnCount")
	private suspend fun evaluateCandidatesWithBudget(
		candidates: List<ImportedCellHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		maximumSize: Int,
		context: ImportedCellProductScanSnapshot? = null,
		budget: ImportedCellProductReadBudget,
	): ImportedCellProductScanPage {
		if (!isValidCandidatePage(candidates, beforeStartTimeMs, beforeIdentity, maximumSize)) {
			return ImportedCellProductScanPage(
				candidates.unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE),
				ImportedCellProductReadUsage.ZERO,
			)
		}
		if (candidates.isEmpty()) {
			return ImportedCellProductScanPage(
				emptyList(),
				ImportedCellProductReadUsage.ZERO,
			)
		}
		val sourceContext = try {
			context ?: loadScanSnapshot()
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedCellProductScanContextFailure) {
			return ImportedCellProductScanPage(
				candidates.unverifiable(failure.reason),
				ImportedCellProductReadUsage.ZERO,
			)
		} catch (_: RuntimeException) {
			return ImportedCellProductScanPage(
				candidates.unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE),
				ImportedCellProductReadUsage.ZERO,
			)
		}
		val evaluated = mutableListOf<ImportedCellProductEvaluation>()
		var remaining = budget
		var usage = ImportedCellProductReadUsage.ZERO
		candidates.chunked(ImportedCellDao.HISTORY_EVALUATION_BATCH_SIZE)
			.forEachIndexed { index, batch ->
				batchCheckpoint(index)
				val result = evaluateBatch(batch, sourceContext, remaining)
				if (result.budgetExceeded) {
					return ImportedCellProductScanPage(
						candidates.unverifiable(
							ImportedCellProductFailure.DEPENDENCY_OVERFLOW,
							sourceContext,
						),
						usage,
					)
				}
				remaining = remaining.consume(result.usage) ?: return ImportedCellProductScanPage(
					candidates.unverifiable(
						ImportedCellProductFailure.DEPENDENCY_OVERFLOW,
						sourceContext,
					),
					usage,
				)
				usage += result.usage
				evaluated += result.evaluations
			}
		return ImportedCellProductScanPage(evaluated, usage)
	}

	private suspend fun evaluateBatch(
		candidates: List<ImportedCellHistoryCandidate>,
		context: ImportedCellProductScanSnapshot,
		budget: ImportedCellProductReadBudget,
	): ImportedCellProductBatchEvaluation {
		if (candidates.isEmpty()) {
			return ImportedCellProductBatchEvaluation(
				emptyList(),
				ImportedCellProductReadUsage.ZERO,
				false,
			)
		}
		val state = context.state
		val identities = candidates.map(ImportedCellHistoryCandidate::identity)
		val (loaded, hierarchyUsage) = try {
			loadBoundedBatch(identities, budget)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedCellOwnershipFailure) {
			return batchFailure(candidates, failure.reason, context,
				budgetExceeded = failure.reason == ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
		}
		val (authenticated, failures) = loaded.authenticateCandidates(candidates, state)
			?: return batchFailure(candidates, ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE, context)

		val audit = auditAuthenticatedCandidates(authenticated, failures, context)

		val evaluations = candidates.map { candidate ->
			failures[candidate.identity]?.let { reason ->
				ImportedCellProductEvaluation.Unverifiable(
					candidate,
					reason,
					context.localOriginHandle(candidate.identity),
				)
			} ?: authenticated.getValue(candidate.identity).toEvaluation(requireNotNull(audit), state)
		}
		return ImportedCellProductBatchEvaluation(evaluations, hierarchyUsage, false)
	}

	private suspend fun auditAuthenticatedCandidates(
		authenticated: Map<String, AuthenticatedCellCandidate>,
		failures: MutableMap<String, ImportedCellProductFailure>,
		context: ImportedCellProductScanSnapshot,
	): ImportedCellOwnershipAudit? {
		return if (authenticated.isEmpty()) {
			null
		} else {
			try {
				authenticateOwnership(
					dao = database.importedCellDao(),
					graph = ImportedCellProductIdentityGraph.merge(
						authenticated.values.map(AuthenticatedCellCandidate::graph),
					) ?: throw ImportedCellOwnershipFailure(
						ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT,
					),
					context = context,
				)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (failure: ImportedCellOwnershipFailure) {
				authenticated.keys.forEach { identity -> failures[identity] = failure.reason }
				null
			} catch (_: ArithmeticException) {
				authenticated.keys.forEach { identity ->
					failures[identity] = ImportedCellProductFailure.VALUE_OVERFLOW
				}
				null
			} catch (_: RuntimeException) {
				authenticated.keys.forEach { identity ->
					failures[identity] =
						ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
				}
				null
			}
		}
	}

	/** The caller translates these existing typed failures without changing query/catch boundaries. */
	private suspend fun loadBoundedBatch(
		identities: List<String>,
		budget: ImportedCellProductReadBudget,
	): Pair<ImportedCellProductBatch, ImportedCellProductReadUsage> {
		val preflight = try {
			database.importedCellDao().historyPreflight(identities)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			storedCorrupt()
		}
		val hierarchyUsage = try {
			preflight.toReadUsage()
		} catch (_: ArithmeticException) {
			dependencyOverflow()
		}
		if (!preflight.hasValidBounds(identities.size) || !budget.canLoadHierarchy(hierarchyUsage)) {
			dependencyOverflow()
		}
		val loaded = try {
			ImportedCellProductBatch.load(database.importedCellDao(), identities, preflight)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			dependencyOverflow()
		} catch (_: RuntimeException) {
			storedCorrupt()
		}
		requireBoundedHierarchy(loaded, preflight, identities)
		return loaded to hierarchyUsage
	}

	private fun requireBoundedHierarchy(
		loaded: ImportedCellProductBatch,
		preflight: ImportedCellHistoryPreflight,
		identities: List<String>,
	) {
		if (!loaded.matches(preflight)) {
			storedCorrupt()
		}
		if (loaded.exceedsLimits(identities.size)) {
			dependencyOverflow()
		}
		if (!loaded.belongsOnlyTo(identities)) {
			storedCorrupt()
		}
	}

	private suspend fun loadScanSnapshot(): ImportedCellProductScanSnapshot {
		val state = database.sourceEvidenceStateDao().get()
			?: scanContextFailure(
				ImportedCellProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
			)
		if (!state.hasValidImportedCellProductShape()) {
			scanContextFailure(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val dao = database.importedCellDao()
		val limit = ImportedCellDao.MAX_LIVE_OWNER_ROWS + 1
		val logicalIds = dao.liveLogicalTrackingIds(limit)
		val runs = dao.liveServiceRunOwners(limit)
		val facts = dao.liveCellFactOwners(limit)
		val completeness = dao.liveCellCompletenessOwners(
			SourceDestinationOwnerEntity.SOURCE_CELL,
			limit,
		)
		val generations = dao.liveCellDeletionGenerations(limit)
		if (listOf(
				logicalIds.size,
				runs.size,
				facts.size,
				completeness.size,
				generations.size,
			).any { it > ImportedCellDao.MAX_LIVE_OWNER_ROWS }
		) {
			scanContextFailure(ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
		}
		val localLogicalByEntry = directLocalLogicalClaims(logicalIds, runs, facts, completeness, generations)
		return ImportedCellProductScanSnapshot(
			state = state,
			logicalIds = logicalIds,
			runs = runs,
			facts = facts,
			completeness = completeness,
			generations = generations,
			directLocalLogicalByEntryIdentity = localLogicalByEntry,
		)
	}

	private fun directLocalLogicalClaims(
		logicalIds: List<String>,
		runs: List<ImportedCellLiveRunOwner>,
		facts: List<ImportedCellLiveFactOwner>,
		completeness: List<ImportedCellLiveCompletenessOwner>,
		generations: List<CellCaptureDeletionGenerationEntity>,
	): Map<String, String> {
		if (logicalIds.any(String::isBlank) || logicalIds.distinct().size != logicalIds.size) {
			scanContextFailure(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val localLogicalClaims = (
			logicalIds + runs.map { it.logicalTrackingId } +
				facts.map { it.logicalTrackingId } +
				completeness.map { it.logicalTrackingId } +
				generations.map { it.logicalTrackingId }
			).distinct()
		if (localLogicalClaims.any(String::isBlank)) {
			scanContextFailure(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return localLogicalClaims.map { logicalId ->
			val entryIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
			entryIdentity to logicalId
		}.groupBy({ it.first }, { it.second }).mapValues { (_, owners) ->
			owners.distinct().singleOrNull() ?: scanContextFailure(
				ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT,
			)
		}
	}

	private suspend fun authenticateOwnership(
		dao: ImportedCellDao,
		graph: ImportedCellProductIdentityGraph,
		context: ImportedCellProductScanSnapshot,
	): ImportedCellOwnershipAudit {
		val state = context.state
		val sourceFences = authenticateImportedOwnerProbes(dao, graph, state)

		val logicalIds = context.logicalIds
		val liveRuns = context.runs
		val facts = context.facts
		val completeness = context.completeness
		val generations = context.generations
		val logicalByEntryIdentity = authenticateLocalEntryOwners(logicalIds, graph)
		val liveRunById = authenticateLocalRunOwners(liveRuns, graph, logicalByEntryIdentity)
		authenticateLocalCompletenessOwners(completeness, liveRunById, graph)
		facts.groupBy(ImportedCellLiveFactOwner::logicalFactId).forEach { (logicalFactId, claims) ->
			authenticateLocalFactClaims(logicalFactId, claims, liveRunById, graph)
		}
		authenticateLocalDeletionGenerations(generations, state, graph, sourceFences)
		return ImportedCellOwnershipAudit(
			localLogicalTrackingIdsByEntryIdentity =
				context.directLocalLogicalByEntryIdentity + logicalByEntryIdentity,
			sourceDeletedScopeDigests = sourceFences.keys,
		)
	}

	private suspend fun authenticateImportedOwnerProbes(
		dao: ImportedCellDao,
		graph: ImportedCellProductIdentityGraph,
		state: SourceEvidenceState,
	): Map<String, Long> {
		val sourceFences = linkedMapOf<String, Long>()
		graph.allProtectedValues.chunked(ImportedCellDao.MAX_IDENTITY_QUERY_CHUNK)
			.forEachIndexed { index, values ->
			ownerChunkCheckpoint(index)
			currentCoroutineContext().ensureActive()
			val limit = Math.addExact(
				Math.multiplyExact(graph.allProtectedValues.size, OWNER_PROBE_KIND_FACTOR),
				1,
			)
			val rows = dao.opaqueOwnerProbe(values, limit)
			if (rows.size >= limit) {
				dependencyOverflow()
			}
			rows.forEach { row ->
				authenticateImportedOwner(row, graph, state, sourceFences)
			}
		}
		return sourceFences
	}

	private fun authenticateLocalEntryOwners(
		logicalIds: List<String>,
		graph: ImportedCellProductIdentityGraph,
	): MutableMap<String, String> {
		val logicalByEntryIdentity = linkedMapOf<String, String>()
		logicalIds.forEach { logicalId ->
			val entryIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
			if (entryIdentity in graph.allProtectedValues) {
				if (graph.kinds[entryIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY) {
					originConflict()
				}
				logicalByEntryIdentity[entryIdentity] = logicalId
			}
		}
		return logicalByEntryIdentity
	}

	private fun authenticateLocalRunOwners(
		liveRuns: List<ImportedCellLiveRunOwner>,
		graph: ImportedCellProductIdentityGraph,
		logicalByEntryIdentity: MutableMap<String, String>,
	): Map<String, ImportedCellLiveRunOwner> {
		val liveRunById = liveRuns.associateBy { it.serviceRunId }
		if (liveRunById.size != liveRuns.size || liveRuns.any {
			it.serviceRunId.isBlank() || it.logicalTrackingId.isBlank()
		}) {
			storedCorrupt()
		}
		liveRuns.forEach { owner ->
			val entryIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				owner.logicalTrackingId,
			).value
			val runIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN,
				owner.serviceRunId,
			).value
			val scope = PortableCellDeletionScopeDigest.derive(
				owner.logicalTrackingId,
				owner.serviceRunId,
			).value
			if (listOf(entryIdentity, runIdentity, scope).any { it in graph.allProtectedValues }) {
				if (!graph.hasExactRunOwner(entryIdentity, runIdentity, scope)) {
					originConflict()
				}
				logicalByEntryIdentity[entryIdentity] = owner.logicalTrackingId
			}
		}
		return liveRunById
	}

	private fun authenticateLocalCompletenessOwners(
		completeness: List<ImportedCellLiveCompletenessOwner>,
		liveRunById: Map<String, ImportedCellLiveRunOwner>,
		graph: ImportedCellProductIdentityGraph,
	) {
		completeness.forEach { owner ->
			val liveRun = liveRunById[owner.serviceRunId]
			if (liveRun == null || liveRun.logicalTrackingId != owner.logicalTrackingId) {
				storedCorrupt()
			}
			val entryIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				owner.logicalTrackingId,
			).value
			val runIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN,
				owner.serviceRunId,
			).value
			val scope = PortableCellDeletionScopeDigest.derive(
				owner.logicalTrackingId,
				owner.serviceRunId,
			).value
			if (listOf(entryIdentity, runIdentity, scope).any { it in graph.allProtectedValues } &&
				!graph.hasExactRunOwner(entryIdentity, runIdentity, scope)
			) {
				originConflict()
			}
		}
	}

	private fun authenticateLocalFactClaims(
		logicalFactId: String,
		claims: List<ImportedCellLiveFactOwner>,
		liveRunById: Map<String, ImportedCellLiveRunOwner>,
		graph: ImportedCellProductIdentityGraph,
	) {
		val structuralClaims = claims.map {
			Triple(
				it.logicalTrackingId,
				it.serviceRunId,
				it.aggregateOwnerLogicalFactId to it.aggregateOwnerSemanticRevision,
			)
		}.distinct()
		if (logicalFactId.isBlank() || structuralClaims.size != 1) {
			storedCorrupt()
		}
		val claim = claims.first()
		val liveRun = liveRunById[claim.serviceRunId]
		if (liveRun == null || liveRun.logicalTrackingId != claim.logicalTrackingId) {
			storedCorrupt()
		}
		val identity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.OBSERVATION,
			logicalFactId,
		).value
		val aggregateIdentity = claim.aggregateOwnerLogicalFactId?.let { ownerId ->
			PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.OBSERVATION,
				ownerId,
			).value
		}
		if (identity !in graph.allProtectedValues &&
			aggregateIdentity?.let { it in graph.allProtectedValues } != true
		) {
			return
		}
		val entryIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.LOGICAL_ENTRY,
			claim.logicalTrackingId,
		).value
		val runIdentity = PortableCellOpaqueIdentity.derive(
			PortableCellIdentityKind.PHYSICAL_RUN,
			claim.serviceRunId,
		).value
		if (!graph.accepts(
				identity = identity,
				entryIdentity = entryIdentity,
				runIdentity = runIdentity,
				aggregateOwnerIdentity = aggregateIdentity,
			)
		) {
			originConflict()
		}
	}

	private fun authenticateLocalDeletionGenerations(
		generations: List<CellCaptureDeletionGenerationEntity>,
		state: SourceEvidenceState,
		graph: ImportedCellProductIdentityGraph,
		sourceFences: Map<String, Long>,
	) {
		val relevantGenerations = linkedMapOf<String, Long>()
		generations.forEach { generation ->
			if (!state.acceptsRetainedEpoch(generation.collectedDataEpoch) ||
				generation.logicalTrackingId.isBlank() || generation.serviceRunId.isBlank() ||
				generation.generation <= 0L
			) {
				storedCorrupt()
			}
			val entryIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				generation.logicalTrackingId,
			).value
			val runIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.PHYSICAL_RUN,
				generation.serviceRunId,
			).value
			val scope = PortableCellDeletionScopeDigest.derive(
				generation.logicalTrackingId,
				generation.serviceRunId,
			).value
			if (listOf(entryIdentity, runIdentity, scope).none { it in graph.allProtectedValues }) {
				return@forEach
			}
			if (!graph.hasMatchingRunAndScopeOwners(entryIdentity, runIdentity, scope)) {
				originConflict()
			}
			if (relevantGenerations.put(scope, generation.generation) != null) {
				storedCorrupt()
			}
		}
		if (relevantGenerations != sourceFences) {
			storedCorrupt()
		}
	}

	private fun authenticateImportedOwner(
		row: ImportedCellOpaqueOwnerRow,
		graph: ImportedCellProductIdentityGraph,
		state: SourceEvidenceState,
		sourceFences: MutableMap<String, Long>,
	) {
		if (row.collectedDataEpoch?.let { !state.acceptsRetainedEpoch(it) } == true) {
			storedCorrupt()
		}
		if (row.ownerKind == ImportedCellOpaqueOwnerRow.SOURCE_FENCE) {
			authenticateImportedSourceFence(row, graph, sourceFences)
		} else {
			authenticateImportedHierarchyOwner(row, graph)
		}
	}

	// Each saved owner kind must correspond to its complete fixed hierarchy tuple; there is no traversal or mutation.
	@Suppress("CyclomaticComplexMethod", "ComplexCondition")
	private fun authenticateImportedHierarchyOwner(
		row: ImportedCellOpaqueOwnerRow,
		graph: ImportedCellProductIdentityGraph,
	) {
		when (row.ownerKind) {
			ImportedCellOpaqueOwnerRow.ENTRY,
			ImportedCellOpaqueOwnerRow.ENTRY_DELETION,
			-> {
				if (graph.kinds[row.protectedIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY ||
					row.entryIdentity != row.protectedIdentity
				) {
					originConflict()
				}
			}
			ImportedCellOpaqueOwnerRow.RUN -> {
				val entry = row.entryIdentity ?: originConflict()
				val scope = row.deletionScopeDigest ?: originConflict()
				if (graph.kinds[row.protectedIdentity] != PortableCellIdentityKind.PHYSICAL_RUN ||
					graph.runOwners[row.protectedIdentity] != (entry to scope)
				) {
					originConflict()
				}
			}
			ImportedCellOpaqueOwnerRow.SCOPE -> {
				val entry = row.entryIdentity ?: originConflict()
				val run = row.runIdentity ?: originConflict()
				if (graph.scopeOwners[row.protectedIdentity] != (entry to run)) {
					originConflict()
				}
			}
			ImportedCellOpaqueOwnerRow.OBSERVATION -> {
				if (!graph.accepts(
						identity = row.protectedIdentity,
						entryIdentity = row.entryIdentity ?: originConflict(),
						runIdentity = row.runIdentity ?: originConflict(),
						aggregateOwnerIdentity = row.aggregateOwnerIdentity,
					)
				) {
					originConflict()
				}
			}
			ImportedCellOpaqueOwnerRow.RUN_DELETION -> {
				val entry = row.entryIdentity ?: originConflict()
				val scope = row.deletionScopeDigest ?: originConflict()
				if (graph.runOwners[row.protectedIdentity] != (entry to scope) ||
					row.generation != 1L
				) {
					originConflict()
				}
			}
			ImportedCellOpaqueOwnerRow.DELETION_RECEIPT,
			ImportedCellOpaqueOwnerRow.DELETED_IDENTITY,
			-> storedCorrupt()
			else -> storedCorrupt()
		}
	}

	private fun authenticateImportedSourceFence(
		row: ImportedCellOpaqueOwnerRow,
		graph: ImportedCellProductIdentityGraph,
		sourceFences: MutableMap<String, Long>,
	) {
		val scope = row.deletionScopeDigest ?: originConflict()
		val expected = graph.scopeOwners[scope] ?: originConflict()
		val generation = row.generation ?: originConflict()
		if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_CELL ||
			row.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
			row.scopeKind != SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN ||
			graph.runOwners[expected.second] != (expected.first to scope)
		) {
			originConflict()
		}
		if (generation <= 0L || sourceFences.put(scope, generation) != null) {
			originConflict()
		}
	}

	private fun isValidCandidatePage(
		candidates: List<ImportedCellHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		maximumSize: Int,
	): Boolean {
		if ((beforeStartTimeMs == null) != (beforeIdentity == null) ||
			candidates.size > maximumSize ||
			candidates.map(ImportedCellHistoryCandidate::identity).distinct().size != candidates.size
		) {
			return false
		}
		if (candidates.any { it.hasInvalidSavedShape() }) {
			return false
		}
		val cursors = buildList {
			if (beforeStartTimeMs != null && beforeIdentity != null) {
				add(beforeStartTimeMs to beforeIdentity)
			}
			addAll(candidates.map { it.startTimeMs to it.identity })
		}
		return cursors.zipWithNext().all { (left, right) ->
			left.first > right.first || left.first == right.first && left.second > right.second
		}
	}


	// Complete immutable saved identity, digest and time shape; cursor traversal remains in its caller.
	@Suppress("ComplexCondition")
	private fun ImportedCellHistoryCandidate.hasInvalidSavedShape(): Boolean =
		runCatching { PortableCellOpaqueIdentity(identity) }.isFailure ||
			runCatching { PortableCellDigest(contentChecksum) }.isFailure ||
			importRevision <= 0L || startTimeMs < 0L || endTimeMs < startTimeMs || receivedAtMs < 0L

	private fun scanContextFailure(reason: ImportedCellProductFailure): Nothing =
		throw ImportedCellProductScanContextFailure(reason)

	private fun storedCorrupt(): Nothing = throw ImportedCellOwnershipFailure(
		ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	)

	private fun originConflict(): Nothing = throw ImportedCellOwnershipFailure(
		ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT,
	)

	private fun dependencyOverflow(): Nothing = throw ImportedCellOwnershipFailure(
		ImportedCellProductFailure.DEPENDENCY_OVERFLOW,
	)

	private companion object {
		const val MAX_RANGE_CANDIDATES = 256
		const val OWNER_PROBE_KIND_FACTOR = 24
	}
}

/**
 * Imported Cell candidate with verified hierarchy/masks or a named failure; any local collision
 * handle stays separate.
 */
sealed interface ImportedCellProductEvaluation {
	val candidate: ImportedCellHistoryCandidate
	val localOriginHandle: ImportedCellLocalOriginHandle?

	/**
	 * Verified imported source hierarchy with independent deletion/retention masks; readable does not
	 * imply complete re-export eligibility.
	 */
	data class Readable(
		override val candidate: ImportedCellHistoryCandidate,
		val entry: PortableCapturedCellEntryV1,
		val entryDeleted: Boolean,
		val deletedRunIdentities: Set<String>,
		val retainedFromMs: Long?,
		val retentionLimited: Boolean,
		override val localOriginHandle: ImportedCellLocalOriginHandle?,
	) : ImportedCellProductEvaluation {
		val isReExportable: Boolean
			get() = !entryDeleted && deletedRunIdentities.isEmpty() && !retentionLimited
	}

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		override val candidate: ImportedCellHistoryCandidate,
		val reason: ImportedCellProductFailure,
		override val localOriginHandle: ImportedCellLocalOriginHandle? = null,
	) : ImportedCellProductEvaluation
}

/**
 * Missing source state, stale epoch, invalid stored lineage/origin or exceeded dependency/value
 * limits.
 */
enum class ImportedCellProductFailure {
	SOURCE_EVIDENCE_STATE_MISSING,
	STALE_COLLECTED_DATA_EPOCH,
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
	VALUE_OVERFLOW,
}

private data class AuthenticatedCellCandidate(
	val candidate: ImportedCellHistoryCandidate,
	val lineage: AuthenticatedImportedCellLineage,
	val graph: ImportedCellProductIdentityGraph,
	val entryDeleted: Boolean,
	val importedDeletedRunIdentities: Set<String>,
) {
	fun toEvaluation(
		audit: ImportedCellOwnershipAudit,
		state: SourceEvidenceState,
	): ImportedCellProductEvaluation.Readable {
		val sourceDeletedRuns = graph.runOwners.filterValues { (_, scope) ->
			scope in audit.sourceDeletedScopeDigests
		}.keys
		val retainedFromMs = state.retainedFromMs
		return ImportedCellProductEvaluation.Readable(
			candidate = candidate,
			entry = lineage.revisions.last().entry,
			entryDeleted = entryDeleted,
			deletedRunIdentities = importedDeletedRunIdentities + sourceDeletedRuns,
			retainedFromMs = retainedFromMs,
			retentionLimited = retainedFromMs?.let { floor ->
				lineage.revisions.any { revision ->
					revision.entry.runs.any { run ->
						run.observations.any { it.coverageStartTimeMs < floor }
					}
				}
			} == true,
			localOriginHandle = audit.localLogicalTrackingIdsByEntryIdentity[candidate.identity]
				?.let(::ImportedCellLocalOriginHandle),
		)
	}

	companion object {
		fun authenticate(
			candidate: ImportedCellHistoryCandidate,
			lineage: AuthenticatedImportedCellLineage,
			state: SourceEvidenceState,
			entryDeletionsByIdentity: Map<String, ImportedCellEntryDeletionEntity>,
			runDeletionsByEntry: Map<String, List<ImportedCellDeletionGenerationEntity>>,
		): AuthenticatedCellCandidate {
			val latest = lineage.revisions.lastOrNull()
				?: throw IllegalArgumentException("Imported Cell candidate has no revision")
			require(candidate.exactlyMatches(latest)) {
				"Imported Cell candidate is not the latest revision"
			}
			val entryDeletion = entryDeletionsByIdentity[candidate.identity]
			require(entryDeletion == null || (
				state.acceptsRetainedEpoch(entryDeletion.collectedDataEpoch) &&
					entryDeletion.deletedImportRevision == latest.header.importRevision
				)
			) {
				"Imported Cell entry deletion is stale"
			}
			val graph = ImportedCellProductIdentityGraph.from(lineage)
				?: throw ImportedCellOwnershipFailure(
					ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT,
				)
			val deletedRuns = runDeletionsByEntry[candidate.identity].orEmpty()
			require(deletedRuns.none { marker ->
				!state.acceptsRetainedEpoch(marker.collectedDataEpoch) || marker.generation != 1L ||
					graph.runOwners[marker.runIdentity] !=
					(marker.entryIdentity to marker.deletionScopeDigest)
			}) {
				"Imported Cell run deletion is stale or orphaned"
			}
			return AuthenticatedCellCandidate(
				candidate = candidate,
				lineage = lineage,
				graph = graph,
				entryDeleted = entryDeletion != null,
				importedDeletedRunIdentities = deletedRuns.mapTo(linkedSetOf()) {
					it.runIdentity
				},
			)
		}
	}
}

/**
 * Reader-issued opaque reference to a matching local Cell origin; callers cannot reconstruct the
 * internal logical key.
 */
class ImportedCellLocalOriginHandle internal constructor(
	internal val logicalTrackingId: String,
) {
	init {
		require(logicalTrackingId.isNotBlank())
	}

	override fun toString(): String = "ImportedCellLocalOriginHandle"
}

private data class ImportedCellOwnershipAudit(
	val localLogicalTrackingIdsByEntryIdentity: Map<String, String>,
	val sourceDeletedScopeDigests: Set<String>,
)

internal data class ImportedCellProductScanSnapshot(
	internal val state: SourceEvidenceState,
	internal val logicalIds: List<String>,
	internal val runs: List<ImportedCellLiveRunOwner>,
	internal val facts: List<ImportedCellLiveFactOwner>,
	internal val completeness: List<ImportedCellLiveCompletenessOwner>,
	internal val generations: List<CellCaptureDeletionGenerationEntity>,
	internal val directLocalLogicalByEntryIdentity: Map<String, String>,
) {
	fun localOriginHandle(identity: String): ImportedCellLocalOriginHandle? =
		directLocalLogicalByEntryIdentity[identity]?.let(::ImportedCellLocalOriginHandle)
}

/**
 * Transaction-scoped imported Cell scan sharing its snapshot and bounded read usage between pages.
 */
class ImportedCellProductScanScope internal constructor(
	private val reader: ImportedCellProductReader,
	private val issuer: Any,
	private val snapshot: ImportedCellProductScanSnapshot,
) {
	private val active = AtomicBoolean(true)

	/**
	 * Reads a recent imported Cell page under this live scan context and the supplied remaining
	 * dependency budget.
	 */
	suspend fun selectRecentPageInTransaction(
		budget: ImportedCellProductReadBudget,
		limit: Int,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): ImportedCellProductScanPage = reader.selectRecentPageInScope(
		this,
		budget,
		limit,
		beforeStartTimeMs,
		beforeIdentity,
	)

	/**
	 * Checks for an older candidate after the paired UTC millisecond/identity cursor without
	 * materializing its full payload.
	 */
	suspend fun hasRecentCandidateInTransaction(
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): Boolean = reader.hasRecentCandidateInScope(
		this,
		beforeStartTimeMs,
		beforeIdentity,
	)

	internal fun requireActive(
		expectedIssuer: Any,
		inTransaction: Boolean,
	): ImportedCellProductScanSnapshot {
		if (!active.get() || issuer !== expectedIssuer || !inTransaction) {
			throw ImportedCellProductScanContextFailure(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return snapshot
	}

	internal fun invalidate() {
		active.set(false)
	}

	override fun toString(): String = "ImportedCellProductScanScope"
}

/**
 * Signals a wrong or expired imported Cell scan context using its explicit source failure reason.
 */
class ImportedCellProductScanContextFailure(
	val reason: ImportedCellProductFailure,
) : IllegalArgumentException(reason.name)

/**
 * Remaining finite row, public-observation and UTF-8 text-byte allowances for one shared imported
 * Cell scan.
 */
data class ImportedCellProductReadBudget(
	val revisionRows: Int,
	val receiptRows: Int,
	val runRows: Int,
	val observationRows: Int,
	val publicObservationRows: Int,
	val textBytes: Long,
) {
	init {
		require(
			listOf(
				revisionRows,
				receiptRows,
				runRows,
				observationRows,
				publicObservationRows,
			).all { it >= 0 },
		)
		require(textBytes >= 0L)
	}

	/**
	 * Subtracts actual page usage from all remaining limits, returning null if any dimension would be
	 * exceeded.
	 */
	fun consume(usage: ImportedCellProductReadUsage): ImportedCellProductReadBudget? =
		takeIf { canLoad(usage) }?.let {
			copy(
				revisionRows = revisionRows - usage.revisionRows,
				receiptRows = receiptRows - usage.receiptRows,
				runRows = runRows - usage.runRows,
				observationRows = observationRows - usage.observationRows,
				publicObservationRows = publicObservationRows - usage.publicObservationRows,
				textBytes = textBytes - usage.textBytes,
			)
		}

	internal fun canLoadHierarchy(usage: ImportedCellProductReadUsage): Boolean =
		usage.revisionRows <= revisionRows &&
			usage.receiptRows <= receiptRows &&
			usage.runRows <= runRows &&
			usage.observationRows <= observationRows &&
			usage.textBytes <= textBytes

	internal fun canLoad(usage: ImportedCellProductReadUsage): Boolean =
		canLoadHierarchy(usage) && usage.publicObservationRows <= publicObservationRows

	/**
	 * Fixed shared-history budget plus the internal unbounded preflight budget.
	 */
	companion object {
		/**
		 * Creates the fixed row and one-megabyte text budget used by a shared imported Cell history
		 * read.
		 */
		fun sharedHistory() = ImportedCellProductReadBudget(
			revisionRows = 256,
			receiptRows = 512,
			runRows = 256,
			observationRows = 4_096,
			publicObservationRows = 4_096,
			textBytes = 1L * 1_024L * 1_024L,
		)

		internal fun unbounded() = ImportedCellProductReadBudget(
			revisionRows = Int.MAX_VALUE,
			receiptRows = Int.MAX_VALUE,
			runRows = Int.MAX_VALUE,
			observationRows = Int.MAX_VALUE,
			publicObservationRows = Int.MAX_VALUE,
			textBytes = Long.MAX_VALUE,
		)
	}
}

/**
 * Nonnegative revision, receipt, run, observation and text-byte cost of an imported Cell page.
 */
data class ImportedCellProductReadUsage(
	val revisionRows: Int,
	val receiptRows: Int,
	val runRows: Int,
	val observationRows: Int,
	val publicObservationRows: Int,
	val textBytes: Long,
) {
	init {
		require(
			listOf(
				revisionRows,
				receiptRows,
				runRows,
				observationRows,
				publicObservationRows,
			).all { it >= 0 },
		)
		require(textBytes >= 0L)
	}

	/**
	 * Adds each row/byte usage dimension with exact overflow checking.
	 */
	operator fun plus(other: ImportedCellProductReadUsage) = ImportedCellProductReadUsage(
		revisionRows = Math.addExact(revisionRows, other.revisionRows),
		receiptRows = Math.addExact(receiptRows, other.receiptRows),
		runRows = Math.addExact(runRows, other.runRows),
		observationRows = Math.addExact(observationRows, other.observationRows),
		publicObservationRows = Math.addExact(
			publicObservationRows,
			other.publicObservationRows,
		),
		textBytes = Math.addExact(textBytes, other.textBytes),
	)

	/**
	 * Zero-cost seed for accumulating real imported Cell read usage.
	 */
	companion object {
		val ZERO = ImportedCellProductReadUsage(0, 0, 0, 0, 0, 0L)
	}
}

/**
 * Evaluated imported Cell entries together with the actual bounded dependency usage of their read.
 */
data class ImportedCellProductScanPage(
	val evaluations: List<ImportedCellProductEvaluation>,
	val usage: ImportedCellProductReadUsage,
)

private data class ImportedCellProductBatchEvaluation(
	val evaluations: List<ImportedCellProductEvaluation>,
	val usage: ImportedCellProductReadUsage,
	val budgetExceeded: Boolean,
)

private data class ImportedCellProductBatch(
	val headers: List<ImportedCellEntryRevisionEntity>,
	val receipts: List<ImportedCellReceiptEntity>,
	val runs: List<ImportedCellRunEntity>,
	val observations: List<ImportedCellObservationEntity>,
	val entryDeletions: List<ImportedCellEntryDeletionEntity>,
	val runDeletions: List<ImportedCellDeletionGenerationEntity>,
) {
	/** Groups and authenticates this already-bounded hierarchy without querying outside its snapshot. */
	suspend fun authenticateCandidates(
		candidates: List<ImportedCellHistoryCandidate>,
		state: SourceEvidenceState,
	): Pair<Map<String, AuthenticatedCellCandidate>, MutableMap<String, ImportedCellProductFailure>>? {
		val headersByIdentity = headers.groupBy(ImportedCellEntryRevisionEntity::identity)
		val receiptsByIdentity = receipts.groupBy(ImportedCellReceiptEntity::entryIdentity)
		val runsByIdentity = runs.groupBy(ImportedCellRunEntity::entryIdentity)
		val observationsByIdentity = observations.groupBy(ImportedCellObservationEntity::entryIdentity)
		val entryDeletionsByIdentity = entryDeletions.associateBy(
			ImportedCellEntryDeletionEntity::entryIdentity,
		)
		if (entryDeletionsByIdentity.size != entryDeletions.size) {
			return null
		}
		val runDeletionsByEntry = runDeletions.groupBy(
			ImportedCellDeletionGenerationEntity::entryIdentity,
		)

		val authenticated = linkedMapOf<String, AuthenticatedCellCandidate>()
		val failures = linkedMapOf<String, ImportedCellProductFailure>()
		for (candidate in candidates) {
			currentCoroutineContext().ensureActive()
			val headers = headersByIdentity[candidate.identity].orEmpty()
			if (headers.any { !state.acceptsRetainedEpoch(it.collectedDataEpoch) }) {
				failures[candidate.identity] = ImportedCellProductFailure.STALE_COLLECTED_DATA_EPOCH
				continue
			}
			try {
				val lineage = ImportedCellLineageAuthenticator.authenticate(
					identity = candidate.identity,
					expectedCollectedDataEpoch = state.collectedDataEpoch,
					retainedState = state,
					headers = headers,
					receipts = receiptsByIdentity[candidate.identity].orEmpty(),
					runs = runsByIdentity[candidate.identity].orEmpty(),
					observations = observationsByIdentity[candidate.identity].orEmpty(),
				)
				authenticated[candidate.identity] = AuthenticatedCellCandidate.authenticate(
					candidate, lineage, state, entryDeletionsByIdentity, runDeletionsByEntry)
			} catch (failure: ImportedCellOwnershipFailure) {
				failures[candidate.identity] = failure.reason
			} catch (failure: ImportedCellLineageFailure) {
				failures[candidate.identity] = failure.reason.toProductFailure()
			} catch (_: ArithmeticException) {
				failures[candidate.identity] = ImportedCellProductFailure.VALUE_OVERFLOW
			} catch (_: RuntimeException) {
				failures[candidate.identity] =
					ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
			}
		}
		return authenticated to failures
	}

	fun exceedsLimits(identityCount: Int): Boolean =
		headers.size > identityCount * ImportedCellDao.MAX_REVISIONS_PER_ENTRY ||
			receipts.size > identityCount * ImportedCellDao.MAX_RECEIPTS_PER_ENTRY ||
			runs.size > identityCount * ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE ||
			observations.size > identityCount * ImportedCellDao.MAX_OBSERVATION_ROWS_PER_LINEAGE ||
			entryDeletions.size > identityCount ||
			runDeletions.size > identityCount * ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE

	fun matches(preflight: ImportedCellHistoryPreflight): Boolean =
		headers.size.toLong() == preflight.revisionRows &&
			receipts.size.toLong() == preflight.receiptRows &&
			runs.size.toLong() == preflight.runRows &&
			observations.size.toLong() == preflight.observationRows &&
			entryDeletions.size.toLong() == preflight.entryDeletionRows &&
			runDeletions.size.toLong() == preflight.runDeletionRows

	fun belongsOnlyTo(identities: List<String>): Boolean {
		val expected = identities.toHashSet()
		return headers.all { it.identity in expected } &&
			receipts.all { it.entryIdentity in expected } &&
			runs.all { it.entryIdentity in expected } &&
			observations.all { it.entryIdentity in expected } &&
			entryDeletions.all { it.entryIdentity in expected } &&
			runDeletions.all { it.entryIdentity in expected }
	}

	companion object {
		suspend fun load(
			dao: ImportedCellDao,
			identities: List<String>,
			preflight: ImportedCellHistoryPreflight,
		): ImportedCellProductBatch = ImportedCellProductBatch(
			headers = dao.entryRevisionsForHistory(identities, exactRowLimit(preflight.revisionRows)),
			receipts = dao.receiptsForHistory(identities, exactRowLimit(preflight.receiptRows)),
			runs = dao.runsForHistory(identities, exactRowLimit(preflight.runRows)),
			observations = dao.observationsForHistory(identities, exactRowLimit(preflight.observationRows)),
			entryDeletions = dao.entryDeletionsForHistory(identities, exactRowLimit(preflight.entryDeletionRows)),
			runDeletions = dao.deletionGenerationsForHistory(identities, exactRowLimit(preflight.runDeletionRows)),
		)

		private fun exactRowLimit(rowCount: Long): Int = Math.toIntExact(Math.addExact(rowCount, 1L))
	}
}

private fun ImportedCellHistoryPreflight.hasValidBounds(identityCount: Int): Boolean {
	if (identityCount <= 0 || listOf(
			revisionRows,
			receiptRows,
			runRows,
			observationRows,
			entryDeletionRows,
			runDeletionRows,
			textBytes,
		).any { it < 0L }
	) {
		return false
	}
	val identities = identityCount.toLong()
	return revisionRows <= identities * ImportedCellDao.MAX_REVISIONS_PER_ENTRY &&
		receiptRows <= identities * ImportedCellDao.MAX_RECEIPTS_PER_ENTRY &&
		runRows <= identities * ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE &&
		observationRows <= identities * ImportedCellDao.MAX_OBSERVATION_ROWS_PER_LINEAGE &&
		entryDeletionRows <= identities &&
		runDeletionRows <= identities * ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE
}

private fun ImportedCellHistoryPreflight.toReadUsage() = ImportedCellProductReadUsage(
	revisionRows = Math.toIntExact(Math.addExact(revisionRows, entryDeletionRows)),
	receiptRows = Math.toIntExact(receiptRows),
	runRows = Math.toIntExact(Math.addExact(runRows, runDeletionRows)),
	observationRows = Math.toIntExact(observationRows),
	publicObservationRows = 0,
	textBytes = textBytes,
)

private fun batchFailure(
	candidates: List<ImportedCellHistoryCandidate>,
	reason: ImportedCellProductFailure,
	context: ImportedCellProductScanSnapshot,
	usage: ImportedCellProductReadUsage = ImportedCellProductReadUsage.ZERO,
	budgetExceeded: Boolean = false,
) = ImportedCellProductBatchEvaluation(
	evaluations = candidates.unverifiable(reason, context),
	usage = usage,
	budgetExceeded = budgetExceeded,
)

private data class ImportedCellProductIdentityGraph(
	val kinds: Map<String, PortableCellIdentityKind>,
	val runOwners: Map<String, Pair<String, String>>,
	val scopeOwners: Map<String, Pair<String, String>>,
	val observationOwners: Map<String, ImportedCellObservationOwner>,
) {
	val allProtectedValues: List<String> =
		(kinds.keys + scopeOwners.keys).distinct().sorted()

	fun hasExactRunOwner(entryIdentity: String, runIdentity: String, scope: String): Boolean =
		kinds[entryIdentity] == PortableCellIdentityKind.LOGICAL_ENTRY &&
			hasMatchingRunAndScopeOwners(entryIdentity, runIdentity, scope)

	fun hasMatchingRunAndScopeOwners(entryIdentity: String, runIdentity: String, scope: String): Boolean =
		runOwners[runIdentity] == (entryIdentity to scope) &&
			scopeOwners[scope] == (entryIdentity to runIdentity)

	@Suppress("CyclomaticComplexMethod") // Keep this fixed field or enum projection together.
	fun accepts(
		identity: String,
		entryIdentity: String,
		runIdentity: String,
		aggregateOwnerIdentity: String?,
	): Boolean {
		val directIntersects = identity in allProtectedValues
		val aggregateIntersects = aggregateOwnerIdentity?.let { it in allProtectedValues } == true
		if (!directIntersects && !aggregateIntersects) {
			return false
		}
		val expectedDirect = observationOwners[identity]
		val expectedAggregate = aggregateOwnerIdentity?.let(observationOwners::get)
		return (directIntersects && expectedDirect == null).not() &&
			(aggregateIntersects && expectedAggregate == null).not() &&
			(expectedDirect == null || expectedDirect == ImportedCellObservationOwner(
				entryIdentity,
				runIdentity,
				aggregateOwnerIdentity,
			)) &&
			(expectedAggregate == null || expectedAggregate == ImportedCellObservationOwner(
				entryIdentity,
				runIdentity,
				null,
			))
	}

	/**
	 * Reconstructs and merges original imported Cell ownership graphs, rejecting identity/scope
	 * collisions.
	 */
	companion object {
		fun from(lineage: AuthenticatedImportedCellLineage): ImportedCellProductIdentityGraph? =
			merge(lineage.revisions.map { revision -> from(revision.entry) ?: return null })

		fun merge(graphs: Collection<ImportedCellProductIdentityGraph>):
			ImportedCellProductIdentityGraph? {
			val kinds = linkedMapOf<String, PortableCellIdentityKind>()
			val runs = linkedMapOf<String, Pair<String, String>>()
			val scopes = linkedMapOf<String, Pair<String, String>>()
			val observations = linkedMapOf<String, ImportedCellObservationOwner>()
			for (graph in graphs) {
				if (!kinds.appendConsistentClaims(graph.kinds) ||
					!runs.appendConsistentClaims(graph.runOwners) ||
					!scopes.appendConsistentClaims(graph.scopeOwners) ||
					!observations.appendConsistentClaims(graph.observationOwners)
				) {
					return null
				}
			}
			val graph = ImportedCellProductIdentityGraph(kinds, runs, scopes, observations)
			if (graph.kinds.size != runs.size + observations.size +
				graph.kinds.values.count { it == PortableCellIdentityKind.LOGICAL_ENTRY } ||
				graph.kinds.keys.any { it in graph.scopeOwners }
			) {
				return null
			}
			return graph
		}

		private fun <T> MutableMap<String, T>.appendConsistentClaims(claims: Map<String, T>): Boolean {
			for ((identity, claim) in claims) {
				if (putIfAbsent(identity, claim)?.let { it != claim } == true) {
					return false
				}
			}
			return true
		}

		private fun from(entry: PortableCapturedCellEntryV1): ImportedCellProductIdentityGraph? {
			val kinds = linkedMapOf(entry.identity.value to PortableCellIdentityKind.LOGICAL_ENTRY)
			val runs = linkedMapOf<String, Pair<String, String>>()
			val scopes = linkedMapOf<String, Pair<String, String>>()
			val observations = linkedMapOf<String, ImportedCellObservationOwner>()
			for (run in entry.runs) {
				if (kinds.putIfAbsent(
						run.identity.value,
						PortableCellIdentityKind.PHYSICAL_RUN,
					) != null
				) {
					return null
				}
				val runOwner = entry.identity.value to run.deletionScopeDigest.value
				if (runs.putIfAbsent(run.identity.value, runOwner) != null ||
					scopes.putIfAbsent(
						run.deletionScopeDigest.value,
						entry.identity.value to run.identity.value,
					) != null
				) {
					return null
				}
				for (observation in run.observations) {
					if (kinds.putIfAbsent(
							observation.identity.value,
							PortableCellIdentityKind.OBSERVATION,
						) != null ||
						observations.putIfAbsent(
							observation.identity.value,
							ImportedCellObservationOwner(
								entry.identity.value,
								run.identity.value,
								observation.aggregateOwnerIdentity?.value,
							),
						) != null
					) {
						return null
					}
				}
			}
			return ImportedCellProductIdentityGraph(kinds, runs, scopes, observations)
				.takeIf { graph -> graph.kinds.keys.none { it in graph.scopeOwners } }
		}
	}
}

private data class ImportedCellObservationOwner(
	val entryIdentity: String,
	val runIdentity: String,
	val aggregateOwnerIdentity: String?,
)

private class ImportedCellOwnershipFailure(
	val reason: ImportedCellProductFailure,
) : IllegalArgumentException(reason.name)

private fun ImportedCellHistoryCandidate.exactlyMatches(
	latest: AuthenticatedImportedCellRevision,
): Boolean = identity == latest.header.identity &&
	importRevision == latest.header.importRevision &&
	contentChecksum == latest.header.contentChecksum &&
	startTimeMs == latest.header.startTimeMs &&
	endTimeMs == latest.header.endTimeMs &&
	receivedAtMs == latest.header.receivedAtMs

private fun List<ImportedCellHistoryCandidate>.unverifiable(
	reason: ImportedCellProductFailure,
) = map { ImportedCellProductEvaluation.Unverifiable(it, reason) }

private fun List<ImportedCellHistoryCandidate>.unverifiable(
	reason: ImportedCellProductFailure,
	context: ImportedCellProductScanSnapshot,
) = map { candidate ->
	ImportedCellProductEvaluation.Unverifiable(
		candidate,
		reason,
		context.localOriginHandle(candidate.identity),
	)
}

private fun PortableCellImportUnverifiableReason.toProductFailure(): ImportedCellProductFailure =
	when (this) {
		PortableCellImportUnverifiableReason.DEPENDENCY_OVERFLOW,
		PortableCellImportUnverifiableReason.RUN_OVERFLOW,
		PortableCellImportUnverifiableReason.OBSERVATION_OVERFLOW,
		PortableCellImportUnverifiableReason.TOTAL_OBSERVATION_OVERFLOW,
		PortableCellImportUnverifiableReason.REVISION_OVERFLOW,
		-> ImportedCellProductFailure.DEPENDENCY_OVERFLOW
		PortableCellImportUnverifiableReason.ENTRY_INVALID,
		PortableCellImportUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
		PortableCellImportUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
		-> ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE
	}

private fun SourceEvidenceState.hasValidImportedCellProductShape(): Boolean =
	id == SourceEvidenceState.SINGLETON_ID && revision >= 0L && collectedDataEpoch >= 0L &&
		retainedFromMs?.let { it >= 0L } != false && deletedSourceEventHighWaterOrdinal >= 0L &&
		updatedAtMs >= 0L
