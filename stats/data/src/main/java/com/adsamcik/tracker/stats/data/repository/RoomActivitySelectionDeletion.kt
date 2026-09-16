package com.adsamcik.tracker.stats.data.repository

import android.database.sqlite.SQLiteException
import androidx.room.withTransaction
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivity
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivityRequest
import com.adsamcik.tracker.shared.base.database.DeleteSelectedImportedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.PortableActivityDeletionScopeDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityDigest
import com.adsamcik.tracker.shared.base.database.PortableActivityOpaqueIdentity
import com.adsamcik.tracker.shared.base.database.PortableActivityTransferRetryableReason
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityIdentity
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityRunDeletionScope
import com.adsamcik.tracker.shared.base.database.data.SourceDestinationOwnerEntity
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryEntryKey
import com.adsamcik.tracker.stats.api.repository.ActivityHistoryOrigin
import com.adsamcik.tracker.stats.api.repository.ActivityHistorySelection
import com.adsamcik.tracker.stats.api.repository.ActivityImportedHistorySelection
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
		try {
			when (val selection = request.selection) {
				is ActivityHistorySelection.Local -> {
					when (val resolved = database.withTransaction { resolveLocal(selection.key) }) {
						ResolvedActivitySelection.NotFound -> ActivitySelectionDeletionResult.NotFound
						is ResolvedActivitySelection.Unverifiable ->
							ActivitySelectionDeletionResult.Unverifiable(resolved.reason)
						is ResolvedActivitySelection.Local ->
							localDeletion.deleteSelectedSession(resolved.segmentId).toActionResult()
					}
				}
				is ActivityHistorySelection.Imported -> importedDeletion.delete(
					selection.selected.toDeletionRequest(request.deletedAtMs),
				).toActionResult()
			}
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			ActivitySelectionDeletionResult.RetryableFailure(
				ActivitySelectionDeletionRetryableReason.STORAGE_UNAVAILABLE,
			)
		} catch (_: ArithmeticException) {
			ActivitySelectionDeletionResult.Unverifiable(
				ActivitySelectionDeletionUnverifiableReason.DEPENDENCY_OVERFLOW,
			)
		} catch (_: IllegalArgumentException) {
			ActivitySelectionDeletionResult.Unverifiable(
				ActivitySelectionDeletionUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE,
			)
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

	private companion object {
		const val SELECTION_PAGE_SIZE = 100
	}
}

private sealed interface ResolvedActivitySelection {
	data object NotFound : ResolvedActivitySelection

	data class Local(val segmentId: Long) : ResolvedActivitySelection

	data class Unverifiable(
		val reason: ActivitySelectionDeletionUnverifiableReason,
	) : ResolvedActivitySelection
}

private fun ActivityImportedHistorySelection.toDeletionRequest(
	deletedAtMs: Long,
) = DeleteSelectedImportedActivityRequest(
	selected = SelectedImportedActivityIdentity(
		entryIdentity = PortableActivityOpaqueIdentity(identity.value),
		importRevision = importRevision,
		contentChecksum = PortableActivityDigest(contentChecksum.value),
		runDeletionScopes = runDeletionScopes.map { run ->
			SelectedImportedActivityRunDeletionScope(
				runIdentity = PortableActivityOpaqueIdentity(run.runIdentity.value),
				deletionScopeDigest = PortableActivityDeletionScopeDigest(
					run.deletionScopeDigest.value,
				),
			)
		},
		windowIdentities = windowIdentities.map { window ->
			PortableActivityOpaqueIdentity(window.value)
		},
	),
	expectedCollectedDataEpoch = readSnapshot.collectedDataEpoch,
	deletedAtMs = deletedAtMs,
	expectedSourceEvidenceRevision = readSnapshot.sourceEvidenceRevision,
)

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
	ImportedActivityProductFailure.TEMPORAL_AUTHORITY_UNAVAILABLE,
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
