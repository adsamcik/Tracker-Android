package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivity
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivityRequest
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductEvaluation
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductReader
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityTransferRetryableReason
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityIdentity
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityRunDeletionScope
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletion
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionBlockedReason
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionRequest
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionResult
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionRetryableReason
import com.adsamcik.tracker.stats.api.repository.ActivitySelectionDeletionUnverifiableReason
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletion
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionResult
import com.adsamcik.tracker.stats.api.repository.ActivitySessionDeletionRetryableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Resolves and deletes one Activity history selection strictly inside its declared origin. */
@Singleton
internal class RoomActivitySelectionDeletion @Inject constructor(
	private val database: AppDatabase,
	private val localDeletion: ActivitySessionDeletion,
	private val importedDeletion: DeleteSelectedImportedActivity,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ActivitySelectionDeletion {
	override suspend fun delete(
		request: ActivitySelectionDeletionRequest,
	): ActivitySelectionDeletionResult = withContext(ioDispatcher) {
		val selection = try {
			database.withTransaction {
				when (request.origin) {
					ActivityHistoryOrigin.LOCAL -> resolveLocal(request.key)
					ActivityHistoryOrigin.IMPORTED -> resolveImported(request.key)
				}
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			return@withContext ActivitySelectionDeletionResult.RetryableFailure(
				ActivitySelectionDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: ArithmeticException) {
			return@withContext ActivitySelectionDeletionResult.Unverifiable(
				ActivitySelectionDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		} catch (_: IllegalArgumentException) {
			return@withContext ActivitySelectionDeletionResult.Unverifiable(
				ActivitySelectionDeletionUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
		}
		when (selection) {
			ResolvedActivitySelection.NotFound -> ActivitySelectionDeletionResult.NotFound
			is ResolvedActivitySelection.Blocked ->
				ActivitySelectionDeletionResult.Blocked(selection.reason)
			is ResolvedActivitySelection.Unverifiable ->
				ActivitySelectionDeletionResult.Unverifiable(selection.reason)
			is ResolvedActivitySelection.Local ->
				localDeletion.deleteSelectedSession(selection.segmentId).toActionResult()
			is ResolvedActivitySelection.Imported -> importedDeletion.delete(
				DeleteSelectedImportedActivityRequest(
					selected = selection.selected,
					expectedCollectedDataEpoch = selection.collectedDataEpoch,
					deletedAtMs = request.deletedAtMs,
				),
			).toActionResult()
		}
	}

	private suspend fun resolveLocal(key: ActivityHistoryEntryKey): ResolvedActivitySelection {
		var beforeStartTimeMs: Long? = null
		var beforeSegmentId: Long? = null
		var visited = 0
		while (true) {
			val remaining = ActivityCapturedPortableFormatV1.MAX_ENTRIES - visited
			if (remaining <= 0) {
				return ResolvedActivitySelection.Unverifiable(
					ActivitySelectionDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
				)
			}
			val limit = minOf(SELECTION_PAGE_SIZE, remaining)
			val page = database.activityCapturedFactDao().logicalHistoryCandidatePage(
				limit = limit,
				beforeStartTimeMs = beforeStartTimeMs,
				beforeSegmentId = beforeSegmentId,
				activitySourceKind = SourceDestinationOwnerEntity.SOURCE_ACTIVITY,
			)
			if (page.isEmpty()) return ResolvedActivitySelection.NotFound
			page.firstOrNull { candidate ->
				val logicalId = candidate.segment.logicalTrackingId ?: return@firstOrNull false
				ActivityHistoryEntryKey("activity-logical:$logicalId") == key
			}?.let { candidate ->
				return ResolvedActivitySelection.Local(candidate.segment.id)
			}
			visited = Math.addExact(visited, page.size)
			val last = page.last()
			beforeStartTimeMs = last.logicalRecencyStartMs
			beforeSegmentId = last.logicalRecencySegmentId
			if (page.size < limit) return ResolvedActivitySelection.NotFound
		}
	}

	private suspend fun resolveImported(key: ActivityHistoryEntryKey): ResolvedActivitySelection {
		var beforeStartTimeMs: Long? = null
		var beforeIdentity: String? = null
		var visited = 0
		val dao = database.importedActivityDao()
		while (true) {
			val remaining = ActivityCapturedPortableFormatV1.MAX_ENTRIES - visited
			if (remaining <= 0) {
				return ResolvedActivitySelection.Unverifiable(
					ActivitySelectionDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
				)
			}
			val limit = minOf(SELECTION_PAGE_SIZE, remaining)
			val page = dao.recentHistoryCandidatePage(
				limit = limit,
				beforeStartTimeMs = beforeStartTimeMs,
				beforeIdentity = beforeIdentity,
			)
			if (page.isEmpty()) return ResolvedActivitySelection.NotFound
			val match = page.firstOrNull { candidate ->
				ActivityHistoryEntryKey("activity-imported:${candidate.identity}") == key
			}
			if (match != null) {
				val evaluation = ImportedActivityProductReader(database).selectIdentityInTransaction(
					PortableActivityOpaqueIdentity(match.identity),
				) ?: return ResolvedActivitySelection.NotFound
				return evaluation.toResolvedImported()
			}
			visited = Math.addExact(visited, page.size)
			val last = page.last()
			beforeStartTimeMs = last.startTimeMs
			beforeIdentity = last.identity
			if (page.size < limit) return ResolvedActivitySelection.NotFound
		}
	}

	private suspend fun ImportedActivityProductEvaluation.toResolvedImported():
		ResolvedActivitySelection = when (this) {
		is ImportedActivityProductEvaluation.Retained -> ResolvedActivitySelection.Blocked(
			ActivitySelectionDeletionBlockedReason.RETENTION_BOUNDARY,
		)
		is ImportedActivityProductEvaluation.Unverifiable -> ResolvedActivitySelection.Unverifiable(
			reason.toSelectionReason(),
		)
		is ImportedActivityProductEvaluation.Readable -> {
			if (entryDeleted || retentionLimited) {
				ResolvedActivitySelection.Blocked(
					if (retentionLimited) ActivitySelectionDeletionBlockedReason.RETENTION_BOUNDARY
					else ActivitySelectionDeletionBlockedReason.STALE_SELECTION,
				)
			} else {
				val state = database.sourceEvidenceStateDao().get()
					?: return ResolvedActivitySelection.Unverifiable(
						ActivitySelectionDeletionUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE,
					)
				ResolvedActivitySelection.Imported(
					selected = SelectedImportedActivityIdentity(
						entryIdentity = entry.identity,
						importRevision = candidate.importRevision,
						contentChecksum = entry.contentChecksum,
						runDeletionScopes = entry.runs.map { run ->
							SelectedImportedActivityRunDeletionScope(
								runIdentity = run.identity,
								deletionScopeDigest = run.deletionScopeDigest,
							)
						},
						windowIdentities = entry.runs.flatMap { run ->
							run.windows.map { window -> window.identity }
						},
					),
					collectedDataEpoch = state.collectedDataEpoch,
				)
			}
		}
	}

	private companion object {
		const val SELECTION_PAGE_SIZE = 100
	}
}

private sealed interface ResolvedActivitySelection {
	data object NotFound : ResolvedActivitySelection

	data class Local(val segmentId: Long) : ResolvedActivitySelection

	data class Imported(
		val selected: SelectedImportedActivityIdentity,
		val collectedDataEpoch: Long,
	) : ResolvedActivitySelection

	data class Blocked(
		val reason: ActivitySelectionDeletionBlockedReason,
	) : ResolvedActivitySelection

	data class Unverifiable(
		val reason: ActivitySelectionDeletionUnverifiableReason,
	) : ResolvedActivitySelection
}

private fun ActivitySessionDeletionResult.toActionResult(): ActivitySelectionDeletionResult =
	when (this) {
		ActivitySessionDeletionResult.Deleted ->
			ActivitySelectionDeletionResult.Deleted(ActivityHistoryOrigin.LOCAL)
		ActivitySessionDeletionResult.NotFound -> ActivitySelectionDeletionResult.NotFound
		ActivitySessionDeletionResult.BlockedActive -> ActivitySelectionDeletionResult.Blocked(
			ActivitySelectionDeletionBlockedReason.ACTIVE_LOCAL_SESSION,
		)
		ActivitySessionDeletionResult.LegacyUnverifiable ->
			ActivitySelectionDeletionResult.Unverifiable(
				ActivitySelectionDeletionUnverifiableReason.LEGACY_LOCAL_SCOPE,
			)
		is ActivitySessionDeletionResult.UnsupportedScope ->
			ActivitySelectionDeletionResult.Unverifiable(
				ActivitySelectionDeletionUnverifiableReason.UNSUPPORTED_LOCAL_SCOPE,
			)
		is ActivitySessionDeletionResult.RetryableFailure ->
			ActivitySelectionDeletionResult.RetryableFailure(reason.toSelectionReason())
	}

private fun DeleteSelectedImportedActivityResult.toActionResult(): ActivitySelectionDeletionResult =
	when (this) {
		is DeleteSelectedImportedActivityResult.Deleted ->
			ActivitySelectionDeletionResult.Deleted(ActivityHistoryOrigin.IMPORTED)
		is DeleteSelectedImportedActivityResult.AlreadyDeleted ->
			ActivitySelectionDeletionResult.AlreadyDeleted(ActivityHistoryOrigin.IMPORTED)
		DeleteSelectedImportedActivityResult.NotFound -> ActivitySelectionDeletionResult.NotFound
		is DeleteSelectedImportedActivityResult.Blocked ->
			ActivitySelectionDeletionResult.Blocked(reason.toSelectionReason())
		is DeleteSelectedImportedActivityResult.Unverifiable ->
			ActivitySelectionDeletionResult.Unverifiable(reason.toSelectionReason())
		is DeleteSelectedImportedActivityResult.RetryableFailure ->
			ActivitySelectionDeletionResult.RetryableFailure(reason.toSelectionReason())
	}

private fun SelectedImportedActivityDeletionBlockedReason.toSelectionReason() = when (this) {
	SelectedImportedActivityDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED ->
		ActivitySelectionDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED
	SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION ->
		ActivitySelectionDeletionBlockedReason.STALE_SELECTION
	SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST ->
		ActivitySelectionDeletionBlockedReason.STALE_REQUEST
	SelectedImportedActivityDeletionBlockedReason.RETENTION_BOUNDARY ->
		ActivitySelectionDeletionBlockedReason.RETENTION_BOUNDARY
}

private fun ImportedActivityProductFailure.toSelectionReason() = when (this) {
	ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT ->
		ActivitySelectionDeletionUnverifiableReason.ORIGIN_IDENTITY_CONFLICT
	ImportedActivityProductFailure.DEPENDENCY_OVERFLOW,
	ImportedActivityProductFailure.VALUE_OVERFLOW,
	-> ActivitySelectionDeletionUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
	ImportedActivityProductFailure.STALE_COLLECTED_DATA_EPOCH,
	ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	-> ActivitySelectionDeletionUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE
}

private fun PortableActivityTransferRetryableReason.toSelectionReason() = when (this) {
	PortableActivityTransferRetryableReason.CONCURRENT_STATE_CHANGE ->
		ActivitySelectionDeletionRetryableReason.CONCURRENT_STATE_CHANGE
	PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE ->
		ActivitySelectionDeletionRetryableReason.STORAGE_UNAVAILABLE
}

private fun ActivitySessionDeletionRetryableReason.toSelectionReason() = when (this) {
	ActivitySessionDeletionRetryableReason.DATABASE_UNAVAILABLE ->
		ActivitySelectionDeletionRetryableReason.STORAGE_UNAVAILABLE
	ActivitySessionDeletionRetryableReason.CONCURRENT_STATE_CHANGE ->
		ActivitySelectionDeletionRetryableReason.CONCURRENT_STATE_CHANGE
	ActivitySessionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING ->
		ActivitySelectionDeletionRetryableReason.DAY_REPAIR_MATERIALIZING
}
