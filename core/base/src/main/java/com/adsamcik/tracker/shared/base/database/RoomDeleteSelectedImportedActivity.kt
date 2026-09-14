package com.adsamcik.tracker.shared.base.database

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedActivityEntryDeletionEntity
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

/** Selected imported-Activity erasure. It never mutates live source or provider authority. */
@Singleton
class RoomDeleteSelectedImportedActivity internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val writeCheckpoint: suspend (ImportedActivityDeletionCheckpoint) -> Unit,
) : DeleteSelectedImportedActivity {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, { currentCoroutineContext().ensureActive() })

	override suspend fun delete(
		request: DeleteSelectedImportedActivityRequest,
	): DeleteSelectedImportedActivityResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { deleteInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedActivityDeletionAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			DeleteSelectedImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: Exception) {
			DeleteSelectedImportedActivityResult.RetryableFailure(
				PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod", "ComplexCondition", "CyclomaticComplexMethod")
	private suspend fun deleteInTransaction(
		request: DeleteSelectedImportedActivityRequest,
	): DeleteSelectedImportedActivityResult {
		writeCheckpoint(ImportedActivityDeletionCheckpoint.TRANSACTION_STARTED)
		val state = storedValue { database.sourceEvidenceStateDao().get() }
			?: unverifiable(ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			blocked(SelectedImportedActivityDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED)
		}
		if (state.revision < 0L || state.deletedSourceEventHighWaterOrdinal < 0L ||
			state.retainedFromMs?.let { it < 0L } == true || state.updatedAtMs < 0L
		) storedCorrupt()

		val dao = database.importedActivityDao()
		val evaluation = storedValue {
			ImportedActivityProductReader(database).selectIdentityInTransaction(
				request.selected.entryIdentity,
			)
		}
		if (evaluation == null) {
			val deletion = storedValue { dao.entryDeletion(request.selected.entryIdentity.value) }
				?: return DeleteSelectedImportedActivityResult.NotFound
			if (deletion.collectedDataEpoch != request.expectedCollectedDataEpoch) storedCorrupt()
			if (deletion.deletedImportRevision != request.selected.importRevision) {
				blocked(SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION)
			}
			return DeleteSelectedImportedActivityResult.AlreadyDeleted(deletion.deletedImportRevision)
		}
		val readable = when (evaluation) {
			is ImportedActivityProductEvaluation.Unverifiable -> unverifiable(evaluation.reason)
			is ImportedActivityProductEvaluation.Readable -> evaluation
		}
		if (readable.candidate.identity != request.selected.entryIdentity.value ||
			readable.candidate.importRevision != request.selected.importRevision ||
			readable.candidate.contentChecksum != request.selected.contentChecksum.value
		) blocked(SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION)
		if (readable.entryDeleted) storedCorrupt()
		if (readable.retentionLimited) {
			blocked(SelectedImportedActivityDeletionBlockedReason.RETENTION_BOUNDARY)
		}

		val latestDurableTimeMs = storedValue {
			maxOf(
				state.updatedAtMs,
				readable.candidate.receivedAtMs,
				dao.receiptsForAdmission(readable.candidate.identity)
					.maxOfOrNull { it.receivedAtMs } ?: 0L,
			)
		}
		val runIdentities = readable.entry.runs.map { it.identity.value }
		val deletionScopesByRun = readable.entry.runs.associate {
			it.identity.value to it.deletionScopeDigest.value
		}
		if (runIdentities.isEmpty() || runIdentities.distinct().size != runIdentities.size ||
			deletionScopesByRun.size != runIdentities.size
		) storedCorrupt()
		val runDeletions = storedValue { dao.deletionGenerations(runIdentities) }
		val sourceDeletions = storedValue {
			database.trackingHistoryReadDao().deletionFences(
				sourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
				purpose = SessionManifestPurposeCode.SESSION_CAPTURE,
				scopeKind = SourceDeletionFenceEntity.SCOPE_LOGICAL_SERVICE_RUN,
				scopeIdentityDigests = deletionScopesByRun.values.toList(),
			)
		}
		if (runDeletions.distinctBy { it.runIdentity }.size != runDeletions.size ||
			runDeletions.any {
				it.runIdentity !in deletionScopesByRun ||
					it.collectedDataEpoch != request.expectedCollectedDataEpoch || it.generation != 1L
			} || sourceDeletions.distinctBy { it.scopeIdentityDigest }.size != sourceDeletions.size ||
			sourceDeletions.any {
				it.scopeIdentityDigest !in deletionScopesByRun.values ||
					it.collectedDataEpoch != request.expectedCollectedDataEpoch || it.fenceGeneration != 1L
			}
		) storedCorrupt()
		val exactLatestDurableTimeMs = maxOf(
			latestDurableTimeMs,
			runDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
			sourceDeletions.maxOfOrNull { it.deletedAtMs } ?: 0L,
		)
		if (request.deletedAtMs < exactLatestDurableTimeMs) {
			blocked(SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST)
		}
		val runDeletionIdentities = runDeletions.mapTo(hashSetOf()) { it.runIdentity }
		val sourceDeletionScopes = sourceDeletions.mapTo(hashSetOf()) { it.scopeIdentityDigest }
		val exactlyDeletedRuns = deletionScopesByRun.filter { (run, scope) ->
			run in runDeletionIdentities || scope in sourceDeletionScopes
		}.keys
		if (readable.deletedRunIdentities != exactlyDeletedRuns) storedCorrupt()
		writeCheckpoint(ImportedActivityDeletionCheckpoint.AUTHORITY_AUTHENTICATED)

		val entryDeletion = ImportedActivityEntryDeletionEntity.create(
			entryIdentity = request.selected.entryIdentity.value,
			collectedDataEpoch = request.expectedCollectedDataEpoch,
			deletedImportRevision = request.selected.importRevision,
			deletedAtMs = request.deletedAtMs,
		)
		dao.insertEntryDeletion(entryDeletion)
		val deletionsByRun = runDeletions.associateBy { it.runIdentity }
		runIdentities.forEach { runIdentity ->
			if (runIdentity !in deletionsByRun) {
				dao.insertDeletionGeneration(
					ImportedActivityDeletionGenerationEntity.create(
						runIdentity = runIdentity,
						collectedDataEpoch = request.expectedCollectedDataEpoch,
						generation = 1L,
						deletedAtMs = request.deletedAtMs,
					),
				)
			}
		}
		writeCheckpoint(ImportedActivityDeletionCheckpoint.TOMBSTONES_RECORDED)

		val expectedRevisionCount = try {
			Math.toIntExact(request.selected.importRevision)
		} catch (_: ArithmeticException) {
			unverifiable(ImportedActivityProductFailure.VALUE_OVERFLOW)
		}
		if (dao.deleteEntryRevisions(request.selected.entryIdentity.value) != expectedRevisionCount) {
			storedCorrupt()
		}
		if (dao.latestHistoryCandidate(request.selected.entryIdentity.value) != null ||
			dao.receiptsForAdmission(request.selected.entryIdentity.value).isNotEmpty() ||
			dao.allRunsForAdmission(request.selected.entryIdentity.value).isNotEmpty() ||
			dao.allZoneEpochsForAdmission(request.selected.entryIdentity.value).isNotEmpty() ||
			dao.allWindowsForAdmission(request.selected.entryIdentity.value).isNotEmpty() ||
			dao.allFragmentsForAdmission(request.selected.entryIdentity.value).isNotEmpty() ||
			dao.entryDeletion(request.selected.entryIdentity.value) != entryDeletion ||
			dao.deletionGenerations(runIdentities).associateBy { it.runIdentity }.keys !=
				runIdentities.toSet()
		) storedCorrupt()
		writeCheckpoint(ImportedActivityDeletionCheckpoint.HIERARCHY_REMOVED)
		return DeleteSelectedImportedActivityResult.Deleted(
			importRevisionCount = expectedRevisionCount,
			physicalRunCount = runIdentities.size,
		)
	}

	private suspend inline fun <T> storedValue(crossinline block: suspend () -> T): T = try {
		block()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (abort: ImportedActivityDeletionAbort) {
		throw abort
	} catch (storage: SQLiteException) {
		throw storage
	} catch (_: IllegalArgumentException) {
		storedCorrupt()
	} catch (_: ArithmeticException) {
		storedCorrupt()
	}

	private fun blocked(reason: SelectedImportedActivityDeletionBlockedReason): Nothing =
		throw ImportedActivityDeletionAbort(DeleteSelectedImportedActivityResult.Blocked(reason))

	private fun storedCorrupt(): Nothing =
		unverifiable(ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE)

	private fun unverifiable(reason: ImportedActivityProductFailure): Nothing =
		throw ImportedActivityDeletionAbort(DeleteSelectedImportedActivityResult.Unverifiable(reason))

	private class ImportedActivityDeletionAbort(
		val result: DeleteSelectedImportedActivityResult,
	) : RuntimeException(null, null, false, false)
}

internal enum class ImportedActivityDeletionCheckpoint {
	TRANSACTION_STARTED,
	AUTHORITY_AUTHENTICATED,
	TOMBSTONES_RECORDED,
	HIERARCHY_REMOVED,
}
