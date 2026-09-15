package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedCellDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveCompletenessOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveFactOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellLiveRunOwner
import com.adsamcik.tracker.shared.base.database.dao.ImportedCellOpaqueOwnerRow
import com.adsamcik.tracker.shared.base.database.data.CellCaptureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellObservationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedCellRunEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import com.adsamcik.tracker.shared.base.database.data.SourceProductLaneExecutionAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Authenticates imported Cell product facts without creating local provider, session, manifest,
 * policy, consent, WAL, or writer authority. Callers own the enclosing Room transaction.
 */
class ImportedCellProductReader(
	private val database: AppDatabase,
	private val batchCheckpoint: suspend (completedBatchCount: Int) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
	private val ownerChunkCheckpoint: suspend (completedChunkCount: Int) -> Unit = {
		currentCoroutineContext().ensureActive()
	},
) {
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

	suspend fun selectIdentityInTransaction(
		identity: PortableCellOpaqueIdentity,
	): ImportedCellProductEvaluation? {
		val candidate = database.importedCellDao().latestHistoryCandidate(identity.value) ?: return null
		return evaluateCandidates(listOf(candidate), null, null, 1).single()
	}

	suspend fun selectRecentInTransaction(limit: Int): List<ImportedCellProductEvaluation> {
		require(limit in 1..ImportedCellDao.MAX_HISTORY_ENTRY_CANDIDATES)
		val candidates = database.importedCellDao().recentHistoryCandidatePage(
			limit = limit,
			beforeStartTimeMs = null,
			beforeIdentity = null,
		)
		return evaluateCandidates(
			candidates = candidates,
			beforeStartTimeMs = null,
			beforeIdentity = null,
			maximumSize = ImportedCellDao.MAX_HISTORY_ENTRY_CANDIDATES,
		)
	}

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
			if (page.isEmpty()) break
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
			if (page.size < pageLimit) break
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
	): List<ImportedCellProductEvaluation> {
		if (!isValidCandidatePage(candidates, beforeStartTimeMs, beforeIdentity, maximumSize)) {
			return candidates.unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (candidates.isEmpty()) return emptyList()
		val context = try {
			loadPageContext(candidates)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedCellPageContextFailure) {
			return candidates.unverifiable(failure.reason)
		} catch (_: RuntimeException) {
			return candidates.unverifiable(ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val evaluated = mutableListOf<ImportedCellProductEvaluation>()
		candidates.chunked(ImportedCellDao.HISTORY_EVALUATION_BATCH_SIZE)
			.forEachIndexed { index, batch ->
				batchCheckpoint(index)
				evaluated += evaluateBatch(batch, context)
			}
		return evaluated
	}

	@Suppress("LongMethod")
	private suspend fun evaluateBatch(
		candidates: List<ImportedCellHistoryCandidate>,
		context: ImportedCellPageContext,
	): List<ImportedCellProductEvaluation> {
		if (candidates.isEmpty()) return emptyList()
		val state = context.state
		val identities = candidates.map(ImportedCellHistoryCandidate::identity)
		val loaded = try {
			ImportedCellProductBatch(
				headers = database.importedCellDao().entryRevisionsForHistory(
					identities,
					historyLimit(identities.size, ImportedCellDao.MAX_REVISIONS_PER_ENTRY),
				),
				receipts = database.importedCellDao().receiptsForHistory(
					identities,
					historyLimit(identities.size, ImportedCellDao.MAX_RECEIPTS_PER_ENTRY),
				),
				runs = database.importedCellDao().runsForHistory(
					identities,
					historyLimit(identities.size, ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE),
				),
				observations = database.importedCellDao().observationsForHistory(
					identities,
					historyLimit(identities.size, ImportedCellDao.MAX_OBSERVATION_ROWS_PER_LINEAGE),
				),
				entryDeletions = database.importedCellDao().entryDeletionsForHistory(
					identities,
					historyLimit(identities.size, 1),
				),
				runDeletions = database.importedCellDao().deletionGenerationsForHistory(
					identities,
					historyLimit(identities.size, ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE),
				),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: ArithmeticException) {
			return candidates.unverifiable(ImportedCellProductFailure.DEPENDENCY_OVERFLOW, context)
		} catch (_: RuntimeException) {
			return candidates.unverifiable(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				context,
			)
		}
		if (loaded.exceedsLimits(identities.size)) {
			return candidates.unverifiable(ImportedCellProductFailure.DEPENDENCY_OVERFLOW, context)
		}
		if (!loaded.belongsOnlyTo(identities)) {
			return candidates.unverifiable(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				context,
			)
		}

		val headersByIdentity = loaded.headers.groupBy(ImportedCellEntryRevisionEntity::identity)
		val receiptsByIdentity = loaded.receipts.groupBy(ImportedCellReceiptEntity::entryIdentity)
		val runsByIdentity = loaded.runs.groupBy(ImportedCellRunEntity::entryIdentity)
		val observationsByIdentity = loaded.observations.groupBy(ImportedCellObservationEntity::entryIdentity)
		val entryDeletionsByIdentity = loaded.entryDeletions.associateBy(
			ImportedCellEntryDeletionEntity::entryIdentity,
		)
		if (entryDeletionsByIdentity.size != loaded.entryDeletions.size) {
			return candidates.unverifiable(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				context,
			)
		}
		val runDeletionsByEntry = loaded.runDeletions.groupBy(
			ImportedCellDeletionGenerationEntity::entryIdentity,
		)

		val authenticated = linkedMapOf<String, AuthenticatedCellCandidate>()
		val failures = linkedMapOf<String, ImportedCellProductFailure>()
		for (candidate in candidates) {
			currentCoroutineContext().ensureActive()
			val headers = headersByIdentity[candidate.identity].orEmpty()
			if (headers.any { it.collectedDataEpoch != state.collectedDataEpoch }) {
				failures[candidate.identity] = ImportedCellProductFailure.STALE_COLLECTED_DATA_EPOCH
				continue
			}
			try {
				val lineage = ImportedCellLineageAuthenticator.authenticate(
					identity = candidate.identity,
					expectedCollectedDataEpoch = state.collectedDataEpoch,
					headers = headers,
					receipts = receiptsByIdentity[candidate.identity].orEmpty(),
					runs = runsByIdentity[candidate.identity].orEmpty(),
					observations = observationsByIdentity[candidate.identity].orEmpty(),
				)
				val latest = lineage.revisions.lastOrNull()
					?: throw IllegalArgumentException("Imported Cell candidate has no revision")
				if (!candidate.exactlyMatches(latest)) {
					throw IllegalArgumentException("Imported Cell candidate is not the latest revision")
				}
				val entryDeletion = entryDeletionsByIdentity[candidate.identity]
				if (entryDeletion != null && (
					entryDeletion.collectedDataEpoch != state.collectedDataEpoch ||
						entryDeletion.deletedImportRevision != latest.header.importRevision
					)
				) throw IllegalArgumentException("Imported Cell entry deletion is stale")
				val graph = ImportedCellProductIdentityGraph.from(lineage)
					?: throw ImportedCellOwnershipFailure(
						ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT,
					)
				val deletedRuns = runDeletionsByEntry[candidate.identity].orEmpty()
				if (deletedRuns.any { marker ->
					marker.collectedDataEpoch != state.collectedDataEpoch || marker.generation != 1L ||
						graph.runOwners[marker.runIdentity] !=
						(marker.entryIdentity to marker.deletionScopeDigest)
				}) throw IllegalArgumentException("Imported Cell run deletion is stale or orphaned")
				authenticated[candidate.identity] = AuthenticatedCellCandidate(
					candidate = candidate,
					lineage = lineage,
					graph = graph,
					entryDeleted = entryDeletion != null,
					importedDeletedRunIdentities = deletedRuns.mapTo(linkedSetOf()) {
						it.runIdentity
					},
				)
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

		val audit = if (authenticated.isEmpty()) {
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

		return candidates.map { candidate ->
			failures[candidate.identity]?.let { reason ->
				ImportedCellProductEvaluation.Unverifiable(
					candidate,
					reason,
					context.localOriginHandle(candidate.identity),
				)
			} ?: authenticated.getValue(candidate.identity).toEvaluation(requireNotNull(audit), state)
		}
	}

	private suspend fun loadPageContext(
		candidates: List<ImportedCellHistoryCandidate>,
	): ImportedCellPageContext {
		val state = database.sourceEvidenceStateDao().get()
			?: throw ImportedCellPageContextFailure(
				ImportedCellProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
			)
		if (!state.hasValidImportedCellProductShape()) {
			throw ImportedCellPageContextFailure(
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
			throw ImportedCellPageContextFailure(ImportedCellProductFailure.DEPENDENCY_OVERFLOW)
		}
		if (logicalIds.any(String::isBlank) || logicalIds.distinct().size != logicalIds.size) {
			throw ImportedCellPageContextFailure(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val candidateIdentities = candidates.mapTo(hashSetOf(), ImportedCellHistoryCandidate::identity)
		val localLogicalClaims = (
			logicalIds + runs.map { it.logicalTrackingId } +
				facts.map { it.logicalTrackingId } +
				completeness.map { it.logicalTrackingId } +
				generations.map { it.logicalTrackingId }
			).distinct()
		if (localLogicalClaims.any(String::isBlank)) {
			throw ImportedCellPageContextFailure(
				ImportedCellProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val localLogicalByEntry = localLogicalClaims.mapNotNull { logicalId ->
			val entryIdentity = PortableCellOpaqueIdentity.derive(
				PortableCellIdentityKind.LOGICAL_ENTRY,
				logicalId,
			).value
			(entryIdentity to logicalId).takeIf { entryIdentity in candidateIdentities }
		}.groupBy({ it.first }, { it.second }).mapValues { (_, owners) ->
			owners.distinct().singleOrNull() ?: throw ImportedCellPageContextFailure(
				ImportedCellProductFailure.ORIGIN_IDENTITY_CONFLICT,
			)
		}
		return ImportedCellPageContext(
			state = state,
			logicalIds = logicalIds,
			runs = runs,
			facts = facts,
			completeness = completeness,
			generations = generations,
			directLocalLogicalByEntryIdentity = localLogicalByEntry,
		)
	}

	@Suppress("LongMethod", "ComplexCondition")
	private suspend fun authenticateOwnership(
		dao: ImportedCellDao,
		graph: ImportedCellProductIdentityGraph,
		context: ImportedCellPageContext,
	): ImportedCellOwnershipAudit {
		val state = context.state
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
			if (rows.size >= limit) dependencyOverflow()
			rows.forEach { row ->
				authenticateImportedOwner(row, graph, state, sourceFences)
			}
		}

		val logicalIds = context.logicalIds
		val liveRuns = context.runs
		val facts = context.facts
		val completeness = context.completeness
		val generations = context.generations
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
		val liveRunById = liveRuns.associateBy { it.serviceRunId }
		if (liveRunById.size != liveRuns.size || liveRuns.any {
			it.serviceRunId.isBlank() || it.logicalTrackingId.isBlank()
		}) storedCorrupt()
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
				if (graph.kinds[entryIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY ||
					graph.runOwners[runIdentity] != (entryIdentity to scope) ||
					graph.scopeOwners[scope] != (entryIdentity to runIdentity)
				) originConflict()
				logicalByEntryIdentity[entryIdentity] = owner.logicalTrackingId
			}
		}
		completeness.forEach { owner ->
			val liveRun = liveRunById[owner.serviceRunId]
			if (liveRun == null || liveRun.logicalTrackingId != owner.logicalTrackingId) storedCorrupt()
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
				(graph.kinds[entryIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY ||
					graph.runOwners[runIdentity] != (entryIdentity to scope) ||
					graph.scopeOwners[scope] != (entryIdentity to runIdentity))
			) originConflict()
		}
		facts.groupBy(ImportedCellLiveFactOwner::logicalFactId).forEach { (logicalFactId, claims) ->
			val structuralClaims = claims.map {
				Triple(
					it.logicalTrackingId,
					it.serviceRunId,
					it.aggregateOwnerLogicalFactId to it.aggregateOwnerSemanticRevision,
				)
			}.distinct()
			if (logicalFactId.isBlank() || structuralClaims.size != 1) storedCorrupt()
			val claim = claims.first()
			val liveRun = liveRunById[claim.serviceRunId]
			if (liveRun == null || liveRun.logicalTrackingId != claim.logicalTrackingId) storedCorrupt()
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
			) return@forEach
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
			) originConflict()
		}

		val relevantGenerations = linkedMapOf<String, Long>()
		generations.forEach { generation ->
			if (generation.collectedDataEpoch != state.collectedDataEpoch ||
				generation.logicalTrackingId.isBlank() || generation.serviceRunId.isBlank() ||
				generation.generation <= 0L
			) storedCorrupt()
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
			if (graph.runOwners[runIdentity] != (entryIdentity to scope) ||
				graph.scopeOwners[scope] != (entryIdentity to runIdentity)
			) originConflict()
			if (relevantGenerations.put(scope, generation.generation) != null) storedCorrupt()
		}
		if (relevantGenerations.keys != sourceFences.keys ||
			relevantGenerations.any { (scope, generation) ->
				sourceFences.getValue(scope) != generation
			}
		) {
			storedCorrupt()
		}
		return ImportedCellOwnershipAudit(
			localLogicalTrackingIdsByEntryIdentity =
				context.directLocalLogicalByEntryIdentity + logicalByEntryIdentity,
			sourceDeletedScopeDigests = sourceFences.keys,
		)
	}

	@Suppress("ComplexCondition")
	private fun authenticateImportedOwner(
		row: ImportedCellOpaqueOwnerRow,
		graph: ImportedCellProductIdentityGraph,
		state: SourceEvidenceState,
		sourceFences: MutableMap<String, Long>,
	) {
		if (row.collectedDataEpoch?.let { it != state.collectedDataEpoch } == true) storedCorrupt()
		when (row.ownerKind) {
			ImportedCellOpaqueOwnerRow.ENTRY -> {
				if (graph.kinds[row.protectedIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY ||
					row.entryIdentity != row.protectedIdentity
				) originConflict()
			}
			ImportedCellOpaqueOwnerRow.RUN -> {
				val entry = row.entryIdentity ?: originConflict()
				val scope = row.deletionScopeDigest ?: originConflict()
				if (graph.kinds[row.protectedIdentity] != PortableCellIdentityKind.PHYSICAL_RUN ||
					graph.runOwners[row.protectedIdentity] != (entry to scope)
				) originConflict()
			}
			ImportedCellOpaqueOwnerRow.SCOPE -> {
				val entry = row.entryIdentity ?: originConflict()
				val run = row.runIdentity ?: originConflict()
				if (graph.scopeOwners[row.protectedIdentity] != (entry to run)) originConflict()
			}
			ImportedCellOpaqueOwnerRow.OBSERVATION -> {
				if (!graph.accepts(
						identity = row.protectedIdentity,
						entryIdentity = row.entryIdentity ?: originConflict(),
						runIdentity = row.runIdentity ?: originConflict(),
						aggregateOwnerIdentity = row.aggregateOwnerIdentity,
					)
				) originConflict()
			}
			ImportedCellOpaqueOwnerRow.ENTRY_DELETION -> {
				if (graph.kinds[row.protectedIdentity] != PortableCellIdentityKind.LOGICAL_ENTRY ||
					row.entryIdentity != row.protectedIdentity
				) originConflict()
			}
			ImportedCellOpaqueOwnerRow.RUN_DELETION -> {
				val entry = row.entryIdentity ?: originConflict()
				val scope = row.deletionScopeDigest ?: originConflict()
				if (graph.runOwners[row.protectedIdentity] != (entry to scope) ||
					row.generation != 1L
				) originConflict()
			}
			ImportedCellOpaqueOwnerRow.SOURCE_FENCE -> {
				val scope = row.deletionScopeDigest ?: originConflict()
				val expected = graph.scopeOwners[scope] ?: originConflict()
				val generation = row.generation ?: originConflict()
				if (row.sourceKind != SourceDestinationOwnerEntity.SOURCE_CELL ||
					row.purpose != SessionManifestPurposeCode.SESSION_CAPTURE ||
					row.scopeKind != SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN ||
					graph.runOwners[expected.second] != (expected.first to scope) ||
					generation <= 0L || sourceFences.put(scope, generation) != null
				) originConflict()
			}
			ImportedCellOpaqueOwnerRow.DELETION_RECEIPT,
			ImportedCellOpaqueOwnerRow.DELETED_IDENTITY,
			-> storedCorrupt()
			else -> storedCorrupt()
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
		) return false
		if (candidates.any { candidate ->
			runCatching { PortableCellOpaqueIdentity(candidate.identity) }.isFailure ||
				runCatching { PortableCellDigest(candidate.contentChecksum) }.isFailure ||
				candidate.importRevision <= 0L || candidate.startTimeMs < 0L ||
				candidate.endTimeMs < candidate.startTimeMs || candidate.receivedAtMs < 0L
		}) return false
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

	private fun historyLimit(identityCount: Int, perIdentity: Int): Int =
		Math.addExact(Math.multiplyExact(identityCount, perIdentity), 1)

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

sealed interface ImportedCellProductEvaluation {
	val candidate: ImportedCellHistoryCandidate
	val localOriginHandle: ImportedCellLocalOriginHandle?

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

	data class Unverifiable(
		override val candidate: ImportedCellHistoryCandidate,
		val reason: ImportedCellProductFailure,
		override val localOriginHandle: ImportedCellLocalOriginHandle? = null,
	) : ImportedCellProductEvaluation
}

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
}

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

private data class ImportedCellPageContext(
	val state: SourceEvidenceState,
	val logicalIds: List<String>,
	val runs: List<ImportedCellLiveRunOwner>,
	val facts: List<ImportedCellLiveFactOwner>,
	val completeness: List<ImportedCellLiveCompletenessOwner>,
	val generations: List<CellCaptureDeletionGenerationEntity>,
	val directLocalLogicalByEntryIdentity: Map<String, String>,
) {
	fun localOriginHandle(identity: String): ImportedCellLocalOriginHandle? =
		directLocalLogicalByEntryIdentity[identity]?.let(::ImportedCellLocalOriginHandle)
}

private class ImportedCellPageContextFailure(
	val reason: ImportedCellProductFailure,
) : IllegalArgumentException(reason.name)

private data class ImportedCellProductBatch(
	val headers: List<ImportedCellEntryRevisionEntity>,
	val receipts: List<ImportedCellReceiptEntity>,
	val runs: List<ImportedCellRunEntity>,
	val observations: List<ImportedCellObservationEntity>,
	val entryDeletions: List<ImportedCellEntryDeletionEntity>,
	val runDeletions: List<ImportedCellDeletionGenerationEntity>,
) {
	fun exceedsLimits(identityCount: Int): Boolean =
		headers.size > identityCount * ImportedCellDao.MAX_REVISIONS_PER_ENTRY ||
			receipts.size > identityCount * ImportedCellDao.MAX_RECEIPTS_PER_ENTRY ||
			runs.size > identityCount * ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE ||
			observations.size > identityCount * ImportedCellDao.MAX_OBSERVATION_ROWS_PER_LINEAGE ||
			entryDeletions.size > identityCount ||
			runDeletions.size > identityCount * ImportedCellDao.MAX_RUN_ROWS_PER_LINEAGE

	fun belongsOnlyTo(identities: List<String>): Boolean {
		val expected = identities.toHashSet()
		return headers.all { it.identity in expected } &&
			receipts.all { it.entryIdentity in expected } &&
			runs.all { it.entryIdentity in expected } &&
			observations.all { it.entryIdentity in expected } &&
			entryDeletions.all { it.entryIdentity in expected } &&
			runDeletions.all { it.entryIdentity in expected }
	}
}

private data class ImportedCellProductIdentityGraph(
	val kinds: Map<String, PortableCellIdentityKind>,
	val runOwners: Map<String, Pair<String, String>>,
	val scopeOwners: Map<String, Pair<String, String>>,
	val observationOwners: Map<String, ImportedCellObservationOwner>,
) {
	val allProtectedValues: List<String> =
		(kinds.keys + scopeOwners.keys).distinct().sorted()

	fun accepts(
		identity: String,
		entryIdentity: String,
		runIdentity: String,
		aggregateOwnerIdentity: String?,
	): Boolean {
		val directIntersects = identity in allProtectedValues
		val aggregateIntersects = aggregateOwnerIdentity?.let { it in allProtectedValues } == true
		if (!directIntersects && !aggregateIntersects) return false
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
				for ((identity, kind) in graph.kinds) {
					if (kinds.putIfAbsent(identity, kind)?.let { it != kind } == true) return null
				}
				for ((identity, owner) in graph.runOwners) {
					if (runs.putIfAbsent(identity, owner)?.let { it != owner } == true) return null
				}
				for ((scope, owner) in graph.scopeOwners) {
					if (scopes.putIfAbsent(scope, owner)?.let { it != owner } == true) return null
				}
				for ((identity, owner) in graph.observationOwners) {
					if (observations.putIfAbsent(identity, owner)?.let { it != owner } == true) return null
				}
			}
			val graph = ImportedCellProductIdentityGraph(kinds, runs, scopes, observations)
			if (graph.kinds.size != runs.size + observations.size +
				graph.kinds.values.count { it == PortableCellIdentityKind.LOGICAL_ENTRY } ||
				graph.kinds.keys.any { it in graph.scopeOwners }
			) return null
			return graph
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
				) return null
				val runOwner = entry.identity.value to run.deletionScopeDigest.value
				if (runs.putIfAbsent(run.identity.value, runOwner) != null ||
					scopes.putIfAbsent(
						run.deletionScopeDigest.value,
						entry.identity.value to run.identity.value,
					) != null
				) return null
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
					) return null
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
	context: ImportedCellPageContext,
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
