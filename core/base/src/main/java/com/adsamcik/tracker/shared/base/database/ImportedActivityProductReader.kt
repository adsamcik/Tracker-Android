package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityOrderedHistoryCandidate
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProductBatchPreflight
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityProductRevisionSnapshot
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedZoneRange
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
			return live.unverifiableImportedActivityProduct(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
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
			if (page.isEmpty()) {
				break
			}
			if (!isValidActivityCandidatePage(page, beforeStartTimeMs, beforeIdentity)) {
				return ImportedActivityRetentionVisitResult.Unverifiable(
					ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			if (page.size > remaining) {
				return ImportedActivityRetentionVisitResult.Unverifiable(
					ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
				)
			}
			when (val progress = visitRetentionPage(page, retainedCount, visit)) {
				is ImportedActivityRetentionVisitResult.Unverifiable -> return progress
				is ImportedActivityRetentionVisitResult.Complete -> retainedCount = progress.retainedCount
			}
			candidateCount = Math.addExact(candidateCount, page.size)
			val last = page.last()
			beforeStartTimeMs = last.startTimeMs
			beforeIdentity = last.identity
			if (page.size < pageLimit) {
				break
			}
		}
		return ImportedActivityRetentionVisitResult.Complete(candidateCount, retainedCount)
	}

	private suspend fun visitRetentionPage(
		page: List<ImportedActivityHistoryCandidate>,
		initialRetainedCount: Int,
		visit: suspend (ImportedActivityProductEvaluation) -> Unit,
	): ImportedActivityRetentionVisitResult {
		var retainedCount = initialRetainedCount
		for (candidate in page) {
			currentCoroutineContext().ensureActive()
			val evaluation = database.evaluateImportedActivityProductBatch(listOf(candidate), RETENTION_SCAN_LIMITS).single()
			if (evaluation is ImportedActivityProductEvaluation.Unverifiable) {
				return ImportedActivityRetentionVisitResult.Unverifiable(evaluation.reason)
			}
			if (evaluation is ImportedActivityProductEvaluation.Retained) {
				retainedCount = Math.addExact(retainedCount, 1)
			}
			visit(evaluation)
		}
		return ImportedActivityRetentionVisitResult.Complete(page.size, retainedCount)
	}

	/**
	 * Evaluates a bounded newest-member ordered imported Activity selection inside the caller's Room
	 * snapshot.
	 */
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
		return try {
			readAllImportedActivityProductPages(limits, budget, evaluations, seenIdentities)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: ImportedActivityProductScanBudgetExceeded) {
			ImportedActivityProductScanPage.Unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
		} catch (_: ArithmeticException) {
			ImportedActivityProductScanPage.Unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
		}
	}
	private suspend fun readAllImportedActivityProductPages(
		limits: ImportedActivityProductScanLimits,
		budget: ImportedActivityProductScanBudget,
		evaluations: MutableList<ImportedActivityProductEvaluation>,
		seenIdentities: MutableSet<String>,
	): ImportedActivityProductScanPage {
		var candidateCount = 0
		var previous: ImportedActivityHistoryCandidate? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = limits.maximumCandidates - candidateCount
			val pageLimit = minOf(ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES, remaining + 1)
			budget.consumeQuery()
			val page = database.importedActivityDao().allHistoryCandidatePage(
				limit = pageLimit,
				beforeStartTimeMs = previous?.startTimeMs,
				beforeIdentity = previous?.identity,
				beforeCandidateState = previous?.candidateState,
			)
			if (page.isEmpty()) {
				break
			}
			when (val evaluated = evaluateSharedHistoryPage(page, previous, remaining, pageLimit, seenIdentities, budget)) {
				is ImportedActivityProductScanPage.Unverifiable -> return evaluated
				is ImportedActivityProductScanPage.Ready -> evaluations += evaluated.evaluations
			}
			candidateCount = Math.addExact(candidateCount, page.size)
			previous = page.last()
			if (page.size < pageLimit) {
				break
			}
		}
		return ImportedActivityProductScanPage.Ready(evaluations)
	}


	private suspend fun evaluateSharedHistoryPage(
		page: List<ImportedActivityHistoryCandidate>,
		previous: ImportedActivityHistoryCandidate?,
		remaining: Int,
		pageLimit: Int,
		seenIdentities: MutableSet<String>,
		budget: ImportedActivityProductScanBudget,
	): ImportedActivityProductScanPage {
		if (!isValidAllActivityCandidatePage(
				page, previous?.startTimeMs, previous?.identity, previous?.candidateState, pageLimit,
			)
		) {
			return ImportedActivityProductScanPage.Unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (page.map { it.identity }.distinct().size != page.size || page.any { it.identity in seenIdentities }) {
			return ImportedActivityProductScanPage.Unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
		}
		if (page.size > remaining) {
			return ImportedActivityProductScanPage.Unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
		}
		seenIdentities += page.map(ImportedActivityHistoryCandidate::identity)
		budget.consumeCandidates(page)
		val evaluations = mutableListOf<ImportedActivityProductEvaluation>()
		for (batch in page.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE)) {
			currentCoroutineContext().ensureActive()
			evaluations += database.evaluateImportedActivityProductBatch(batch, null, budget)
		}
		return ImportedActivityProductScanPage.Ready(evaluations)
	}

	/**
	 * Reads one bounded imported Activity page after the optional paired original
	 * member-time/identity cursor.
	 */
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

	/**
	 * Reads a bounded imported Activity range page in original-member recency order within the
	 * caller's transaction.
	 */
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

	/**
	 * Reads current imported Activity revision/deletion/retention markers from the caller's
	 * transaction for stability comparison.
	 */
	suspend fun productRevisionSnapshotInTransaction(): ImportedActivityProductRevisionSnapshot =
		database.importedActivityDao().productRevisionSnapshot()

	private suspend fun selectOrderedPageInTransaction(
		limit: Int,
		load: suspend () -> List<ImportedActivityOrderedHistoryCandidate>,
	): ImportedActivityOrderedProductPage {
		require(limit in 1..ImportedActivityDao.MAX_HISTORY_ENTRY_CANDIDATES)
		val loaded = load()
		if (loaded.size > limit + 1 || !isValidOrderedActivityCandidatePage(loaded)) {
			return ImportedActivityOrderedProductPage.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val selected = loaded.take(limit)
		val evaluations = selected.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE)
			.flatMap { batch ->
				database.evaluateImportedActivityProductBatch(
					batch.map(ImportedActivityOrderedHistoryCandidate::toHistoryCandidate), null,
				)
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



	/**
	 * Evaluates complete imported Activity export candidates within the selected UTC millisecond
	 * range and source bounds.
	 */
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
			if (page.isEmpty()) {
				break
			}
			if (!isValidActivityCandidatePage(page, beforeStartTimeMs, beforeIdentity)) {
				return page.take(1).unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
			}
			if (page.size > remaining) {
				return page.take(1).unverifiableImportedActivityProduct(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
			}
			candidates += page
			val last = page.last()
			beforeStartTimeMs = last.startTimeMs
			beforeIdentity = last.identity
			if (page.size < pageLimit) {
				break
			}
		}
		return evaluateCandidates(candidates, null, null, ActivityCapturedPortableFormatV1.MAX_ENTRIES)
	}

	private suspend fun evaluateCandidates(
		candidates: List<ImportedActivityHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
		maximumSize: Int,
	): List<ImportedActivityProductEvaluation> {
		if (!isValidActivityCandidatePage(candidates, beforeStartTimeMs, beforeIdentity, maximumSize)) {
			return candidates.unverifiableImportedActivityProduct(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		return candidates.chunked(ImportedActivityDao.HISTORY_EVALUATION_BATCH_SIZE).flatMap { batch ->
			database.evaluateImportedActivityProductBatch(batch, null)
		}
	}









	private companion object {
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

/**
 * Verified imported Activity hierarchy with deletion/retention masks, or a candidate-specific
 * qualification failure.
 */
sealed interface ImportedActivityProductEvaluation {
	val candidate: ImportedActivityHistoryCandidate

	/**
	 * Verified imported source hierarchy with independent deletion/retention masks; readable does not
	 * imply complete re-export eligibility.
	 */
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

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		override val candidate: ImportedActivityHistoryCandidate,
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityProductEvaluation
}

/**
 * Imported Activity evaluation plus the original member-time/identity pair used for deterministic
 * recent ordering.
 */
data class ImportedActivityOrderedProductEvaluation(
	val evaluation: ImportedActivityProductEvaluation,
	val recencyStartTimeMs: Long,
	val recencyMemberIdentity: PortableActivityOpaqueIdentity,
)

/**
 * Bounded imported Activity evaluations with continuation availability, or a page-wide
 * qualification failure.
 */
sealed interface ImportedActivityOrderedProductPage {
	/**
	 * Contains bounded imported source evaluations; individual payloads retain their deletion and
	 * retention masks.
	 */
	data class Ready(
		val evaluations: List<ImportedActivityOrderedProductEvaluation>,
		val hasMore: Boolean,
	) : ImportedActivityOrderedProductPage

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityOrderedProductPage
}

/**
 * Finite candidate, row, text-byte, portable/public-payload and query budgets for one imported
 * Activity scan.
 */
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

/**
 * Bounded imported Activity evaluations or the reason the selected scope cannot be verified.
 */
sealed interface ImportedActivityProductScanPage {
	/**
	 * Contains bounded imported source evaluations; individual payloads retain their deletion and
	 * retention masks.
	 */
	data class Ready(
		val evaluations: List<ImportedActivityProductEvaluation>,
	) : ImportedActivityProductScanPage

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityProductScanPage
}

/**
 * Missing source state, stale epoch, invalid origin/lineage, temporal authority or exceeded
 * read/value bounds.
 */
enum class ImportedActivityProductFailure {
	SOURCE_EVIDENCE_STATE_MISSING,
	STALE_COLLECTED_DATA_EPOCH,
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
	TEMPORAL_AUTHORITY_UNAVAILABLE,
	VALUE_OVERFLOW,
}




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










internal data class ImportedActivityOwnerAuditKey(
	val ownerKind: String,
	val protectedIdentity: String,
	val sourceKind: Int?,
	val purpose: String?,
	val scopeKind: String?,
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









internal sealed interface ImportedActivityRetentionVisitResult {
	data class Complete(
		val candidateCount: Int,
		val retainedCount: Int,
	) : ImportedActivityRetentionVisitResult

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityRetentionVisitResult
}

internal fun ImportedActivityHistoryCandidate.unverifiableImportedActivityProduct(
	reason: ImportedActivityProductFailure,
) =
	ImportedActivityProductEvaluation.Unverifiable(this, reason)

internal fun List<ImportedActivityHistoryCandidate>.unverifiableImportedActivityProduct(
	reason: ImportedActivityProductFailure,
) =
	map { it.unverifiableImportedActivityProduct(reason) }


