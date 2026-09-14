package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityHistoryCandidate
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryRevisionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityFragmentEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityReceiptEntity
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
	internal suspend fun selectIdentityInTransaction(
		identity: PortableActivityOpaqueIdentity,
	): ImportedActivityProductEvaluation? {
		val candidate = database.importedActivityDao().latestHistoryCandidate(identity.value)
			?: return null
		return evaluateCandidates(listOf(candidate), null, null, 1).single()
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
			evaluateBatch(batch)
		}
	}

	@Suppress("LongMethod")
	private suspend fun evaluateBatch(
		candidates: List<ImportedActivityHistoryCandidate>,
	): List<ImportedActivityProductEvaluation> {
		if (candidates.isEmpty()) return emptyList()
		val state = database.sourceEvidenceStateDao().get()
			?: return candidates.unverifiable(ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch < 0L || state.retainedFromMs?.let { it < 0L } == true) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		val identities = candidates.map(ImportedActivityHistoryCandidate::identity)
		val loaded = try {
			ImportedActivityProductBatch(
				headers = database.importedActivityDao().entryRevisionsForHistory(identities),
				receipts = database.importedActivityDao().receiptsForHistory(identities),
				runs = database.importedActivityDao().runsForHistory(identities),
				zoneEpochs = database.importedActivityDao().zoneEpochsForHistory(identities),
				windows = database.importedActivityDao().windowsForHistory(identities),
				fragments = database.importedActivityDao().fragmentsForHistory(identities),
			)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (loaded.exceedsBatchLimit(identities.size)) {
			return candidates.unverifiable(ImportedActivityProductFailure.DEPENDENCY_OVERFLOW)
		}
		val hasExactOwnership = try {
			hasExactImportedIdentityOwnership(loaded)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: RuntimeException) {
			return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
		}
		if (!loaded.belongsOnlyTo(identities) || !hasExactOwnership) {
			return candidates.unverifiable(ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT)
		}

		val entryDeletions = database.importedActivityDao().entryDeletionsForHistory(identities)
		val runIdentities = loaded.runs.map(ImportedActivityRunEntity::identity).distinct()
		val runDeletions = database.importedActivityDao().deletionGenerationsForHistory(runIdentities)
		val deletionScopes = loaded.runs.map(ImportedActivityRunEntity::deletionScopeDigest).distinct()
		val sourceDeletions = deletionScopes.chunked(SQLITE_BIND_BATCH).flatMap { scopes ->
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = scopes,
			)
		}
		if (entryDeletions.distinctBy(ImportedActivityEntryDeletionEntity::entryIdentity).size !=
			entryDeletions.size ||
			runDeletions.distinctBy(ImportedActivityDeletionGenerationEntity::runIdentity).size !=
			runDeletions.size ||
			entryDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
			runDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch } ||
			sourceDeletions.any { it.collectedDataEpoch != state.collectedDataEpoch }
		) return candidates.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)

		val headersByIdentity = loaded.headers.groupBy(ImportedActivityEntryRevisionEntity::identity)
		val receiptsByIdentity = loaded.receipts.groupBy(ImportedActivityReceiptEntity::entryIdentity)
		val runsByIdentity = loaded.runs.groupBy(ImportedActivityRunEntity::entryIdentity)
		val zonesByIdentity = loaded.zoneEpochs.groupBy(ImportedActivityZoneEpochEntity::entryIdentity)
		val windowsByIdentity = loaded.windows.groupBy(ImportedActivityWindowEntity::entryIdentity)
		val fragmentsByIdentity = loaded.fragments.groupBy(ImportedActivityFragmentEntity::entryIdentity)
		val entryDeletionsByIdentity = entryDeletions.associateBy(ImportedActivityEntryDeletionEntity::entryIdentity)
		val runDeletionsByIdentity = runDeletions.associateBy(ImportedActivityDeletionGenerationEntity::runIdentity)
		val sourceDeletedScopes = sourceDeletions.mapTo(hashSetOf(), SourceDeletionFenceEntity::scopeIdentityDigest)
		return candidates.map { candidate ->
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
						latest.entry.crossesRetentionBoundary(retainedFromMs)
					} == true,
				)
			} catch (failure: ImportedActivityLineageFailure) {
				candidate.unverifiable(failure.reason.toProductFailure())
			} catch (_: IllegalArgumentException) {
				candidate.unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)
			} catch (_: ArithmeticException) {
				candidate.unverifiable(ImportedActivityProductFailure.VALUE_OVERFLOW)
			}
		}
	}

	private suspend fun hasExactImportedIdentityOwnership(batch: ImportedActivityProductBatch): Boolean {
		val entryOwners = batch.headers.mapTo(linkedSetOf(), ImportedActivityEntryRevisionEntity::identity)
		val runOwners = batch.runs.groupBy(ImportedActivityRunEntity::identity)
		val windowOwners = batch.windows.groupBy(ImportedActivityWindowEntity::identity)
		val scopeOwners = batch.runs.groupBy(ImportedActivityRunEntity::deletionScopeDigest)
		val allIdentities = (entryOwners + runOwners.keys + windowOwners.keys).toList()
		if (allIdentities.size != entryOwners.size + runOwners.size + windowOwners.size ||
			scopeOwners.keys.any { it in allIdentities }
		) return false
		for (identities in allIdentities.chunked(SQLITE_BIND_BATCH)) {
			val limit = identities.size + 1
			val entries = database.importedActivityDao().existingEntryIdentities(identities, limit)
			val runs = database.importedActivityDao().existingRunIdentityOwners(identities, limit)
			val windows = database.importedActivityDao().existingWindowIdentityOwners(identities, limit)
			val scopes = database.importedActivityDao().existingRunScopeOwners(identities, 1)
			val deletedEntries = database.importedActivityDao().entryDeletions(identities)
			val deletionReceipts = database.importedActivityDao().entryDeletionReceipts(identities)
			val deletedRuns = database.importedActivityDao().deletionGenerations(identities)
			if (entries.size >= limit || runs.size >= limit || windows.size >= limit || scopes.isNotEmpty()) {
				return false
			}
			if (entries.any { it !in entryOwners } || runs.any { owner ->
				val expected = runOwners[owner.identity].orEmpty()
				expected.isEmpty() || expected.any {
					it.entryIdentity != owner.entryIdentity ||
						it.deletionScopeDigest != owner.deletionScopeDigest
				}
			} || windows.any { owner ->
				val expected = windowOwners[owner.identity].orEmpty()
				expected.isEmpty() || expected.any {
					it.entryIdentity != owner.entryIdentity || it.runIdentity != owner.runIdentity
				}
			} || deletedEntries.any { it.entryIdentity !in entryOwners } ||
				deletionReceipts.any { receipt ->
					val deletion = deletedEntries.singleOrNull {
						it.entryIdentity == receipt.entryIdentity
					}
					receipt.entryIdentity !in entryOwners || deletion == null ||
						receipt.collectedDataEpoch != deletion.collectedDataEpoch ||
						receipt.deletedImportRevision != deletion.deletedImportRevision ||
						receipt.deletedAtMs != deletion.deletedAtMs
				} ||
				deletedRuns.any { it.runIdentity !in runOwners }
			) return false
		}
		for (scopes in scopeOwners.keys.chunked(SQLITE_BIND_BATCH)) {
			val limit = scopes.size + 1
			val owners = database.importedActivityDao().existingRunScopeOwners(scopes, limit)
			if (owners.size >= limit || owners.any { owner ->
				val expected = scopeOwners[owner.deletionScopeDigest].orEmpty()
				expected.isEmpty() || expected.any {
					it.identity != owner.identity || it.entryIdentity != owner.entryIdentity
				}
			} || database.importedActivityDao().existingEntryIdentities(scopes, 1).isNotEmpty() ||
				database.importedActivityDao().existingRunIdentityOwners(scopes, 1).isNotEmpty() ||
				database.importedActivityDao().existingWindowIdentityOwners(scopes, 1).isNotEmpty() ||
				database.importedActivityDao().entryDeletions(scopes).isNotEmpty() ||
				database.importedActivityDao().entryDeletionReceipts(scopes).isNotEmpty() ||
				database.importedActivityDao().deletionGenerations(scopes).isNotEmpty()
			) return false
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
				it.endTimeMs < it.startTimeMs || it.receivedAtMs < 0L
		}) return false
		val cursors = buildList {
			if (beforeStartTimeMs != null && beforeIdentity != null) add(beforeStartTimeMs to beforeIdentity)
			addAll(candidates.map { it.startTimeMs to it.identity })
		}
		return cursors.zipWithNext().all { (left, right) ->
			left.first > right.first || left.first == right.first && left.second > right.second
		}
	}

	private companion object {
		const val SQLITE_BIND_BATCH = 400
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

	data class Unverifiable(
		override val candidate: ImportedActivityHistoryCandidate,
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityProductEvaluation
}

enum class ImportedActivityProductFailure {
	SOURCE_EVIDENCE_STATE_MISSING,
	STALE_COLLECTED_DATA_EPOCH,
	STORED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
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
	fun exceedsBatchLimit(identityCount: Int): Boolean =
		headers.size > identityCount * ImportedActivityDao.MAX_REVISIONS_PER_ENTRY ||
			receipts.size > identityCount * ImportedActivityDao.MAX_RECEIPTS_PER_ENTRY ||
			runs.size > identityCount * ImportedActivityDao.MAX_TOTAL_RUNS_PER_LINEAGE ||
			zoneEpochs.size > identityCount * ImportedActivityDao.MAX_TOTAL_ZONE_EPOCHS_PER_LINEAGE ||
			windows.size > identityCount * ImportedActivityDao.MAX_TOTAL_WINDOWS_PER_LINEAGE ||
			fragments.size > identityCount * ImportedActivityDao.MAX_TOTAL_FRAGMENTS_PER_LINEAGE

	fun belongsOnlyTo(identities: List<String>): Boolean {
		val expected = identities.toHashSet()
		return headers.all { it.identity in expected } && receipts.all { it.entryIdentity in expected } &&
			runs.all { it.entryIdentity in expected } && zoneEpochs.all { it.entryIdentity in expected } &&
			windows.all { it.entryIdentity in expected } && fragments.all { it.entryIdentity in expected }
	}
}

private fun ImportedActivityHistoryCandidate.exactlyMatches(
	latest: AuthenticatedImportedActivityRevision,
): Boolean = identity == latest.header.identity && importRevision == latest.header.importRevision &&
	contentChecksum == latest.header.contentChecksum && startTimeMs == latest.header.startTimeMs &&
	endTimeMs == latest.header.endTimeMs && receivedAtMs == latest.header.receivedAtMs

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
