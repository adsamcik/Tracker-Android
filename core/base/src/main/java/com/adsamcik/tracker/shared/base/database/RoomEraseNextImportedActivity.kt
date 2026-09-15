package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
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
				val state = database.sourceEvidenceStateDao().get()
					?: return@withTransaction ImportedActivityEraseRemainingResult.Unverifiable(
						ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
					)
				if (state.collectedDataEpoch != expectedCollectedDataEpoch) {
					return@withTransaction ImportedActivityEraseRemainingResult.Blocked(
						SelectedImportedActivityDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED,
					)
				}
				if (state.revision < 0L || state.updatedAtMs < 0L ||
					state.retainedFromMs?.let { it < 0L } == true
				) {
					return@withTransaction ImportedActivityEraseRemainingResult.Unverifiable(
						ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
					)
				}
				if (database.importedActivityDao().hasErasableProductCandidate()) {
					ImportedActivityEraseRemainingResult.Remaining
				} else {
					ImportedActivityEraseRemainingResult.Complete
				}
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
		if (state.revision < 0L || state.updatedAtMs < 0L ||
			state.retainedFromMs?.let { it < 0L } == true
		) {
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
		}
		writeCheckpoint(ImportedActivitySourceEraseCheckpoint.LIVE_SCOPE_FENCES_RECORDED)
	}

	@Suppress("LongMethod", "ComplexCondition")
	private suspend fun eraseRetained(
		evaluation: ImportedActivityProductEvaluation.Retained,
		expectedCollectedDataEpoch: Long,
		deletedAtMs: Long,
	): EraseNextImportedActivityResult {
		val dao = database.importedActivityDao()
		val receipt = dao.retentionReceipt(evaluation.candidate.identity)
			?: return corrupt()
		val markers = dao.retainedIdentitiesForEntries(
			listOf(evaluation.candidate.identity),
			receipt.protectedIdentityCount + 1,
		)
		if (markers.size != receipt.protectedIdentityCount || !receipt.authenticates(markers)) {
			return corrupt()
		}
		val runIdentities = markers.filter {
			it.identityKind == ImportedActivityRetainedIdentityEntity.RUN
		}.map(ImportedActivityRetainedIdentityEntity::protectedIdentity)
		val scopeDigests = markers.filter {
			it.identityKind == ImportedActivityRetainedIdentityEntity.DELETION_SCOPE
		}.map(ImportedActivityRetainedIdentityEntity::protectedIdentity)
		val windowCount = markers.count {
			it.identityKind == ImportedActivityRetainedIdentityEntity.WINDOW
		}
		if (runIdentities.isEmpty() || runIdentities.size != scopeDigests.size ||
			runIdentities.distinct().size != runIdentities.size ||
			scopeDigests.distinct().size != scopeDigests.size
		) return corrupt()
		if (dao.entryDeletion(receipt.entryIdentity) != null ||
			dao.entryDeletionReceipt(receipt.entryIdentity) != null
		) return corrupt()

		val existingRunDeletions = dao.deletionGenerations(runIdentities)
		if (existingRunDeletions.distinctBy { it.runIdentity }.size != existingRunDeletions.size ||
			existingRunDeletions.any {
				it.runIdentity !in runIdentities ||
					it.collectedDataEpoch != expectedCollectedDataEpoch ||
					it.generation != FIRST_DELETION_GENERATION
			}
		) return corrupt()
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
		) return corrupt()
		val latestDurableTimeMs = maxOf(
			database.sourceEvidenceStateDao().get()?.updatedAtMs ?: return corrupt(),
			receipt.retainedAtMs,
			existingRunDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
			existingSourceFences.maxOfOrNull { it.deletedAtMs } ?: 0L,
		)
		if (deletedAtMs < latestDurableTimeMs) {
			return EraseNextImportedActivityResult.Blocked(
				SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST,
			)
		}

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
			) abortCorrupt()
		}
		val entryDeletion = ImportedActivityEntryDeletionEntity.create(
			entryIdentity = receipt.entryIdentity,
			collectedDataEpoch = expectedCollectedDataEpoch,
			deletedImportRevision = receipt.latestImportRevision,
			deletedAtMs = deletedAtMs,
		)
		dao.insertEntryDeletion(entryDeletion)
		writeCheckpoint(ImportedActivitySourceEraseCheckpoint.SOURCE_MARKERS_RECORDED)

		val finalRunDeletions = dao.deletionGenerations(runIdentities)
		val finalSourceFences = database.trackingHistoryReadDao().deletionFences(
			sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
			scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
			scopeIdentityDigests = scopeDigests,
		)
		if (finalRunDeletions.size != runIdentities.size ||
			finalSourceFences.size != scopeDigests.size
		) abortCorrupt()
		val nextRevision = Math.addExact(
			database.sourceEvidenceStateDao().get()?.revision ?: abortCorrupt(),
			1L,
		)
		val redactedReceipt = ImportedActivityRetentionReceiptEntity.create(
			entryIdentity = receipt.entryIdentity,
			collectedDataEpoch = expectedCollectedDataEpoch,
			sourceEvidenceRevision = nextRevision,
			retainedFromMs = database.sourceEvidenceStateDao().get()?.retainedFromMs ?: abortCorrupt(),
			retainedAtMs = deletedAtMs,
			latestImportRevision = receipt.latestImportRevision,
			latestContentChecksum = receipt.latestContentChecksum,
			startTimeMs = 0L,
			endTimeMs = 0L,
			receivedAtMs = 0L,
			revisionCount = receipt.revisionCount,
			importReceiptCount = receipt.importReceiptCount,
			runRowCount = receipt.runRowCount,
			zoneEpochRowCount = receipt.zoneEpochRowCount,
			windowRowCount = receipt.windowRowCount,
			fragmentRowCount = receipt.fragmentRowCount,
			runDeletions = finalRunDeletions,
			sourceFences = finalSourceFences,
			markers = markers,
			lineageAuthorityChecksum = receipt.lineageAuthorityChecksum,
		)
		if (dao.updateRetentionReceipt(redactedReceipt) != 1 ||
			database.sourceEvidenceStateDao().incrementRevision(deletedAtMs) != 1
		) abortCorrupt()
		writeCheckpoint(ImportedActivitySourceEraseCheckpoint.RETAINED_RECEIPT_REDACTED)
		val currentState = database.sourceEvidenceStateDao().get() ?: abortCorrupt()
		if (currentState.revision != nextRevision ||
			database.authenticateImportedActivityRetentionBatch(
				currentState,
				listOf(redactedReceipt),
			) != null ||
			dao.retainedHistoryCandidate(receipt.entryIdentity) != null
		) abortCorrupt()
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

sealed interface EraseNextImportedActivityResult {
	data object Complete : EraseNextImportedActivityResult

	data class ErasedLive(
		val importRevisionCount: Int,
		val physicalRunCount: Int,
	) : EraseNextImportedActivityResult

	data class ErasedRetained(
		val physicalRunCount: Int,
		val windowCount: Int,
	) : EraseNextImportedActivityResult

	data class Blocked(
		val reason: SelectedImportedActivityDeletionBlockedReason,
	) : EraseNextImportedActivityResult

	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : EraseNextImportedActivityResult

	data class RetryableFailure(
		val reason: PortableActivityTransferRetryableReason,
	) : EraseNextImportedActivityResult
}

sealed interface ImportedActivityEraseRemainingResult {
	data object Complete : ImportedActivityEraseRemainingResult

	data object Remaining : ImportedActivityEraseRemainingResult

	data class Blocked(
		val reason: SelectedImportedActivityDeletionBlockedReason,
	) : ImportedActivityEraseRemainingResult

	data class Unverifiable(
		val reason: ImportedActivityProductFailure,
	) : ImportedActivityEraseRemainingResult

	data class RetryableFailure(
		val reason: PortableActivityTransferRetryableReason,
	) : ImportedActivityEraseRemainingResult
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
