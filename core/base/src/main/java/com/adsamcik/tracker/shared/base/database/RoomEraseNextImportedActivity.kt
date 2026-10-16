package com.adsamcik.tracker.shared.base.database

import com.adsamcik.tracker.shared.base.database.data.SourceEvidenceState
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.dao.ImportedActivityDao
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetainedIdentityEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityRetentionReceiptEntity
import com.adsamcik.tracker.shared.base.database.data.SessionManifestPurposeCode
import com.adsamcik.tracker.shared.base.database.data.SourceDeletionFenceEntity
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Erases at most one authenticated imported Activity lineage.
 *
 * Repeated calls provide the bounded continuation for source-wide erasure. A live lineage reuses
 * selected deletion, while a payload-free retained lineage is converted to redacted deletion
 * authority without losing its typed entry/run/window/scope collision protection.
 */
// One source erase transaction owns live/retained selection, marker writes and read-back verification.
@Singleton
class RoomEraseNextImportedActivity internal constructor(
	private val database: AppDatabase,
	private val selectedDeletion: RoomDeleteSelectedImportedActivity,
	private val ioDispatcher: CoroutineDispatcher,
	private val writeCheckpoint: suspend (ImportedActivitySourceEraseCheckpoint) -> Unit,
) {
	@Inject
	constructor(
		database: AppDatabase,
		selectedDeletion: RoomDeleteSelectedImportedActivity,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(
		database,
		selectedDeletion,
		ioDispatcher,
		{ currentCoroutineContext().ensureActive() },
	)

	/**
	 * Erases one verified live or retained imported Activity lineage, preserving exact epoch checks
	 * and replay fences.
	 */
	suspend fun eraseNext(
		expectedCollectedDataEpoch: Long,
		deletedAtMs: Long,
	): EraseNextImportedActivityResult = withContext(ioDispatcher) {
		require(expectedCollectedDataEpoch >= 0L)
		require(deletedAtMs >= 0L)
		try {
			database.withTransaction {
				eraseNextInTransaction(expectedCollectedDataEpoch, deletedAtMs)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedActivitySourceEraseAbort) {
			abort.result
		} catch (abort: RoomDeleteSelectedImportedActivity.ImportedActivityDeletionAbort) {
			abort.result.toSourceEraseResult()
		} catch (_: SQLiteConstraintException) {
			EraseNextImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			EraseNextImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: ArithmeticException) {
			EraseNextImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.VALUE_OVERFLOW,
			)
		} catch (_: IllegalArgumentException) {
			EraseNextImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
	}

	/** Read-only probe used after the bounded mutation budget is exhausted. */
	suspend fun probeRemaining(
		expectedCollectedDataEpoch: Long,
	): ImportedActivityEraseRemainingResult = withContext(ioDispatcher) {
		require(expectedCollectedDataEpoch >= 0L)
		try {
			database.withTransaction {
				probeRemainingInTransaction(expectedCollectedDataEpoch)
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			ImportedActivityEraseRemainingResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: ArithmeticException) {
			ImportedActivityEraseRemainingResult.Unverifiable(
				ImportedActivityProductFailure.VALUE_OVERFLOW,
			)
		} catch (_: IllegalArgumentException) {
			ImportedActivityEraseRemainingResult.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
	}

	private suspend fun probeRemainingInTransaction(
		expectedCollectedDataEpoch: Long,
	): ImportedActivityEraseRemainingResult {
		val state = database.sourceEvidenceStateDao().get()
			?: return ImportedActivityEraseRemainingResult.Unverifiable(
				ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
			)
		if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
			return ImportedActivityEraseRemainingResult.Blocked(
				SelectedImportedActivityDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		}
		if (state.hasInvalidActivityEraseShape()) {
			return ImportedActivityEraseRemainingResult.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		return if (database.importedActivityDao().hasErasableProductCandidate()) {
			ImportedActivityEraseRemainingResult.Remaining
		} else {
			ImportedActivityEraseRemainingResult.Complete
		}
	}

	private fun SourceEvidenceState.hasInvalidActivityEraseShape(): Boolean =
		revision < 0L || updatedAtMs < 0L || retainedFromMs?.let { it < 0L } == true

	// Missing state, changed epoch, malformed authority and empty history are distinct ordered exits.
	@Suppress("ReturnCount")
	private suspend fun eraseNextInTransaction(
		expectedCollectedDataEpoch: Long,
		deletedAtMs: Long,
	): EraseNextImportedActivityResult {
		writeCheckpoint(ImportedActivitySourceEraseCheckpoint.TRANSACTION_STARTED)
		val state = database.sourceEvidenceStateDao().get()
			?: return EraseNextImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
			)
		if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
			return EraseNextImportedActivityResult.Blocked(
				SelectedImportedActivityDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
			)
		}
		if (state.hasInvalidActivityEraseShape()) {
			return EraseNextImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val evaluation = ImportedActivityProductReader(database)
			.selectRecentInTransaction(1)
			.singleOrNull()
			?: return EraseNextImportedActivityResult.Complete
		writeCheckpoint(ImportedActivitySourceEraseCheckpoint.LINEAGE_AUTHENTICATED)
		return when (evaluation) {
			is ImportedActivityProductEvaluation.Unverifiable ->
				EraseNextImportedActivityResult.Unverifiable(evaluation.reason)
			is ImportedActivityProductEvaluation.Readable -> eraseLive(
				evaluation,
				expectedCollectedDataEpoch,
				deletedAtMs,
			)
			is ImportedActivityProductEvaluation.Retained -> eraseRetained(
				evaluation,
				expectedCollectedDataEpoch,
				deletedAtMs,
			)
		}
	}

	private suspend fun eraseLive(
		evaluation: ImportedActivityProductEvaluation.Readable,
		expectedCollectedDataEpoch: Long,
		deletedAtMs: Long,
	): EraseNextImportedActivityResult {
		if (evaluation.entryDeleted) {
			return EraseNextImportedActivityResult.Unverifiable(
				ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
			)
		}
		val selected = SelectedImportedActivityIdentity(
			entryIdentity = evaluation.entry.identity,
			importRevision = evaluation.candidate.importRevision,
			contentChecksum = evaluation.entry.contentChecksum,
			runDeletionScopes = evaluation.entry.runs.map { run ->
				SelectedImportedActivityRunDeletionScope(run.identity, run.deletionScopeDigest)
			},
			windowIdentities = evaluation.entry.runs.flatMap { run ->
				run.windows.map { window -> window.identity }
			},
		)
		installLiveScopeFences(selected, expectedCollectedDataEpoch, deletedAtMs)
		return selectedDeletion.deleteInTransaction(
			DeleteSelectedImportedActivityRequest(
				selected = selected,
				expectedCollectedDataEpoch = expectedCollectedDataEpoch,
				deletedAtMs = deletedAtMs,
			),
		).toSourceEraseResult()
	}

	private suspend fun installLiveScopeFences(
		selected: SelectedImportedActivityIdentity,
		expectedCollectedDataEpoch: Long,
		deletedAtMs: Long,
	) {
		selected.runDeletionScopes.forEach { run ->
			val expected = SourceDeletionFenceEntity.createForOriginalRunDigest(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeIdentityDigest = run.deletionScopeDigest.value,
				fenceGeneration = FIRST_DELETION_GENERATION,
				collectedDataEpoch = expectedCollectedDataEpoch,
				deletedAtMs = deletedAtMs,
			)
			if (database.sourceDeletionFenceDao().insertIfAbsent(expected) == INSERT_IGNORED) {
				val retained = database.sourceDeletionFenceDao().get(
					expected.sourceKind,
					expected.purpose,
					expected.scopeKind,
					expected.scopeIdentityDigest,
				) ?: abortCorrupt()
				requireExactLiveScopeFence(retained, expected, expectedCollectedDataEpoch, deletedAtMs)
			}
		}
		writeCheckpoint(ImportedActivitySourceEraseCheckpoint.LIVE_SCOPE_FENCES_RECORDED)
	}

	private fun requireExactLiveScopeFence(
		retained: SourceDeletionFenceEntity,
		expected: SourceDeletionFenceEntity,
		expectedCollectedDataEpoch: Long,
		deletedAtMs: Long,
	) {
		// The retained fence must match all fields of the expected immutable scope identity.
		@Suppress("ComplexCondition")
		if (retained.sourceKind != expected.sourceKind ||
			retained.purpose != expected.purpose ||
			retained.scopeKind != expected.scopeKind ||
			retained.scopeIdentityDigest != expected.scopeIdentityDigest ||
			retained.collectedDataEpoch != expectedCollectedDataEpoch ||
			retained.fenceGeneration != FIRST_DELETION_GENERATION
		) {
			abortCorrupt()
		}
		if (retained.deletedAtMs > deletedAtMs) {
			abortBlocked(SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST)
		}
	}

	// Each pre-mutation witness failure returns its existing corrupt result before any writes.
	@Suppress("ReturnCount")
	private suspend fun eraseRetained(
		evaluation: ImportedActivityProductEvaluation.Retained,
		expectedCollectedDataEpoch: Long,
		deletedAtMs: Long,
	): EraseNextImportedActivityResult {
		val dao = database.importedActivityDao()
		val (receipt, markers) = dao.authenticatedRetainedActivityEraseMarkers(evaluation.candidate.identity)
			?: return corrupt()
		val runIdentities = markers.filter {
			it.identityKind == ImportedActivityRetainedIdentityEntity.RUN
		}.map(ImportedActivityRetainedIdentityEntity::protectedIdentity)
		val scopeDigests = markers.filter {
			it.identityKind == ImportedActivityRetainedIdentityEntity.DELETION_SCOPE
		}.map(ImportedActivityRetainedIdentityEntity::protectedIdentity)
		val windowCount = markers.count {
			it.identityKind == ImportedActivityRetainedIdentityEntity.WINDOW
		}
		if (!hasCompleteRetainedActivityEraseScopes(runIdentities, scopeDigests)) {
			return corrupt()
		}
		if (dao.entryDeletion(receipt.entryIdentity) != null ||
			dao.entryDeletionReceipt(receipt.entryIdentity) != null
		) {
			return corrupt()
		}

		val existingRunDeletions = loadRetainedActivityRunDeletions(
			dao, runIdentities, expectedCollectedDataEpoch,
		) ?: return corrupt()
		val existingSourceFences = loadRetainedActivitySourceFences(scopeDigests, expectedCollectedDataEpoch)
			?: return corrupt()
		val latestDurableTimeMs = database.retainedActivityEraseLatestTime(
			receipt, existingRunDeletions, existingSourceFences,
		) ?: return corrupt()
		if (deletedAtMs < latestDurableTimeMs) {
			return EraseNextImportedActivityResult.Blocked(
				SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST,
			)
		}

		recordRetainedActivityEraseMarkers(
			dao, receipt, runIdentities, scopeDigests, existingRunDeletions, existingSourceFences,
			expectedCollectedDataEpoch, deletedAtMs,
		)
		return redactRetainedActivityEraseReceipt(
			dao, receipt, runIdentities, scopeDigests, markers, windowCount, deletedAtMs,
		)
	}

	private suspend fun loadRetainedActivityRunDeletions(
		dao: ImportedActivityDao,
		runIdentities: List<String>,
		expectedCollectedDataEpoch: Long,
	): List<ImportedActivityDeletionGenerationEntity>? {
		val existingRunDeletions = dao.deletionGenerations(runIdentities)
		if (existingRunDeletions.distinctBy { it.runIdentity }.size != existingRunDeletions.size ||
			existingRunDeletions.any {
				it.runIdentity !in runIdentities ||
					it.collectedDataEpoch != expectedCollectedDataEpoch ||
					it.generation != FIRST_DELETION_GENERATION
			}
		) {
			return null
		}
		return existingRunDeletions
	}

	private suspend fun loadRetainedActivitySourceFences(
		scopeDigests: List<String>,
		expectedCollectedDataEpoch: Long,
	): List<SourceDeletionFenceEntity>? {
		val existingSourceFences = database.trackingHistoryReadDao().deletionFences(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigests = scopeDigests,
		)
		if (existingSourceFences.distinctBy { it.scopeIdentityDigest }.size !=
			existingSourceFences.size ||
			existingSourceFences.any {
				it.scopeIdentityDigest !in scopeDigests ||
					it.collectedDataEpoch != expectedCollectedDataEpoch ||
					it.fenceGeneration != FIRST_DELETION_GENERATION
			}
		) {
			return null
		}
		return existingSourceFences
	}

	// One marker-write phase uses the existing authenticated rows and original epoch/time explicitly.
	@Suppress("LongParameterList")
	private suspend fun recordRetainedActivityEraseMarkers(
		dao: ImportedActivityDao,
		receipt: ImportedActivityRetentionReceiptEntity,
		runIdentities: List<String>,
		scopeDigests: List<String>,
		existingRunDeletions: List<ImportedActivityDeletionGenerationEntity>,
		existingSourceFences: List<SourceDeletionFenceEntity>,
		expectedCollectedDataEpoch: Long,
		deletedAtMs: Long,
	) {
		val existingRuns = existingRunDeletions.associateBy { it.runIdentity }
		runIdentities.filterNot(existingRuns::containsKey).forEach { runIdentity ->
			dao.insertDeletionGeneration(
				ImportedActivityDeletionGenerationEntity.create(
					runIdentity = runIdentity,
					collectedDataEpoch = expectedCollectedDataEpoch,
					generation = FIRST_DELETION_GENERATION,
					deletedAtMs = deletedAtMs,
				),
			)
		}
		val existingScopes = existingSourceFences.associateBy { it.scopeIdentityDigest }
		scopeDigests.filterNot(existingScopes::containsKey).forEach { scopeDigest ->
			val fence = SourceDeletionFenceEntity.createForOriginalRunDigest(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeIdentityDigest = scopeDigest,
				fenceGeneration = FIRST_DELETION_GENERATION,
				collectedDataEpoch = expectedCollectedDataEpoch,
				deletedAtMs = deletedAtMs,
			)
			if (database.sourceDeletionFenceDao().insertIfAbsent(fence) == INSERT_IGNORED &&
				database.sourceDeletionFenceDao().get(
					fence.sourceKind,
					fence.purpose,
					fence.scopeKind,
					fence.scopeIdentityDigest,
				) != fence
			) {
				abortCorrupt()
			}
		}
		val entryDeletion = ImportedActivityEntryDeletionEntity.create(
			entryIdentity = receipt.entryIdentity,
			collectedDataEpoch = expectedCollectedDataEpoch,
			deletedImportRevision = receipt.latestImportRevision,
			deletedAtMs = deletedAtMs,
		)
		dao.insertEntryDeletion(entryDeletion)
		writeCheckpoint(ImportedActivitySourceEraseCheckpoint.SOURCE_MARKERS_RECORDED)
	}

	// Keep final receipt construction, writes and read-back under the same caller-owned transaction.
	@Suppress("LongParameterList")
	private suspend fun redactRetainedActivityEraseReceipt(
		dao: ImportedActivityDao,
		receipt: ImportedActivityRetentionReceiptEntity,
		runIdentities: List<String>,
		scopeDigests: List<String>,
		markers: List<ImportedActivityRetainedIdentityEntity>,
		windowCount: Int,
		deletedAtMs: Long,
	): EraseNextImportedActivityResult {
		val finalRunDeletions = dao.deletionGenerations(runIdentities)
		val finalSourceFences = database.trackingHistoryReadDao().deletionFences(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigests = scopeDigests,
		)
		if (finalRunDeletions.size != runIdentities.size ||
			finalSourceFences.size != scopeDigests.size
		) {
			abortCorrupt()
		}
		val nextRevision = Math.addExact(
			database.sourceEvidenceStateDao().get()?.revision ?: abortCorrupt(),
			1L,
		)
		val redactedReceipt = ImportedActivityRetentionReceiptEntity.createRedacted(
			original = receipt,
			sourceEvidenceRevision = nextRevision,
			retainedAtMs = deletedAtMs,
			runDeletions = finalRunDeletions,
			sourceFences = finalSourceFences,
			markers = markers,
		)
		if (dao.updateRetentionReceipt(redactedReceipt) != 1 ||
			database.sourceEvidenceStateDao().incrementRevision(deletedAtMs) != 1
		) {
			abortCorrupt()
		}
		writeCheckpoint(ImportedActivitySourceEraseCheckpoint.RETAINED_RECEIPT_REDACTED)
		val currentState = database.sourceEvidenceStateDao().get() ?: abortCorrupt()
		if (currentState.revision != nextRevision ||
			database.authenticateImportedActivityRetentionBatch(
				currentState,
				listOf(redactedReceipt),
			) != null ||
			dao.retainedHistoryCandidate(receipt.entryIdentity) != null
		) {
			abortCorrupt()
		}
		return EraseNextImportedActivityResult.ErasedRetained(
			physicalRunCount = runIdentities.size,
			windowCount = windowCount,
		)
	}

	private fun corrupt() = EraseNextImportedActivityResult.Unverifiable(
		ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	)

	private fun abortCorrupt(): Nothing = throw ImportedActivitySourceEraseAbort(corrupt())

	private fun abortBlocked(reason: SelectedImportedActivityDeletionBlockedReason): Nothing =
		throw ImportedActivitySourceEraseAbort(EraseNextImportedActivityResult.Blocked(reason))

	private companion object {
		const val FIRST_DELETION_GENERATION = 1L
		const val INSERT_IGNORED = -1L
	}
}

private class ImportedActivitySourceEraseAbort(
	val result: EraseNextImportedActivityResult,
) : RuntimeException(null, null, false, false)

internal enum class ImportedActivitySourceEraseCheckpoint {
	TRANSACTION_STARTED,
	LINEAGE_AUTHENTICATED,
	LIVE_SCOPE_FENCES_RECORDED,
	SOURCE_MARKERS_RECORDED,
	RETAINED_RECEIPT_REDACTED,
}

/**
 * One bounded imported Activity erase result with live/retained progress or explicit
 * complete/failure states.
 */
sealed interface EraseNextImportedActivityResult {
	/**
	 * No further affected imported payload remains for this bounded source erase.
	 */
	data object Complete : EraseNextImportedActivityResult

	/**
	 * One live imported Activity correction lineage was fenced and removed; reports revisions and
	 * physical runs.
	 */
	data class ErasedLive(
		val importRevisionCount: Int,
		val physicalRunCount: Int,
	) : EraseNextImportedActivityResult

	/**
	 * One retained imported Activity witness lineage was erased; reports its physical runs and
	 * windows.
	 */
	data class ErasedRetained(
		val physicalRunCount: Int,
		val windowCount: Int,
	) : EraseNextImportedActivityResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: SelectedImportedActivityDeletionBlockedReason,
	) : EraseNextImportedActivityResult

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : EraseNextImportedActivityResult

	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class RetryableFailure(
		val reason: PortableActivityTransferRetryableReason,
	) : EraseNextImportedActivityResult
}

/**
 * Whether imported Activity erase has completed or additional live/retained payload remains.
 */
sealed interface ImportedActivityEraseRemainingResult {
	/**
	 * No further affected imported payload remains for this bounded source erase.
	 */
	data object Complete : ImportedActivityEraseRemainingResult

	/**
	 * Additional imported live or retained Activity payload remains for a later bounded erase call.
	 */
	data object Remaining : ImportedActivityEraseRemainingResult

	/**
	 * Changed or invalid scope/authority prevents the requested operation; the exact reason remains
	 * explicit.
	 */
	data class Blocked(
		val reason: SelectedImportedActivityDeletionBlockedReason,
	) : ImportedActivityEraseRemainingResult

	/**
	 * The stored scope or original source lineage could not be verified; this is not an empty or
	 * measured-zero result.
	 */
	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityEraseRemainingResult

	/**
	 * A transient concurrent-state, ownership or storage problem prevents completion and permits
	 * retry.
	 */
	data class RetryableFailure(
		val reason: PortableActivityTransferRetryableReason,
	) : ImportedActivityEraseRemainingResult
}

private suspend fun ImportedActivityDao.authenticatedRetainedActivityEraseMarkers(
	identity: String,
): Pair<ImportedActivityRetentionReceiptEntity, List<ImportedActivityRetainedIdentityEntity>>? {
	val receipt = retentionReceipt(identity)
		?: return null
	val markers = retainedIdentitiesForEntries(
		listOf(identity),
		receipt.protectedIdentityCount + 1,
	)
	if (markers.size != receipt.protectedIdentityCount || !receipt.authenticates(markers)) {
		return null
	}
	return receipt to markers
}

private fun hasCompleteRetainedActivityEraseScopes(
	runIdentities: List<String>,
	scopeDigests: List<String>,
): Boolean = runIdentities.isNotEmpty() && runIdentities.size == scopeDigests.size &&
	runIdentities.distinct().size == runIdentities.size && scopeDigests.distinct().size == scopeDigests.size

private suspend fun AppDatabase.retainedActivityEraseLatestTime(
	receipt: ImportedActivityRetentionReceiptEntity,
	existingRunDeletions: List<ImportedActivityDeletionGenerationEntity>,
	existingSourceFences: List<SourceDeletionFenceEntity>,
): Long? {
	val evidenceUpdatedAtMs = sourceEvidenceStateDao().get()?.updatedAtMs ?: return null
	return maxOf(
		evidenceUpdatedAtMs,
		receipt.retainedAtMs,
		existingRunDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
		existingSourceFences.maxOfOrNull { it.deletedAtMs } ?: 0L,
	)
}

private fun DeleteSelectedImportedActivityResult.toSourceEraseResult():
	EraseNextImportedActivityResult = when (this) {
	is DeleteSelectedImportedActivityResult.Deleted ->
		EraseNextImportedActivityResult.ErasedLive(importRevisionCount, physicalRunCount)
	is DeleteSelectedImportedActivityResult.Blocked ->
		EraseNextImportedActivityResult.Blocked(reason)
	is DeleteSelectedImportedActivityResult.Unverifiable ->
		EraseNextImportedActivityResult.Unverifiable(reason)
	is DeleteSelectedImportedActivityResult.RetryableFailure ->
		EraseNextImportedActivityResult.RetryableFailure(reason)
	is DeleteSelectedImportedActivityResult.AlreadyDeleted,
	DeleteSelectedImportedActivityResult.NotFound,
	-> EraseNextImportedActivityResult.Unverifiable(
		ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	)
}
