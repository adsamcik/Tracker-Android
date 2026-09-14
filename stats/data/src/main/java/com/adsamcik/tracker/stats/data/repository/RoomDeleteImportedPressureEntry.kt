package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.dao.ImportedPressureDao
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureDeletionGenerationEntity
import com.adsamcik.tracker.shared.base.database.data.ImportedPressureEntryDeletionEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntry
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryRequest
import com.adsamcik.tracker.stats.api.repository.DeleteImportedPressureEntryResult
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionStaleReason
import com.adsamcik.tracker.stats.api.repository.ImportedPressureEntryDeletionUnverifiableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact source-local deletion for one authenticated portable-origin Pressure entry. */
@Singleton
internal class RoomDeleteImportedPressureEntry internal constructor(
	private val database: AppDatabase,
	private val ioDispatcher: CoroutineDispatcher,
	private val wallTimeMsProvider: () -> Long,
	private val writeCheckpoint: suspend (ImportedPressureDeletionWriteCheckpoint) -> Unit,
) : DeleteImportedPressureEntry {
	@Inject
	constructor(
		database: AppDatabase,
		@IoDispatcher ioDispatcher: CoroutineDispatcher,
	) : this(database, ioDispatcher, System::currentTimeMillis, {})

	override suspend fun delete(
		request: DeleteImportedPressureEntryRequest,
	): DeleteImportedPressureEntryResult = withContext(ioDispatcher) {
		try {
			database.withTransaction { deleteInTransaction(request) }
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (abort: ImportedPressureDeletionAbort) {
			abort.result
		} catch (_: SQLiteConstraintException) {
			DeleteImportedPressureEntryResult.RetryableFailure(
				ImportedPressureEntryDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
			)
		} catch (_: SQLiteException) {
			DeleteImportedPressureEntryResult.RetryableFailure(
				ImportedPressureEntryDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: IllegalArgumentException) {
			unverifiableResult(
				ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
			)
		} catch (_: Exception) {
			DeleteImportedPressureEntryResult.RetryableFailure(
				ImportedPressureEntryDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
	}

	@Suppress("LongMethod")
	private suspend fun deleteInTransaction(
		request: DeleteImportedPressureEntryRequest,
	): DeleteImportedPressureEntryResult {
		val dao = database.importedPressureDao()
		val state = database.sourceEvidenceStateDao().get() ?: unverifiable(
			ImportedPressureEntryDeletionUnverifiableReason.SOURCE_EVIDENCE_STATE_MISSING,
		)
		if (state.collectedDataEpoch != request.expectedCollectedDataEpoch) {
			stale(ImportedPressureEntryDeletionStaleReason.COLLECTED_DATA_EPOCH_CHANGED)
		}

		val identity = request.identity.value
		val headers = dao.entryRevisionsForAdmission(identity)
		val receipts = dao.receiptsForAdmission(identity)
		val runs = dao.allRunsForAdmission(identity)
		val windows = dao.allWindowsForAdmission(identity)
		val entryMarker = dao.entryDeletion(identity)
		if (entryMarker != null) {
			if (headers.isNotEmpty() || receipts.isNotEmpty() || runs.isNotEmpty() || windows.isNotEmpty()) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
			}
			if (entryMarker.collectedDataEpoch != request.expectedCollectedDataEpoch) {
				unverifiable(
					ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE,
				)
			}
			if (entryMarker.deletedImportRevision != request.expectedImportRevision) {
				stale(ImportedPressureEntryDeletionStaleReason.IMPORT_REVISION_CHANGED)
			}
			return DeleteImportedPressureEntryResult.AlreadyDeleted
		}
		if (headers.isEmpty()) {
			if (receipts.isNotEmpty() || runs.isNotEmpty() || windows.isNotEmpty()) {
				unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
			}
			return DeleteImportedPressureEntryResult.NotFound
		}
		if (headers.any { it.collectedDataEpoch != request.expectedCollectedDataEpoch }) {
			stale(ImportedPressureEntryDeletionStaleReason.COLLECTED_DATA_EPOCH_CHANGED)
		}

		val lineage = try {
			ImportedPressureLineageAuthenticator.authenticate(
				identity = identity,
				expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
				headers = headers,
				receipts = receipts,
				runs = runs,
				windows = windows,
			)
		} catch (failure: ImportedPressureLineageFailure) {
			unverifiable(failure.reason.toDeletionReason())
		}
		val latest = requireNotNull(lineage.latest)
		if (latest.header.importRevision != request.expectedImportRevision) {
			stale(ImportedPressureEntryDeletionStaleReason.IMPORT_REVISION_CHANGED)
		}
		val runIdentities = lineage.revisions.flatMap { revision ->
			revision.entry.runs.map { run -> run.identity.value }
		}.distinct()
		val existingMarkers = dao.deletionGenerationsForHistory(runIdentities)
		if (existingMarkers.isNotEmpty()) {
			unverifiable(ImportedPressureEntryDeletionUnverifiableReason.PARTIAL_DELETION_STATE)
		}

		val deletedAtMs = wallTimeMsProvider().coerceAtLeast(0L)
		for (runIdentity in runIdentities) {
			dao.insertDeletionGeneration(
				ImportedPressureDeletionGenerationEntity.create(
					runIdentity = runIdentity,
					collectedDataEpoch = request.expectedCollectedDataEpoch,
					generation = 1L,
					deletedAtMs = deletedAtMs,
				),
			)
			writeCheckpoint(ImportedPressureDeletionWriteCheckpoint.RUN_TOMBSTONE_INSERTED)
		}
		dao.insertEntryDeletion(
			ImportedPressureEntryDeletionEntity.create(
				entryIdentity = identity,
				collectedDataEpoch = request.expectedCollectedDataEpoch,
				deletedImportRevision = request.expectedImportRevision,
				deletedAtMs = deletedAtMs,
			),
		)
		writeCheckpoint(ImportedPressureDeletionWriteCheckpoint.ENTRY_TOMBSTONE_INSERTED)
		currentCoroutineContext().ensureActive()
		lineage.revisions.asReversed().forEach { revision ->
			if (dao.deleteEntryRevision(identity, revision.header.importRevision) != 1) {
				concurrentMutation()
			}
		}
		if (dao.entryRevisionsForAdmission(identity).isNotEmpty() ||
			dao.receiptsForAdmission(identity).isNotEmpty() ||
			dao.allRunsForAdmission(identity).isNotEmpty() ||
			dao.allWindowsForAdmission(identity).isNotEmpty()
		) concurrentMutation()
		writeCheckpoint(ImportedPressureDeletionWriteCheckpoint.HIERARCHY_REMOVED)
		return DeleteImportedPressureEntryResult.Deleted
	}

	private fun stale(reason: ImportedPressureEntryDeletionStaleReason): Nothing =
		throw ImportedPressureDeletionAbort(DeleteImportedPressureEntryResult.StaleSelection(reason))

	private fun unverifiable(reason: ImportedPressureEntryDeletionUnverifiableReason): Nothing =
		throw ImportedPressureDeletionAbort(unverifiableResult(reason))

	private fun concurrentMutation(): Nothing = throw ImportedPressureDeletionAbort(
		DeleteImportedPressureEntryResult.RetryableFailure(
			ImportedPressureEntryDeletionRetryableReason.CONCURRENT_STATE_CHANGE,
		),
	)

	private class ImportedPressureDeletionAbort(
		val result: DeleteImportedPressureEntryResult,
	) : RuntimeException(null, null, false, false)
}

internal enum class ImportedPressureDeletionWriteCheckpoint {
	RUN_TOMBSTONE_INSERTED,
	ENTRY_TOMBSTONE_INSERTED,
	HIERARCHY_REMOVED,
}

private fun unverifiableResult(
	reason: ImportedPressureEntryDeletionUnverifiableReason,
) = DeleteImportedPressureEntryResult.Unverifiable(reason)

private fun ImportedPressureLineageFailureReason.toDeletionReason():
	ImportedPressureEntryDeletionUnverifiableReason = when (this) {
	ImportedPressureLineageFailureReason.STORED_EVIDENCE_UNVERIFIABLE ->
		ImportedPressureEntryDeletionUnverifiableReason.STORED_EVIDENCE_UNVERIFIABLE
	ImportedPressureLineageFailureReason.DEPENDENCY_OVERFLOW,
	ImportedPressureLineageFailureReason.RUN_OVERFLOW,
	ImportedPressureLineageFailureReason.WINDOW_OVERFLOW,
	ImportedPressureLineageFailureReason.TOTAL_WINDOW_OVERFLOW,
	ImportedPressureLineageFailureReason.REVISION_OVERFLOW ->
		ImportedPressureEntryDeletionUnverifiableReason.DEPENDENCY_OVERFLOW
}
