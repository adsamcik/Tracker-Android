package com.adsamcik.tracker.stats.data.repository

import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureRunEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureWindowEntity
import com.adsamcik.tracker.stats.api.repository.ExportPortablePressureRequest
import com.adsamcik.tracker.stats.api.repository.PortablePressureEntryV1
import com.adsamcik.tracker.stats.api.repository.PortablePressureOpaqueIdentity
import com.adsamcik.tracker.stats.api.repository.PressurePortableFormatV1
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded imported-origin Pressure evaluator. Caller owns the enclosing Room transaction. */
@Singleton
internal class ImportedPressureHistoryEvaluator @Inject constructor(
	private val database: AppDatabase,
) {
	internal suspend fun selectRecentInTransaction(
		limit: Int,
	): List<ImportedPressureHistoryEvaluation> {
		require(limit in 1..ImportedPressureDao.MAX_HISTORY_ENTRY_CANDIDATES)
		val candidates = database.importedPressureDao().recentHistoryCandidatePage(
			limit = limit,
			beforeStartTimeMs = null,
			beforeIdentity = null,
		)
		return evaluateCandidates(candidates)
	}

	internal suspend fun selectForExportInTransaction(
		request: ExportPortablePressureRequest,
	): List<ImportedPressureHistoryEvaluation> {
		val selected = mutableListOf<ImportedPressureHistoryEvaluation>()
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: String? = null
		while (true) {
			currentCoroutineContext().ensureActive()
			val remaining = PressurePortableFormatV1.MAX_ENTRIES - selected.size
			val pageLimit = minOf(ImportedPressureDao.MAX_HISTORY_ENTRY_CANDIDATES, remaining + 1)
			val page = database.importedPressureDao().historyCandidatePageInRange(
				fromInclusiveMs = request.fromInclusiveMs,
				toExclusiveMs = request.toExclusiveMs,
				limit = pageLimit,
				beforeStartTimeMs = beforeStartTimeMs,
				beforeIdentity = beforeIdentity,
			)
			if (page.isEmpty()) break
			if (!isValidCandidatePage(page, beforeStartTimeMs, beforeIdentity)) {
				return selected + page.take(1).unverifiable(
					ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			if (page.size > remaining) {
				return selected + page.take(1).map {
					ImportedPressureHistoryEvaluation.Unverifiable(
						it,
						ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW,
					)
				}
			}
			selected += evaluateCandidates(page)
			val last = page.last()
			beforeStartTimeMs = last.startTimeMs
			beforeIdentity = last.identity
			if (page.size < pageLimit) break
		}
		return selected
	}

	private suspend fun evaluateCandidates(
		candidates: List<ImportedPressureHistoryCandidate>,
	): List<ImportedPressureHistoryEvaluation> {
		if (!isValidCandidatePage(candidates, null, null)) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return candidates.flatMap { candidate -> evaluateBatch(listOf(candidate)) }
	}

	@Suppress("LongMethod")
	private suspend fun evaluateBatch(
		candidates: List<ImportedPressureHistoryCandidate>,
	): List<ImportedPressureHistoryEvaluation> {
		if (candidates.isEmpty()) return emptyList()
		require(candidates.size == 1)
		if (!isValidCandidatePage(candidates, null, null)) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val state = database.sourceEvidenceStateDao().get()
			?: return candidates.unverifiable(
				ImportedPressureHistoryFailure.SOURCE_EVIDENCE_STATE_MISSING,
			)
		if (state.collectedDataEpoch < 0L || state.retainedFromMs?.let { it < 0L } == true) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val candidate = candidates.single()
		if (candidate.candidateState == IMPORTED_PRESSURE_CANDIDATE_RETAINED) {
			val receipt = database.importedPressureDao().retentionReceipt(candidate.identity)
				?: return candidates.unverifiable(
					ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
			if (!candidate.exactlyMatches(receipt)) {
				return candidates.unverifiable(
					ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			return when (database.authenticateImportedPressureRetention(state, receipt)) {
				ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW ->
					candidates.unverifiable(ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW ->
					candidates.unverifiable(ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW)
				ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
				ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
				-> candidates.unverifiable(
					ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
				null -> {
					val markers = database.importedPressureDao().retainedIdentitiesForEntries(
						listOf(candidate.identity),
						receipt.protectedIdentityCount + 1,
					)
					listOf(
						ImportedPressureHistoryEvaluation.Retained(
							candidate = candidate,
							retainedFromMs = receipt.retainedFromMs,
							retainedAtMs = receipt.retainedAtMs,
							protectedIdentities = markers.map {
								it.toRetainedPressureIdentity()
							},
						),
					)
				}
			}
		}
		if (candidate.candidateState != IMPORTED_PRESSURE_CANDIDATE_LIVE) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		when (database.importedPressureDao().lineageFootprint(candidate.identity).validate()) {
			ImportedPressureRetentionAuthorityFailure.DEPENDENCY_OVERFLOW,
			ImportedPressureRetentionAuthorityFailure.VALUE_OVERFLOW,
			-> return candidates.unverifiable(ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW)
			ImportedPressureRetentionAuthorityFailure.ORIGIN_IDENTITY_CONFLICT,
			ImportedPressureRetentionAuthorityFailure.STORED_EVIDENCE_UNVERIFIABLE,
			-> return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
			null -> Unit
		}
		val identities = candidates.map(ImportedPressureHistoryCandidate::identity)
		val loaded = try {
			ImportedPressureHistoryBatch(
				headers = database.importedPressureDao().entryRevisionsForHistory(identities),
				receipts = database.importedPressureDao().receiptsForHistory(identities),
				runs = database.importedPressureDao().runsForHistory(identities),
				windows = database.importedPressureDao().windowsForHistory(identities),
				entryDeletions = database.importedPressureDao().entryDeletionsForHistory(identities),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		if (loaded.exceedsBatchLimit(identities.size)) {
			return candidates.unverifiable(ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW)
		}
		if (!loaded.belongsOnlyTo(identities)) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}

		val tombstones = try {
			database.importedPressureDao().deletionGenerationsForHistory(
				loaded.runs.map { it.identity }.distinct(),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val tombstonesByRun = tombstones.associateBy(ImportedPressureDeletionGenerationEntity::runIdentity)
		if (tombstonesByRun.size != tombstones.size || tombstones.any {
			it.collectedDataEpoch != state.collectedDataEpoch
		}) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}

		val headersByIdentity = loaded.headers.groupBy { it.identity }
		val receiptsByIdentity = loaded.receipts.groupBy { it.entryIdentity }
		val runsByIdentity = loaded.runs.groupBy { it.entryIdentity }
		val windowsByIdentity = loaded.windows.groupBy { it.entryIdentity }
		val entryDeletionsByIdentity = loaded.entryDeletions.associateBy { it.entryIdentity }
		if (entryDeletionsByIdentity.size != loaded.entryDeletions.size) {
			return candidates.unverifiable(
				ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return candidates.map { candidate ->
			currentCoroutineContext().ensureActive()
			if (entryDeletionsByIdentity[candidate.identity] != null) {
				return@map ImportedPressureHistoryEvaluation.Unverifiable(
					candidate,
					ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			val headers = headersByIdentity[candidate.identity].orEmpty()
			if (headers.any { it.collectedDataEpoch != state.collectedDataEpoch }) {
				return@map ImportedPressureHistoryEvaluation.Unverifiable(
					candidate,
					ImportedPressureHistoryFailure.STALE_COLLECTED_DATA_EPOCH,
				)
			}
			try {
				val lineage = ImportedPressureLineageAuthenticator.authenticate(
					identity = candidate.identity,
					expectedCollectedDataEpoch = state.collectedDataEpoch,
					headers = headers,
					receipts = receiptsByIdentity[candidate.identity].orEmpty(),
					runs = runsByIdentity[candidate.identity].orEmpty(),
					windows = windowsByIdentity[candidate.identity].orEmpty(),
				)
				val latest = requireNotNull(lineage.latest)
				require(candidate.exactlyMatches(latest))
				val lineageRunIds = lineage.revisions.flatMapTo(linkedSetOf()) { revision ->
					revision.entry.runs.map { it.identity.value }
				}
				val deletedLineageRunIds = lineageRunIds.filterTo(linkedSetOf()) {
					it in tombstonesByRun
				}
				val latestRunIds = latest.entry.runs.mapTo(linkedSetOf()) { it.identity.value }
				if (deletedLineageRunIds.any { it !in latestRunIds }) {
					ImportedPressureHistoryEvaluation.Unverifiable(
						candidate,
						ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
					)
				} else {
					ImportedPressureHistoryEvaluation.Readable(
						candidate = candidate,
						lineage = lineage,
						deletedRunIdentities = deletedLineageRunIds,
						retainedFromMs = state.retainedFromMs,
					)
				}
			} catch (failure: ImportedPressureLineageFailure) {
				ImportedPressureHistoryEvaluation.Unverifiable(
					candidate,
					failure.reason.toHistoryFailure(),
				)
			} catch (_: IllegalArgumentException) {
				ImportedPressureHistoryEvaluation.Unverifiable(
					candidate,
					ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
		}
	}

	private fun isValidCandidatePage(
		candidates: List<ImportedPressureHistoryCandidate>,
		beforeStartTimeMs: Long?,
		beforeIdentity: String?,
	): Boolean {
		if (candidates.size > ImportedPressureDao.MAX_HISTORY_ENTRY_CANDIDATES) return false
		if (candidates.map { it.identity }.distinct().size != candidates.size) return false
		if (candidates.any {
			it.identity.isBlank() || it.importRevision <= 0L || it.startTimeMs < 0L ||
				it.endTimeMs < it.startTimeMs || it.receivedAtMs < 0L ||
				it.candidateState !in setOf(
					IMPORTED_PRESSURE_CANDIDATE_LIVE,
					IMPORTED_PRESSURE_CANDIDATE_RETAINED,
				)
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

}

internal sealed interface ImportedPressureHistoryEvaluation {
	val candidate: ImportedPressureHistoryCandidate

	data class Readable(
		override val candidate: ImportedPressureHistoryCandidate,
		val lineage: AuthenticatedImportedPressureLineage,
		val deletedRunIdentities: Set<String>,
		val retainedFromMs: Long?,
	) : ImportedPressureHistoryEvaluation {
		val latest: AuthenticatedImportedPressureRevision
			get() = requireNotNull(lineage.latest)

		val isReExportable: Boolean
			get() = deletedRunIdentities.isEmpty() &&
				retainedFromMs?.let { floor -> !latest.entry.crossesImportedPressureFloor(floor) } != false
	}

	/** Authenticated payload-free shell retained after the complete correction lineage was pruned. */
	data class Retained(
		override val candidate: ImportedPressureHistoryCandidate,
		val retainedFromMs: Long,
		val retainedAtMs: Long,
		val protectedIdentities: List<RetainedImportedPressureIdentity>,
	) : ImportedPressureHistoryEvaluation {
		init {
			require(retainedFromMs >= 0L && retainedAtMs >= 0L)
			require(protectedIdentities.isNotEmpty())
			require(protectedIdentities.map { it.value }.distinct().size == protectedIdentities.size)
			require(
				protectedIdentities.filterIsInstance<RetainedImportedPressureIdentity.Entry>()
					.singleOrNull()?.identity?.value == candidate.identity,
			)
		}
	}

	data class Unverifiable(
		override val candidate: ImportedPressureHistoryCandidate,
		val reason: ImportedPressureHistoryFailure,
	) : ImportedPressureHistoryEvaluation
}

internal enum class ImportedPressureHistoryFailure {
	SOURCE_EVIDENCE_STATE_MISSING,
	STALE_COLLECTED_DATA_EPOCH,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
}

private data class ImportedPressureHistoryBatch(
	val headers: List<ImportedPressureEntryRevisionEntity>,
	val receipts: List<ImportedPressureReceiptEntity>,
	val runs: List<ImportedPressureRunEntity>,
	val windows: List<ImportedPressureWindowEntity>,
	val entryDeletions: List<ImportedPressureEntryDeletionEntity>,
) {
	fun exceedsBatchLimit(identityCount: Int): Boolean =
		headers.size > identityCount * ImportedPressureDao.MAX_REVISIONS_PER_ENTRY ||
			receipts.size > identityCount * ImportedPressureDao.MAX_RECEIPTS_PER_ENTRY ||
			runs.size > identityCount * ImportedPressureDao.MAX_TOTAL_RUNS_PER_ENTRY_LINEAGE ||
			windows.size > identityCount * ImportedPressureDao.MAX_TOTAL_WINDOWS_PER_ENTRY_LINEAGE ||
			entryDeletions.size > identityCount

	fun belongsOnlyTo(identities: List<String>): Boolean {
		val expected = identities.toHashSet()
		return headers.all { it.identity in expected } &&
			receipts.all { it.entryIdentity in expected } &&
			runs.all { it.entryIdentity in expected } &&
			windows.all { it.entryIdentity in expected } &&
			entryDeletions.all { it.entryIdentity in expected }
	}
}

private fun ImportedPressureHistoryCandidate.exactlyMatches(
	latest: AuthenticatedImportedPressureRevision,
): Boolean = identity == latest.header.identity &&
	importRevision == latest.header.importRevision &&
	contentChecksum == latest.header.contentChecksum &&
	startTimeMs == latest.header.startTimeMs &&
	endTimeMs == latest.header.endTimeMs &&
	receivedAtMs == latest.header.receivedAtMs

private fun ImportedPressureHistoryCandidate.exactlyMatches(
	retained: ImportedPressureRetentionReceiptEntity,
): Boolean = identity == retained.entryIdentity &&
	importRevision == retained.latestImportRevision &&
	contentChecksum == retained.latestContentChecksum &&
	startTimeMs == retained.startTimeMs &&
	endTimeMs == retained.endTimeMs &&
	receivedAtMs == retained.receivedAtMs &&
	candidateState == IMPORTED_PRESSURE_CANDIDATE_RETAINED

private fun List<ImportedPressureHistoryCandidate>.unverifiable(
	reason: ImportedPressureHistoryFailure,
) = map { ImportedPressureHistoryEvaluation.Unverifiable(it, reason) }

private fun ImportedPressureLineageFailureReason.toHistoryFailure():
	ImportedPressureHistoryFailure = when (this) {
	ImportedPressureLineageFailureReason.DEPENDENCY_OVERFLOW,
	ImportedPressureLineageFailureReason.RUN_OVERFLOW,
	ImportedPressureLineageFailureReason.WINDOW_OVERFLOW,
	ImportedPressureLineageFailureReason.TOTAL_WINDOW_OVERFLOW,
	ImportedPressureLineageFailureReason.REVISION_OVERFLOW ->
		ImportedPressureHistoryFailure.DEPENDENCY_OVERFLOW
	ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
		ImportedPressureHistoryFailure.STORED_EVIDENCE_UNVERIFIABLE
}

internal sealed interface RetainedImportedPressureIdentity {
	val value: String

	data class Entry(
		val identity: PortablePressureOpaqueIdentity,
	) : RetainedImportedPressureIdentity {
		override val value: String get() = identity.value
	}

	data class RunScope(
		val identity: PortablePressureOpaqueIdentity,
	) : RetainedImportedPressureIdentity {
		override val value: String get() = identity.value
	}

	data class Window(
		val identity: PortablePressureOpaqueIdentity,
	) : RetainedImportedPressureIdentity {
		override val value: String get() = identity.value
	}
}

private fun ImportedPressureRetainedIdentityEntity.toRetainedPressureIdentity():
	RetainedImportedPressureIdentity {
	val identity = PortablePressureOpaqueIdentity(
		protectedIdentity,
	)
	return when (identityKind) {
		ImportedPressureRetainedIdentityEntity.ENTRY ->
			RetainedImportedPressureIdentity.Entry(identity)
		ImportedPressureRetainedIdentityEntity.RUN_SCOPE ->
			RetainedImportedPressureIdentity.RunScope(identity)
		ImportedPressureRetainedIdentityEntity.WINDOW ->
			RetainedImportedPressureIdentity.Window(identity)
		else -> error("Unknown imported Pressure retained identity kind")
	}
}

private const val IMPORTED_PRESSURE_CANDIDATE_LIVE = "LIVE"
private const val IMPORTED_PRESSURE_CANDIDATE_RETAINED = "RETAINED"

internal fun PortablePressureEntryV1.crossesImportedPressureFloor(floorMs: Long): Boolean =
	runs.any { run ->
		if (run.windows.isEmpty()) {
			run.retentionLoss && run.startTimeMs < floorMs
		} else {
			run.windows.any { window ->
				val earliest = if (window.wallTimeUncertaintyMs > window.intervalStartTimeMs) {
					0L
				} else {
					window.intervalStartTimeMs - window.wallTimeUncertaintyMs
				}
				earliest < floorMs
			}
		}
	}
