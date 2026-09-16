package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityOrderedHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProductBatchPreflight
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProductRevisionSnapshot
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProtectedIdentityOwnerCount
import com.adsamcik.tracker.shared.base.database.dao.IMPORTED_ACTIVITY_CANDIDATE_LIVE
import com.adsamcik.tracker.shared.base.database.dao.IMPORTED_ACTIVITY_CANDIDATE_RETAINED
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedZoneRange
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityWindowEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityZoneEpochEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Authenticates imported Activity product facts without creating any live capture authority.
 * Callers own the enclosing Room transaction so imported and local composition share one snapshot.
 */
class ImportedActivityProductReader(
	private val database: AppDatabase,
) {
	/**
	 * Authenticates one imported Activity identity inside the caller's existing Room transaction.
	 *
	 * The result is a read-only product snapshot. It grants no provider, session, writer, deletion,
	 * or retention authority, and must not be carried across the transaction boundary for mutation.
	 */
	suspend fun selectIdentityInTransaction(
		identity: PortableActivityOpaqueIdentity,
	): ImportedActivityProductEvaluation? {
		val dao = database.importedActivityDao()
		val live = dao.latestHistoryCandidate(identity.value)
		val retained = dao.retainedHistoryCandidate(identity.value)
		if (live != null && retained != null) {
			return live.unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
		}
		val candidate = live ?: retained ?: return null
		return evaluateCandidates(listOf(candidate), null, null, 1).single()
	}

	/**
	 * Visits every imported entry shell for retention one lineage at a time. Safe payload is
	 * discarded before the next lineage; only the caller's compact authority may outlive a visit.
	 */
	internal suspend fun visitAllForRetentionInTransaction(
		visit: suspend (ImportedActivityProductEvaluation) -> Unit,
	): ImportedActivityRetentionVisitResult {
		var candidateCount = 0
		var retainedCount = 0
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = ActivityCapturedPortableFormatV1.MAX_ENTRIES - candidateCount
			val pageLimit = minOf(ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES, remaining + 1)
			val page = database.importedActivityDao().recentHistoryCandidatePage(
				limit = pageLimit,
				beforeStartTimeMs = beforeStartTimeMs,
				beforeIdentity = beforeIdentity,
			)
			if (page.isEmpty()) break
			if (!isValidCandidatePage(page, beforeStartTimeMs, beforeIdentity)) {
				return ImportedActivityRetentionVisitResult.Unverifiable(
					ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			if (page.size > remaining) {
				return ImportedActivityRetentionVisitResult.Unverifiable(
					ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
				)
			}
			for (candidate in page) {
				currentCoroutineContext().ensureActive()
				val evaluation = evaluateBatch(listOf(candidate), RETENTION_SCAN_LIMITS).single()
				if (evaluation is ImportedActivityProductEvaluation.Unverifiable) {
					return ImportedActivityRetentionVisitResult.Unverifiable(evaluation.reason)
				}
				if (evaluation is ImportedActivityProductEvaluation.Retained) {
					retainedCount = Math.addExact(retainedCount, 1)
				}
				visit(evaluation)
			}
			candidateCount = Math.addExact(candidateCount, page.size)
			val last = page.last()
			beforeStartTimeMs = last.startTimeMs
			beforeIdentity = last.identity
			if (page.size < pageLimit) break
		}
		return ImportedActivityRetentionVisitResult.Complete(candidateCount, retainedCount)
	}

	suspend fun selectRecentInTransaction(limit: Int): List<ImportedActivityProductEvaluation> {
		require(limit in 1..ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES)
		val candidates = database.importedActivityDao().recentHistoryCandidatePage(
			limit = limit,
			beforeStartTimeMs = null,
			beforeIdentity = null,
		)
		return evaluateCandidates(
			candidates,
			null,
			null,
			ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES,
		)
	}

	/**
	 * Authenticates every imported Activity product candidate without a wall-time overlap filter.
	 *
	 * This is intentionally separate from export selection: zero-length and extreme-boundary
	 * entries remain visible to integrity checks. A ready result is returned only after the source
	 * is exhausted within all cumulative read and materialization budgets.
	 */
	suspend fun selectAllForSharedHistoryInTransaction(
		limits: ImportedActivityProductScanLimits = ImportedActivityProductScanLimits(),
	): ImportedActivityProductScanPage {
		val budget = ImportedActivityProductScanBudget(limits)
		val evaluations = mutableListOf<ImportedActivityProductEvaluation>()
		val seenIdentities = linkedSetOf<String>()
		var candidateCount = 0
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: String? = null
		var beforeCandidateState: String? = null
		return try {
			while (true) {
				currentCoroutineContext().ensureActive()
				val remaining = limits.maximumCandidates - candidateCount
				val pageLimit = minOf(
					ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES,
					remaining + 1,
				)
				budget.consumeQuery()
				val page = database.importedActivityDao().allHistoryCandidatePage(
					limit = pageLimit,
					beforeStartTimeMs = beforeStartTimeMs,
					beforeIdentity = beforeIdentity,
					beforeCandidateState = beforeCandidateState,
				)
				if (page.isEmpty()) break
				if (!isValidAllCandidatePage(
						page,
						beforeStartTimeMs,
						beforeIdentity,
						beforeCandidateState,
						pageLimit,
					)
				) {
					return ImportedActivityProductScanPage.Unverifiable(
						ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
				if (page.map { it.identity }.distinct().size != page.size ||
					page.any { it.identity in seenIdentities }
				) {
					return ImportedActivityProductScanPage.Unverifiable(
						ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT,
					)
				}
				if (page.size > remaining) {
					return ImportedActivityProductScanPage.Unverifiable(
						ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
					)
				}
				seenIdentities += page.map(ImportedActivityHistoryCandidate::identity)
				budget.consumeCandidates(page)
				for (batch in page.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE)) {
					currentCoroutineContext().ensureActive()
					val batchEvaluations = evaluateBatch(batch, null, budget)
					evaluations += batchEvaluations
				}
				candidateCount = Math.addExact(candidateCount, page.size)
				val last = page.last()
				beforeStartTimeMs = last.startTimeMs
				beforeIdentity = last.identity
				beforeCandidateState = last.candidateState
				if (page.size < pageLimit) break
			}
			ImportedActivityProductScanPage.Ready(evaluations)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: ImportedActivityProductScanBudgetExceeded) {
			ImportedActivityProductScanPage.Unverifiable(
				ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
			)
		} catch (_: ArithmeticException) {
			ImportedActivityProductScanPage.Unverifiable(
				ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
			)
		}
	}

	suspend fun selectOrderedRecentPageInTransaction(
		limit: Int,
		beforeRecencyStartTimeMs: Long?,
		beforeRecencyMemberIdentity: PortableActivityOpaqueIdentity?,
	): ImportedActivityOrderedProductPage {
		val page = selectOrderedPageInTransaction(limit) {
			database.importedActivityDao().orderedRecentCandidatePage(
				limit = limit + 1,
				beforeRecencyStartTimeMs = beforeRecencyStartTimeMs,
				beforeRecencyMemberIdentity = beforeRecencyMemberIdentity?.value,
			)
		}
		return if (page is ImportedActivityOrderedProductPage.Ready &&
			page.evaluations.any { ordered ->
				(ordered.evaluation as? ImportedActivityProductEvaluation.Retained)
					?.hasTemporalAuthority == false
			}
		) {
			ImportedActivityOrderedProductPage.Unverifiable(
				ImportedActivityProductFailure.TEMPORAL_AUTHORITY_UNAVAILABLE,
			)
		} else {
			page
		}
	}

	suspend fun selectOrderedRangePageInTransaction(
		fromInclusiveMs: Long,
		toExclusiveMs: Long,
		limit: Int,
		beforeRecencyStartTimeMs: Long?,
		beforeRecencyMemberIdentity: PortableActivityOpaqueIdentity?,
	): ImportedActivityOrderedProductPage {
		require(fromInclusiveMs >= 0L && toExclusiveMs > fromInclusiveMs)
		return selectOrderedPageInTransaction(limit) {
			database.importedActivityDao().orderedRangeCandidatePage(
				fromInclusiveMs = fromInclusiveMs,
				toExclusiveMs = toExclusiveMs,
				limit = limit + 1,
				beforeRecencyStartTimeMs = beforeRecencyStartTimeMs,
				beforeRecencyMemberIdentity = beforeRecencyMemberIdentity?.value,
			)
		}
	}

	suspend fun productRevisionSnapshotInTransaction(): ImportedActivityProductRevisionSnapshot =
		database.importedActivityDao().productRevisionSnapshot()

	private suspend fun selectOrderedPageInTransaction(
		limit: Int,
		load: suspend () -> List<ImportedActivityOrderedHistoryCandidate>,
	): ImportedActivityOrderedProductPage {
		require(limit in 1..ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES)
		val loaded = load()
		if (loaded.size > limit + 1 || !isValidOrderedCandidatePage(loaded)) {
			return ImportedActivityOrderedProductPage.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val selected = loaded.take(limit)
		val evaluations = selected.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE)
			.flatMap { batch ->
				evaluateBatch(batch.map(ImportedActivityOrderedHistoryCandidate::toHistoryCandidate), null)
			}
		if (evaluations.size != selected.size || evaluations.indices.any { index ->
			evaluations[index].candidate.identity != selected[index].identity
		}) {
			return ImportedActivityOrderedProductPage.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return ImportedActivityOrderedProductPage.Ready(
			evaluations = evaluations.indices.map { index ->
				ImportedActivityOrderedProductEvaluation(
					evaluation = evaluations[index],
					recencyStartTimeMs = requireNotNull(selected[index].recencyStartTimeMs),
					recencyMemberIdentity = PortableActivityOpaqueIdentity(
						requireNotNull(selected[index].recencyMemberIdentity),
					),
				)
			},
			hasMore = loaded.size > limit,
		)
	}

	private fun isValidOrderedCandidatePage(
		candidates: List<ImportedActivityOrderedHistoryCandidate>,
	): Boolean = candidates.all { candidate ->
		candidate.importRevision > 0L &&
			candidate.startTimeMs >= 0L &&
			candidate.endTimeMs >= candidate.startTimeMs &&
			candidate.receivedAtMs >= 0L &&
			candidate.recencyStartTimeMs != null &&
			candidate.recencyStartTimeMs in candidate.startTimeMs..candidate.endTimeMs &&
			candidate.recencyMemberIdentity != null &&
			candidate.candidateState in setOf(
				IMPORTED_ACTIVITY_CANDIDATE_LIVE,
				IMPORTED_ACTIVITY_CANDIDATE_RETAINED,
			) &&
			when (candidate.candidateState) {
				IMPORTED_ACTIVITY_CANDIDATE_LIVE -> candidate.temporalAuthorityState == null
				IMPORTED_ACTIVITY_CANDIDATE_RETAINED -> when (candidate.temporalAuthorityState) {
					ImportedActivityRetentionReceiptEntity.TEMPORAL_AUTHORITY_AVAILABLE -> true
					ImportedActivityRetentionReceiptEntity.TEMPORAL_AUTHORITY_UNAVAILABLE ->
						candidate.recencyStartTimeMs == candidate.startTimeMs &&
							candidate.recencyMemberIdentity == candidate.identity
					else -> false
				}
				else -> false
			} &&
			runCatching {
				PortableActivityOpaqueIdentity(candidate.identity)
				PortableActivityOpaqueIdentity(candidate.recencyMemberIdentity)
			}.isSuccess
	} && candidates.map(ImportedActivityOrderedHistoryCandidate::identity).distinct().size ==
		candidates.size &&
		candidates.zipWithNext().all { (left, right) ->
			requireNotNull(left.recencyStartTimeMs) > requireNotNull(right.recencyStartTimeMs) ||
				left.recencyStartTimeMs == right.recencyStartTimeMs &&
				requireNotNull(left.recencyMemberIdentity) >
				requireNotNull(right.recencyMemberIdentity)
		}

	suspend fun selectForExportInTransaction(
		request: ExportPortableCapturedActivityRequest,
	): List<ImportedActivityProductEvaluation> {
		val candidates = mutableListOf<ImportedActivityHistoryCandidate>()
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = ActivityCapturedPortableFormatV1.MAX_ENTRIES - candidates.size
			val pageLimit = minOf(ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES, remaining + 1)
			val page = database.importedActivityDao().historyCandidatePageInRange(
				fromInclusiveMs = request.fromInclusiveMs,
				toExclusiveMs = request.toExclusiveMs,
				limit = pageLimit,
				beforeStartTimeMs = beforeStartTimeMs,
				beforeIdentity = beforeIdentity,
			)
			if (page.isEmpty()) break
			if (!isValidCandidatePage(page, beforeStartTimeMs, beforeIdentity)) {
				return page.take(1).unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
			}
			if (page.size > remaining) {
				return page.take(1).unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
			}
			candidates += page
			val last = page.last()
			beforeStartTimeMs = last.startTimeMs
			beforeIdentity = last.identity
			if (page.size < pageLimit) break
		}
		return evaluateCandidates(candidates, null, null, ActivityCapturedPortableFormatV1.MAX_ENTRIES)
	}

	private suspend fun evaluateCandidates(
		candidates: List<ImportedActivityHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		maximumSize: Int,
	): List<ImportedActivityProductEvaluation> {
		if (!isValidCandidatePage(candidates, beforeStartTimeMs, beforeIdentity, maximumSize)) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return candidates.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE).flatMap { batch ->
			evaluateBatch(batch, null)
		}
	}

	@Suppress("LongMethod")
	private suspend fun evaluateBatch(
		candidates: List<ImportedActivityHistoryCandidate>,
		retentionLimits: ImportedActivityRetentionLimits?,
		scanBudget: ImportedActivityProductScanBudget? = null,
	): List<ImportedActivityProductEvaluation> {
		if (candidates.isEmpty()) return emptyList()
		scanBudget?.consumeQuery()
		val state = database.sourceEvidenceStateDao().get()
			?: return candidates.unverifiable(ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch < 0L || state.retainedFromMs?.let { it < 0L } == true) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val dao = database.importedActivityDao()
		val candidateIdentities = candidates.map(ImportedActivityHistoryCandidate::identity)
		val liveCandidates = candidates.filter {
			it.candidateState == IMPORTED_ACTIVITY_CANDIDATE_LIVE
		}
		val retainedCandidates = candidates.filter {
			it.candidateState == IMPORTED_ACTIVITY_CANDIDATE_RETAINED
		}
		val preflight = scanBudget?.let { budget ->
			budget.consumeQuery()
			val value = dao.productBatchPreflight(
				identities = candidateIdentities,
				activitySourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				sessionCapturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				logicalRunScopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			)
			if (value.latestEntryCount != liveCandidates.size.toLong() ||
				value.retentionReceiptCount != retainedCandidates.size.toLong()
			) {
				return candidates.unverifiable(
					ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT,
				)
			}
			if (value.exceedsBatchLimit(liveCandidates.size, retentionLimits)) {
				return candidates.unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
			}
			budget.reserveProductBatch(value, candidates.size)
			value
		}
		val retentionReceipts = try {
			if (preflight == null) {
				dao.retentionReceipts(candidateIdentities)
			} else {
				loadExpectedRows(
					expectedRows = preflight.retentionReceiptCount,
					scanBudget = requireNotNull(scanBudget),
				) { maximumRows ->
					dao.retentionReceiptsForHistory(candidateIdentities, maximumRows)
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedActivityProductScanBudgetExceeded) {
			throw failure
		} catch (_: ImportedActivityProductPreflightMismatch) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		} catch (_: RuntimeException) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (retentionReceipts.distinctBy { it.entryIdentity }.size != retentionReceipts.size) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val retentionByIdentity = retentionReceipts.associateBy { it.entryIdentity }
		if (candidates.any { candidate ->
			(candidate.candidateState == IMPORTED_ACTIVITY_CANDIDATE_RETAINED) !=
				(candidate.identity in retentionByIdentity)
		}) return candidates.unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
		val expectedRetainedMarkerCount = retentionReceipts.sumOf { it.protectedIdentityCount }
		val retainedMarkers = try {
			if (retentionReceipts.isEmpty()) {
				emptyList()
			} else {
				scanBudget?.consumeQuery()
				dao.retainedIdentitiesForEntries(
					retentionReceipts.map { it.entryIdentity },
					expectedRetainedMarkerCount + 1,
				)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedActivityProductScanBudgetExceeded) {
			throw failure
		} catch (_: RuntimeException) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (retainedMarkers.size != expectedRetainedMarkerCount) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (preflight != null &&
			retainedMarkers.size.toLong() != preflight.retainedIdentityCount
		) return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		val retentionFailure = try {
			database.authenticateImportedActivityRetentionBatch(
				state,
				retentionReceipts,
				scanBudget = scanBudget,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedActivityProductScanBudgetExceeded) {
			throw failure
		} catch (_: RuntimeException) {
			ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE
		}
		when (retentionFailure) {
			ImportedActivityRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE ->
				return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
			ImportedActivityRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT ->
				return candidates.unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
			ImportedActivityRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
				return candidates.unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
			null -> Unit
		}
		val retainedMarkersByEntry = retainedMarkers.groupBy(ImportedActivityRetainedIdentityEntity::entryIdentity)
		val retainedEvaluations = retentionReceipts.associate { receipt ->
			val temporalAuthorityAvailable = receipt.temporalAuthorityState ==
				ImportedActivityRetentionReceiptEntity.TEMPORAL_AUTHORITY_AVAILABLE
			receipt.entryIdentity to ImportedActivityProductEvaluation.Retained(
				candidate = receipt.toHistoryCandidate(),
				retainedFromMs = receipt.retainedFromMs,
				retainedAtMs = receipt.retainedAtMs,
				latestMemberStartTimeMs = receipt.latestMemberStartTimeMs,
				latestMemberIdentity = receipt.latestMemberIdentity?.let(
					::PortableActivityOpaqueIdentity,
				),
				structuralZoneRanges = receipt.structuralZoneRanges(),
				structuralZoneCoverageComplete = receipt.structuralZoneCoverageComplete,
				hasTemporalAuthority = temporalAuthorityAvailable,
				protectedIdentities = retainedMarkersByEntry[receipt.entryIdentity].orEmpty()
					.map(ImportedActivityRetainedIdentityEntity::toProductIdentity),
			)
		}
		if (liveCandidates.isEmpty()) {
			return candidates.map { requireNotNull(retainedEvaluations[it.identity]) }
		}
		val identities = liveCandidates.map(ImportedActivityHistoryCandidate::identity)
		val loaded = try {
			if (preflight != null) {
				val budget = requireNotNull(scanBudget)
				ImportedActivityProductBatch(
					headers = loadExpectedRows(preflight.entryRevisionCount, budget) {
						dao.entryRevisionsForBoundedHistory(identities, it)
					},
					receipts = loadExpectedRows(preflight.importReceiptCount, budget) {
						dao.receiptsForBoundedHistory(identities, it)
					},
					runs = loadExpectedRows(preflight.runCount, budget) {
						dao.runsForBoundedHistory(identities, it)
					},
					zoneEpochs = loadExpectedRows(preflight.zoneEpochCount, budget) {
						dao.zoneEpochsForBoundedHistory(identities, it)
					},
					windows = loadExpectedRows(preflight.windowCount, budget) {
						dao.windowsForBoundedHistory(identities, it)
					},
					fragments = loadExpectedRows(preflight.fragmentCount, budget) {
						dao.fragmentsForBoundedHistory(identities, it)
					},
				)
			} else if (retentionLimits == null) {
				ImportedActivityProductBatch(
					headers = dao.entryRevisionsForHistory(identities),
					receipts = dao.receiptsForHistory(identities),
					runs = dao.runsForHistory(identities),
					zoneEpochs = dao.zoneEpochsForHistory(identities),
					windows = dao.windowsForHistory(identities),
					fragments = dao.fragmentsForHistory(identities),
				)
			} else {
				ImportedActivityProductBatch(
					headers = dao.entryRevisionsForBoundedHistory(
						identities,
						retentionLimits.maximumRevisions,
					),
					receipts = dao.receiptsForHistory(identities),
					runs = dao.runsForBoundedHistory(identities, retentionLimits.maximumRunRows),
					zoneEpochs = dao.zoneEpochsForBoundedHistory(identities, retentionLimits.maximumZoneRows),
					windows = dao.windowsForBoundedHistory(identities, retentionLimits.maximumWindowRows),
					fragments = dao.fragmentsForBoundedHistory(
						identities,
						retentionLimits.maximumFragmentRows,
					),
				)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedActivityProductScanBudgetExceeded) {
			throw failure
		} catch (_: ImportedActivityProductPreflightMismatch) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		} catch (_: RuntimeException) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (loaded.exceedsBatchLimit(identities.size, retentionLimits)) {
			return candidates.unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
		}
		if (!loaded.belongsOnlyTo(identities)) {
			return candidates.unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
		}

		val entryDeletions: List<ImportedActivityEntryDeletionEntity>
		val entryDeletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>
		val runIdentities = (
			loaded.runs.map(ImportedActivityRunEntity::identity) +
				retainedMarkers.filter {
					it.identityKind == ImportedActivityRetainedIdentityEntity.RUN
				}.map(ImportedActivityRetainedIdentityEntity::protectedIdentity)
			).distinct()
		val deletionScopes = (
			loaded.runs.map(ImportedActivityRunEntity::deletionScopeDigest) +
				retainedMarkers.filter {
					it.identityKind == ImportedActivityRetainedIdentityEntity.DELETION_SCOPE
				}.map(ImportedActivityRetainedIdentityEntity::protectedIdentity)
			).distinct()
		val runDeletions: List<ImportedActivityDeletionGenerationEntity>
		val sourceDeletions: List<SourceDeletionFenceEntity>
		try {
			if (preflight == null) {
				entryDeletions = dao.entryDeletionsForHistory(candidateIdentities)
				entryDeletionReceipts = dao.entryDeletionReceipts(candidateIdentities)
				runDeletions = dao.deletionGenerationsForHistory(runIdentities)
				sourceDeletions = deletionScopes.chunked(SQLITE_BIND_BATCH).flatMap { scopes ->
					database.trackingHistoryReadDao().deletionFences(
						sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
						purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
						scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
						scopeIdentityDigests = scopes,
					)
				}
			} else {
				val budget = requireNotNull(scanBudget)
				entryDeletions = loadExpectedRows(preflight.entryDeletionCount, budget) {
					dao.entryDeletionsForBoundedHistory(candidateIdentities, it)
				}
				entryDeletionReceipts = loadExpectedRows(
					preflight.entryDeletionReceiptCount,
					budget,
				) {
					dao.entryDeletionReceiptsForBoundedHistory(candidateIdentities, it)
				}
				runDeletions = loadExpectedChunkedRows(
					identities = runIdentities,
					expectedRows = preflight.runDeletionCount,
					scanBudget = budget,
				) { batch, limit ->
					dao.boundedDeletionGenerations(batch, limit)
				}
				sourceDeletions = loadExpectedChunkedRows(
					identities = deletionScopes,
					expectedRows = preflight.sourceFenceCount,
					scanBudget = budget,
				) { batch, limit ->
					dao.boundedActivitySourceFences(
						scopeDigests = batch,
						activitySourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
						sessionCapturePurpose = SessionManifestPurposeCode.SESSION_CAPTURE,
						logicalRunScopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
						limit = limit,
					)
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedActivityProductScanBudgetExceeded) {
			throw failure
		} catch (_: ImportedActivityProductPreflightMismatch) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		} catch (_: RuntimeException) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (entryDeletions.distinctBy(ImportedActivityEntryDeletionEntity::entryIdentity).size !=
			entryDeletions.size ||
			entryDeletionReceipts.distinctBy {
				it.entryIdentity
			}.size != entryDeletionReceipts.size ||
			runDeletions.distinctBy(ImportedActivityDeletionGenerationEntity::runIdentity).size !=
			runDeletions.size ||
			entryDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
			entryDeletionReceipts.any { receipt ->
				val deletion = entryDeletions.singleOrNull {
					it.entryIdentity == receipt.entryIdentity
				}
				deletion == null ||
					receipt.collectedDataEpoch != deletion.collectedDataEpoch ||
					receipt.deletedImportRevision != deletion.deletedImportRevision ||
					receipt.deletedAtMs != deletion.deletedAtMs
			} ||
			runDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
			sourceDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch }
		) return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		val hasExactOwnership = try {
			hasExactImportedIdentityOwnership(
				batch = loaded,
				retentionReceipts = retentionReceipts,
				retainedMarkers = retainedMarkers,
				entryDeletions = entryDeletions,
				entryDeletionReceipts = entryDeletionReceipts,
				runDeletions = runDeletions,
				sourceFences = sourceDeletions,
				scanBudget = scanBudget,
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (failure: ImportedActivityProductScanBudgetExceeded) {
			throw failure
		} catch (_: RuntimeException) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (!hasExactOwnership) {
			return candidates.unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
		}

		val headersByIdentity = loaded.headers.groupBy(ImportedActivityEntryRevisionEntity::identity)
		val receiptsByIdentity = loaded.receipts.groupBy(ImportedActivityReceiptEntity::entryIdentity)
		val runsByIdentity = loaded.runs.groupBy(ImportedActivityRunEntity::entryIdentity)
		val zonesByIdentity = loaded.zoneEpochs.groupBy(ImportedActivityZoneEpochEntity::entryIdentity)
		val windowsByIdentity = loaded.windows.groupBy(ImportedActivityWindowEntity::entryIdentity)
		val fragmentsByIdentity = loaded.fragments.groupBy(ImportedActivityFragmentEntity::entryIdentity)
		val entryDeletionsByIdentity = entryDeletions.associateBy(ImportedActivityEntryDeletionEntity::entryIdentity)
		val runDeletionsByIdentity = runDeletions.associateBy(ImportedActivityDeletionGenerationEntity::runIdentity)
		val sourceDeletedScopes = sourceDeletions.mapTo(hashSetOf(), SourceDeletionFenceEntity::scopeIdentityDigest)
		val liveEvaluations = liveCandidates.map { candidate ->
			currentCoroutineContext().ensureActive()
			val headers = headersByIdentity[candidate.identity].orEmpty()
			if (headers.any { it.collectedDataEpoch != state.collectedDataEpoch }) {
				return@map candidate.unverifiable(ImportedActivityProductFailure.STALE_COLLECTED_DATA_EPOCH)
			}
			try {
				val lineage = ImportedActivityLineageAuthenticator.authenticate(
					identity = candidate.identity,
					expectedCollectedDataEpoch = state.collectedDataEpoch,
					headers = headers,
					receipts = receiptsByIdentity[candidate.identity].orEmpty(),
					runs = runsByIdentity[candidate.identity].orEmpty(),
					zoneEpochs = zonesByIdentity[candidate.identity].orEmpty(),
					windows = windowsByIdentity[candidate.identity].orEmpty(),
					fragments = fragmentsByIdentity[candidate.identity].orEmpty(),
				)
				val latest = lineage.revisions.lastOrNull()
					?: return@map candidate.unverifiable(
						ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
					)
				if (!candidate.exactlyMatches(latest)) {
					return@map candidate.unverifiable(
						ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
				val entryDeletion = entryDeletionsByIdentity[candidate.identity]
				if (entryDeletion != null && entryDeletion.deletedImportRevision != latest.header.importRevision) {
					return@map candidate.unverifiable(
						ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
				val latestRuns = runsByIdentity[candidate.identity].orEmpty()
					.filter { it.entryImportRevision == latest.header.importRevision }
				val deletedRunIdentities = latestRuns.filterTo(linkedSetOf()) { run ->
					run.identity in runDeletionsByIdentity || run.deletionScopeDigest in sourceDeletedScopes
				}.mapTo(linkedSetOf(), ImportedActivityRunEntity::identity)
				ImportedActivityProductEvaluation.Readable(
					candidate = candidate,
					entry = latest.entry,
					entryDeleted = entryDeletion != null,
					deletedRunIdentities = deletedRunIdentities,
					retainedFromMs = state.retainedFromMs,
					retentionLimited = state.retainedFromMs?.let { retainedFromMs ->
						lineage.revisions.any { it.entry.crossesRetentionBoundary(retainedFromMs) }
					} == true,
				)
			} catch (failure: ImportedActivityLineageFailure) {
				candidate.unverifiable(failure.reason.toProductFailure())
			} catch (_: IllegalArgumentException) {
				candidate.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
			} catch (_: ArithmeticException) {
				candidate.unverifiable(ImportedActivityProductFailure.VALUE_OVERFLOW)
			}
		}.associateBy { it.candidate.identity }
		return candidates.map { candidate ->
			retainedEvaluations[candidate.identity] ?: requireNotNull(liveEvaluations[candidate.identity])
		}
	}

	private suspend fun hasExactImportedIdentityOwnership(
		batch: ImportedActivityProductBatch,
		retentionReceipts: List<ImportedActivityRetentionReceiptEntity>,
		retainedMarkers: List<ImportedActivityRetainedIdentityEntity>,
		entryDeletions: List<ImportedActivityEntryDeletionEntity>,
		entryDeletionReceipts: List<ImportedActivityEntryDeletionReceiptEntity>,
		runDeletions: List<ImportedActivityDeletionGenerationEntity>,
		sourceFences: List<SourceDeletionFenceEntity>,
		scanBudget: ImportedActivityProductScanBudget?,
	): Boolean {
		val expected = linkedMapOf<ImportedActivityOwnerAuditKey, Long>()
		fun include(
			ownerKind: String,
			protectedIdentity: String,
			sourceKind: Int? = null,
			purpose: String? = null,
			scopeKind: String? = null,
		) {
			val key = ImportedActivityOwnerAuditKey(
				ownerKind,
				protectedIdentity,
				sourceKind,
				purpose,
				scopeKind,
			)
			expected[key] = Math.addExact(expected[key] ?: 0L, 1L)
		}
		batch.headers.forEach { include("ENTRY_IDENTITY", it.identity) }
		batch.receipts.forEach { include("RECEIPT_ENTRY_OWNER", it.entryIdentity) }
		retentionReceipts.forEach {
			include("RETENTION_RECEIPT_ENTRY_OWNER", it.entryIdentity)
		}
		retainedMarkers.forEach {
			include("RETAINED_IDENTITY", it.protectedIdentity)
			include("RETAINED_IDENTITY_ENTRY_OWNER", it.entryIdentity)
		}
		batch.runs.forEach {
			include("RUN_ENTRY_OWNER", it.entryIdentity)
			include("RUN_IDENTITY", it.identity)
			include("RUN_SCOPE_OWNER", it.deletionScopeDigest)
		}
		batch.zoneEpochs.forEach {
			include("ZONE_ENTRY_OWNER", it.entryIdentity)
			include("ZONE_RUN_OWNER", it.runIdentity)
		}
		batch.windows.forEach {
			include("WINDOW_ENTRY_OWNER", it.entryIdentity)
			include("WINDOW_RUN_OWNER", it.runIdentity)
			include("WINDOW_IDENTITY", it.identity)
		}
		batch.fragments.forEach {
			include("FRAGMENT_ENTRY_OWNER", it.entryIdentity)
			include("FRAGMENT_RUN_OWNER", it.runIdentity)
			include("FRAGMENT_WINDOW_OWNER", it.windowIdentity)
		}
		entryDeletions.forEach { include("ENTRY_DELETION", it.entryIdentity) }
		entryDeletionReceipts.forEach {
			include("ENTRY_DELETION_RECEIPT", it.entryIdentity)
		}
		runDeletions.forEach { include("RUN_DELETION", it.runIdentity) }
		sourceFences.forEach { fence ->
			include(
				ownerKind = "SOURCE_DELETION_SCOPE",
				protectedIdentity = fence.scopeIdentityDigest,
				sourceKind = fence.sourceKind,
				purpose = fence.purpose,
				scopeKind = fence.scopeKind,
			)
		}
		val protectedIdentities = expected.keys.mapTo(linkedSetOf()) { it.protectedIdentity }
		if (protectedIdentities.isEmpty()) return false
		for (identityBatch in protectedIdentities.chunked(SQLITE_BIND_BATCH)) {
			currentCoroutineContext().ensureActive()
			val expectedBatch = expected.filterKeys { it.protectedIdentity in identityBatch }
			val resultCount = if (scanBudget == null) {
				expectedBatch.size
			} else {
				scanBudget.consumeQuery()
				val ownerPreflight = database.importedActivityDao()
					.protectedIdentityOwnerCountPreflight(identityBatch)
				scanBudget.reserveOwnerAudit(
					ownerPreflight.resultCount,
					ownerPreflight.resultTextBytes,
				)
				if (ownerPreflight.resultCount != expectedBatch.size.toLong()) return false
				ownerPreflight.resultCount.toExpectedRowCount()
			}
			scanBudget?.consumeQuery()
			val actualRows = database.importedActivityDao().protectedIdentityOwnerCounts(
				identityBatch,
				Math.addExact(resultCount, 1),
			)
			if (actualRows.size != resultCount) return false
			val actual = actualRows.associate { row ->
				row.toOwnerAuditKey() to row.ownerCount
			}
			if (actual != expectedBatch) return false
		}
		return true
	}

	private fun isValidCandidatePage(
		candidates: List<ImportedActivityHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		maximumSize: Int = ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES,
	): Boolean {
		if ((beforeStartTimeMs == null) != (beforeIdentity == null)) return false
		if (candidates.size > maximumSize) return false
		if (candidates.map { it.identity }.distinct().size != candidates.size) return false
		if (candidates.any {
			it.identity.isBlank() || it.importRevision <= 0L || it.startTimeMs < 0L ||
				it.endTimeMs < it.startTimeMs || it.receivedAtMs < 0L ||
				it.candidateState !in setOf(
					IMPORTED_ACTIVITY_CANDIDATE_LIVE,
					IMPORTED_ACTIVITY_CANDIDATE_RETAINED,
				)
		}) return false
		val cursors = buildList {
			if (beforeStartTimeMs != null && beforeIdentity != null) add(beforeStartTimeMs to beforeIdentity)
			addAll(candidates.map { it.startTimeMs to it.identity })
		}
		return cursors.zipWithNext().all { (left, right) ->
			left.first > right.first || left.first == right.first && left.second > right.second
		}
	}

	private fun isValidAllCandidatePage(
		candidates: List<ImportedActivityHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		beforeCandidateState: String?,
		maximumSize: Int,
	): Boolean {
		if ((beforeStartTimeMs == null) != (beforeIdentity == null) ||
			(beforeIdentity == null) != (beforeCandidateState == null)
		) return false
		if (candidates.size > maximumSize || candidates.any {
				it.identity.isBlank() || it.importRevision <= 0L || it.startTimeMs < 0L ||
					it.endTimeMs < it.startTimeMs || it.receivedAtMs < 0L ||
					it.candidateState !in setOf(
						IMPORTED_ACTIVITY_CANDIDATE_LIVE,
						IMPORTED_ACTIVITY_CANDIDATE_RETAINED,
					)
			}
		) return false
		val cursors = buildList {
			if (beforeStartTimeMs != null && beforeIdentity != null && beforeCandidateState != null) {
				add(Triple(beforeStartTimeMs, beforeIdentity, beforeCandidateState))
			}
			addAll(candidates.map { Triple(it.startTimeMs, it.identity, it.candidateState) })
		}
		return cursors.zipWithNext().all { (left, right) ->
			left.first > right.first ||
				left.first == right.first && (
					left.second > right.second ||
						left.second == right.second && left.third < right.third
					)
		}
	}

	private companion object {
		const val SQLITE_BIND_BATCH = 400
		val RETENTION_SCAN_LIMITS = ImportedActivityRetentionLimits(
			maximumEntries = 1,
			maximumRevisions = ImportedActivityDao.MAX_REVISIONS_PER_ENTRY,
			maximumRunRows = ImportedActivityDao.MAX_TOTAL_RUNS_PER_LINEAGE,
			maximumZoneRows = ImportedActivityDao.MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE,
			maximumWindowRows = ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_LINEAGE,
			maximumFragmentRows = ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_LINEAGE,
			maximumProtectedIdentities = ImportedActivityDao.MAX_RETAINED_IDENTITY_ROWS,
		)
	}
}

sealed interface ImportedActivityProductEvaluation {
	val candidate: ImportedActivityHistoryCandidate

	data class Readable(
		override val candidate: ImportedActivityHistoryCandidate,
		val entry: PortableActivityEntryV1,
		val entryDeleted: Boolean,
		val deletedRunIdentities: Set<String>,
		val retainedFromMs: Long?,
		val retentionLimited: Boolean,
	) : ImportedActivityProductEvaluation {
		val isReExportable: Boolean
			get() = !entryDeleted && deletedRunIdentities.isEmpty() && !retentionLimited
	}

	/** Payload-free, authenticated proof that this imported entry was truncated by retention. */
	data class Retained(
		override val candidate: ImportedActivityHistoryCandidate,
		val retainedFromMs: Long,
		val retainedAtMs: Long,
		val latestMemberStartTimeMs: Long?,
		val latestMemberIdentity: PortableActivityOpaqueIdentity?,
		val structuralZoneRanges: List<ImportedActivityRetainedZoneRange>,
		val structuralZoneCoverageComplete: Boolean,
		val hasTemporalAuthority: Boolean,
		val protectedIdentities: List<RetainedImportedActivityIdentity>,
	) : ImportedActivityProductEvaluation {
		init {
			if (hasTemporalAuthority) {
				require(latestMemberStartTimeMs != null &&
					latestMemberStartTimeMs in candidate.startTimeMs..candidate.endTimeMs)
				require(latestMemberIdentity != null)
				require(structuralZoneRanges.isNotEmpty())
			} else {
				require(latestMemberStartTimeMs == null && latestMemberIdentity == null)
				require(structuralZoneRanges.isEmpty() && !structuralZoneCoverageComplete)
			}
			require(protectedIdentities.isNotEmpty())
			require(protectedIdentities.map { it.value }.distinct().size == protectedIdentities.size)
			val entryMarkers = protectedIdentities.filterIsInstance<RetainedImportedActivityIdentity.Entry>()
			require(entryMarkers.singleOrNull()?.identity?.value == candidate.identity)
		}
	}

	data class Unverifiable(
		override val candidate: ImportedActivityHistoryCandidate,
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityProductEvaluation
}

data class ImportedActivityOrderedProductEvaluation(
	val evaluation: ImportedActivityProductEvaluation,
	val recencyStartTimeMs: Long,
	val recencyMemberIdentity: PortableActivityOpaqueIdentity,
)

sealed interface ImportedActivityOrderedProductPage {
	data class Ready(
		val evaluations: List<ImportedActivityOrderedProductEvaluation>,
		val hasMore: Boolean,
	) : ImportedActivityOrderedProductPage

	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityOrderedProductPage
}

data class ImportedActivityProductScanLimits(
	val maximumCandidates: Int = ActivityCapturedPortableFormatV1.MAX_ENTRIES,
	val maximumSourceRows: Long = DEFAULT_MAXIMUM_SOURCE_ROWS,
	val maximumSourceTextBytes: Long = DEFAULT_MAXIMUM_SOURCE_TEXT_BYTES,
	val maximumPortableElements: Long = DEFAULT_MAXIMUM_PORTABLE_ELEMENTS,
	val maximumPublicPayloadElements: Long = DEFAULT_MAXIMUM_PUBLIC_PAYLOAD_ELEMENTS,
	val maximumQueries: Int = DEFAULT_MAXIMUM_QUERIES,
) {
	init {
		require(maximumCandidates in 1..ActivityCapturedPortableFormatV1.MAX_ENTRIES)
		require(maximumSourceRows > 0L)
		require(maximumSourceTextBytes > 0L)
		require(maximumPortableElements > 0L)
		require(maximumPublicPayloadElements > 0L)
		require(maximumQueries > 0)
	}

	private companion object {
		const val DEFAULT_MAXIMUM_SOURCE_ROWS = 1_048_576L
		const val DEFAULT_MAXIMUM_SOURCE_TEXT_BYTES =
			ActivityCapturedPortableFormatV1.MAX_FILE_BYTES
		const val DEFAULT_MAXIMUM_PORTABLE_ELEMENTS = 1_048_576L
		const val DEFAULT_MAXIMUM_PUBLIC_PAYLOAD_ELEMENTS = 524_288L
		const val DEFAULT_MAXIMUM_QUERIES = 32_768
	}
}

sealed interface ImportedActivityProductScanPage {
	data class Ready(
		val evaluations: List<ImportedActivityProductEvaluation>,
	) : ImportedActivityProductScanPage

	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityProductScanPage
}

enum class ImportedActivityProductFailure {
	SOURCE_EVIDENCE_STATE_MISSING,
	STALE_COLLECTED_DATA_EPOCH,
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
	TEMPORAL_AUTHORITY_UNAVAILABLE,
	VALUE_OVERFLOW,
}

private data class ImportedActivityProductBatch(
	val headers: List<ImportedActivityEntryRevisionEntity>,
	val receipts: List<ImportedActivityReceiptEntity>,
	val runs: List<ImportedActivityRunEntity>,
	val zoneEpochs: List<ImportedActivityZoneEpochEntity>,
	val windows: List<ImportedActivityWindowEntity>,
	val fragments: List<ImportedActivityFragmentEntity>,
) {
	fun exceedsBatchLimit(
		identityCount: Int,
		retentionLimits: ImportedActivityRetentionLimits?,
	): Boolean =
		headers.size > identityCount * ImportedActivityDao.MAX_REVISIONS_PER_ENTRY ||
			receipts.size > identityCount * ImportedActivityDao.MAX_RECEIPTS_PER_ENTRY ||
			runs.size > identityCount * ImportedActivityDao.MAX_TOTAL_RUNS_PER_LINEAGE ||
			zoneEpochs.size > identityCount * ImportedActivityDao.MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE ||
			windows.size > identityCount * ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_LINEAGE ||
			fragments.size > identityCount * ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_LINEAGE ||
			retentionLimits?.let { limits ->
				headers.size > limits.maximumRevisions ||
					runs.size > limits.maximumRunRows ||
					zoneEpochs.size > limits.maximumZoneRows ||
					windows.size > limits.maximumWindowRows ||
					fragments.size > limits.maximumFragmentRows
			} == true

	fun belongsOnlyTo(identities: List<String>): Boolean {
		val expected = identities.toHashSet()
		return headers.all { it.identity in expected } && receipts.all { it.entryIdentity in expected } &&
			runs.all { it.entryIdentity in expected } && zoneEpochs.all { it.entryIdentity in expected } &&
			windows.all { it.entryIdentity in expected } && fragments.all { it.entryIdentity in expected }
	}
}

private fun ImportedActivityProductBatchPreflight.exceedsBatchLimit(
	identityCount: Int,
	retentionLimits: ImportedActivityRetentionLimits?,
): Boolean =
	entryRevisionCount > identityCount.toLong() * ImportedActivityDao.MAX_REVISIONS_PER_ENTRY ||
		importReceiptCount > identityCount.toLong() * ImportedActivityDao.MAX_RECEIPTS_PER_ENTRY ||
		runCount > identityCount.toLong() * ImportedActivityDao.MAX_TOTAL_RUNS_PER_LINEAGE ||
		zoneEpochCount > identityCount.toLong() *
		ImportedActivityDao.MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE ||
		windowCount > identityCount.toLong() * ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_LINEAGE ||
		fragmentCount > identityCount.toLong() *
		ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_LINEAGE ||
		retentionLimits?.let { limits ->
			entryRevisionCount > limits.maximumRevisions ||
				runCount > limits.maximumRunRows ||
				zoneEpochCount > limits.maximumZoneRows ||
				windowCount > limits.maximumWindowRows ||
				fragmentCount > limits.maximumFragmentRows
		} == true

private fun ImportedActivityProductBatchPreflight.sourceRowCount(): Long =
	listOf(
		entryRevisionCount,
		importReceiptCount,
		runCount,
		zoneEpochCount,
		windowCount,
		fragmentCount,
		retentionReceiptCount,
		retainedIdentityCount,
		entryDeletionCount,
		entryDeletionReceiptCount,
		runDeletionCount,
		sourceFenceCount,
	).fold(0L) { total, value -> Math.addExact(total, value) }

private fun ImportedActivityProductBatchPreflight.projectedPortableElements(): Long =
	listOf(
		entryRevisionCount,
		runCount,
		zoneEpochCount,
		windowCount,
		fragmentCount,
		retentionReceiptCount,
		retainedIdentityCount,
		retainedStructuralRangeCount,
	).fold(0L) { total, value -> Math.addExact(total, value) }

private fun ImportedActivityProductBatchPreflight.projectedPublicPayloadElements(
	candidateCount: Int,
): Long = listOf(
	candidateCount.toLong(),
	latestRunCount,
	latestZoneEpochCount,
	latestWindowCount,
	latestFragmentCount,
	retainedStructuralRangeCount,
).fold(0L) { total, value -> Math.addExact(total, value) }

private suspend fun <T> loadExpectedRows(
	expectedRows: Long,
	scanBudget: ImportedActivityProductScanBudget,
	load: suspend (maximumRows: Int) -> List<T>,
): List<T> {
	val maximumRows = expectedRows.toExpectedRowCount()
	scanBudget.consumeQuery()
	val loaded = load(maximumRows)
	if (loaded.size.toLong() != expectedRows) {
		throw ImportedActivityProductPreflightMismatch()
	}
	return loaded
}

private suspend fun <T> loadExpectedChunkedRows(
	identities: List<String>,
	expectedRows: Long,
	scanBudget: ImportedActivityProductScanBudget,
	load: suspend (identities: List<String>, limit: Int) -> List<T>,
): List<T> {
	if (identities.isEmpty()) {
		if (expectedRows != 0L) throw ImportedActivityProductPreflightMismatch()
		return emptyList()
	}
	val loaded = mutableListOf<T>()
	var remaining = expectedRows
	for (batch in identities.chunked(SQLITE_BIND_BATCH)) {
		currentCoroutineContext().ensureActive()
		val limit = Math.addExact(remaining.toExpectedRowCount(), 1)
		scanBudget.consumeQuery()
		val rows = load(batch, limit)
		if (rows.size.toLong() > remaining) {
			throw ImportedActivityProductPreflightMismatch()
		}
		loaded += rows
		remaining -= rows.size
	}
	if (remaining != 0L) throw ImportedActivityProductPreflightMismatch()
	return loaded
}

private fun Long.toExpectedRowCount(): Int {
	if (this !in 0L..Int.MAX_VALUE.toLong()) {
		throw ImportedActivityProductScanBudgetExceeded()
	}
	return toInt()
}

internal data class ImportedActivityOwnerAuditKey(
	val ownerKind: String,
	val protectedIdentity: String,
	val sourceKind: Int?,
	val purpose: String?,
	val scopeKind: String?,
)

private fun ImportedActivityProtectedIdentityOwnerCount.toOwnerAuditKey() =
	ImportedActivityOwnerAuditKey(
		ownerKind,
		protectedIdentity,
		sourceKind,
		purpose,
		scopeKind,
	)

private fun utf8Bytes(vararg values: String?): Long = values.fold(0L) { total, value ->
	Math.addExact(
		total,
		value?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L,
	)
}

internal class ImportedActivityProductScanBudget(
	private val limits: ImportedActivityProductScanLimits,
) {
	private var sourceRows = 0L
	private var sourceTextBytes = 0L
	private var portableElements = 0L
	private var publicPayloadElements = 0L
	private var queryCount = 0

	fun consumeCandidates(values: List<ImportedActivityHistoryCandidate>) {
		consumeRows(values.size.toLong())
		val textBytes = values.fold(0L) { total, value ->
			Math.addExact(
				total,
				utf8Bytes(value.identity, value.contentChecksum, value.candidateState),
			)
		}
		consumeTextBytes(
			Math.addExact(
				textBytes,
				Math.multiplyExact(values.size.toLong(), FIXED_ROW_OVERHEAD_BYTES),
			),
		)
	}

	fun reserveProductBatch(
		preflight: ImportedActivityProductBatchPreflight,
		candidateCount: Int,
	) {
		val rows = preflight.sourceRowCount()
		consumeRows(rows)
		consumeTextBytes(
			Math.addExact(
				preflight.sourceTextBytes,
				Math.multiplyExact(rows, FIXED_ROW_OVERHEAD_BYTES),
			),
		)
		val projectedPortable = preflight.projectedPortableElements()
		portableElements = Math.addExact(portableElements, projectedPortable)
		if (portableElements > limits.maximumPortableElements) {
			throw ImportedActivityProductScanBudgetExceeded()
		}
		val projectedPublic = preflight.projectedPublicPayloadElements(candidateCount)
		publicPayloadElements = Math.addExact(publicPayloadElements, projectedPublic)
		if (publicPayloadElements > limits.maximumPublicPayloadElements) {
			throw ImportedActivityProductScanBudgetExceeded()
		}
	}

	fun reserveOwnerAudit(
		resultCount: Long,
		resultTextBytes: Long,
	) {
		consumeRows(resultCount)
		consumeTextBytes(
			Math.addExact(
				resultTextBytes,
				Math.multiplyExact(resultCount, OWNER_AUDIT_FIXED_OVERHEAD_BYTES),
			),
		)
	}

	fun consumeQuery() {
		consumeQueries(1)
	}

	private fun consumeQueries(count: Int) {
		queryCount = Math.addExact(queryCount, count)
		if (queryCount > limits.maximumQueries) {
			throw ImportedActivityProductScanBudgetExceeded()
		}
	}

	private fun consumeRows(count: Long) {
		sourceRows = Math.addExact(sourceRows, count)
		if (sourceRows > limits.maximumSourceRows) {
			throw ImportedActivityProductScanBudgetExceeded()
		}
	}

	private fun consumeTextBytes(count: Long) {
		sourceTextBytes = Math.addExact(sourceTextBytes, count)
		if (sourceTextBytes > limits.maximumSourceTextBytes) {
			throw ImportedActivityProductScanBudgetExceeded()
		}
	}

	private companion object {
		const val FIXED_ROW_OVERHEAD_BYTES = 256L
		const val OWNER_AUDIT_FIXED_OVERHEAD_BYTES = 64L
	}
}

private class ImportedActivityProductScanBudgetExceeded :
	RuntimeException(null, null, false, false)

private class ImportedActivityProductPreflightMismatch :
	RuntimeException(null, null, false, false)

private fun ImportedActivityHistoryCandidate.exactlyMatches(
	latest: AuthenticatedImportedActivityRevision,
): Boolean = identity == latest.header.identity && importRevision == latest.header.importRevision &&
	contentChecksum == latest.header.contentChecksum && startTimeMs == latest.header.startTimeMs &&
	endTimeMs == latest.header.endTimeMs && receivedAtMs == latest.header.receivedAtMs

private fun ImportedActivityRetentionReceiptEntity.toHistoryCandidate() =
	ImportedActivityHistoryCandidate(
		identity = entryIdentity,
		importRevision = latestImportRevision,
		contentChecksum = latestContentChecksum,
		startTimeMs = startTimeMs,
		endTimeMs = endTimeMs,
		receivedAtMs = receivedAtMs,
		candidateState = IMPORTED_ACTIVITY_CANDIDATE_RETAINED,
	)

private fun ImportedActivityRetainedIdentityEntity.toProductIdentity(): RetainedImportedActivityIdentity =
	when (identityKind) {
		ImportedActivityRetainedIdentityEntity.ENTRY -> RetainedImportedActivityIdentity.Entry(
			PortableActivityOpaqueIdentity(protectedIdentity),
		)
		ImportedActivityRetainedIdentityEntity.RUN -> RetainedImportedActivityIdentity.Run(
			PortableActivityOpaqueIdentity(protectedIdentity),
		)
		ImportedActivityRetainedIdentityEntity.WINDOW -> RetainedImportedActivityIdentity.Window(
			PortableActivityOpaqueIdentity(protectedIdentity),
		)
		ImportedActivityRetainedIdentityEntity.DELETION_SCOPE ->
			RetainedImportedActivityIdentity.DeletionScope(
				PortableActivityDeletionScopeDigest(protectedIdentity),
			)
		else -> error("Unknown imported Activity retained identity kind")
	}

internal sealed interface ImportedActivityRetentionVisitResult {
	data class Complete(
		val candidateCount: Int,
		val retainedCount: Int,
	) : ImportedActivityRetentionVisitResult

	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityRetentionVisitResult
}

private fun ImportedActivityHistoryCandidate.unverifiable(reason: ImportedActivityProductFailure) =
	ImportedActivityProductEvaluation.Unverifiable(this, reason)

private fun List<ImportedActivityHistoryCandidate>.unverifiable(reason: ImportedActivityProductFailure) =
	map { it.unverifiable(reason) }

private fun ImportedActivityLineageAuthenticator.Reason.toProductFailure(): ImportedActivityProductFailure =
	when (this) {
		ImportedActivityLineageAuthenticator.Reason.DEPENDENCY_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.RUN_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.WINDOW_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.FRAGMENT_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.ZONE_EPOCH_OVERFLOW,
		ImportedActivityLineageAuthenticator.Reason.REVISION_OVERFLOW,
		-> ImportedActivityProductFailure.DEPENDENCY_OVERFLOW
		ImportedActivityLineageAuthenticator.Reason.STORED_EVIDENCE_UNVERIFIABLE ->
			ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE
	}
