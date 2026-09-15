package com.adsamcik.tracker.tracker.source.activity

import android.database.sqlite.SQLiteException
import com.adsamcik.tracker.shared.base.database.ActivityCapturedPortableFormatV1
import com.adsamcik.tracker.shared.base.database.ActivityCapturedSourceDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.ActivityCapturedSourceDeletionResult
import com.adsamcik.tracker.shared.base.database.AppDatabase
import com.adsamcik.tracker.shared.base.database.EraseNextImportedActivityResult
import com.adsamcik.tracker.shared.base.database.ImportedActivityEraseRemainingResult
import com.adsamcik.tracker.shared.base.database.ImportedActivityProductFailure
import com.adsamcik.tracker.shared.base.database.PortableActivityTransferRetryableReason
import com.adsamcik.tracker.shared.base.database.RoomEraseNextImportedActivity
import com.adsamcik.tracker.shared.base.database.SelectedImportedActivityDeletionBlockedReason
import com.adsamcik.tracker.shared.base.database.deleteCapturedActivityFactsAfterConsentReset
import com.adsamcik.tracker.shared.base.di.IoDispatcher
import com.adsamcik.tracker.stats.api.repository.ActivitySourceErase
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseBlockedReason
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseRequest
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseResult
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseRetryableReason
import com.adsamcik.tracker.stats.api.repository.ActivitySourceEraseUnverifiableReason
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

internal fun interface ActivityCapturedLocalErase {
	suspend fun erase(request: ActivitySourceEraseRequest): ActivityCapturedSourceDeletionResult
}

@Singleton
internal class RoomActivityCapturedLocalErase @Inject constructor(
	private val database: AppDatabase,
) : ActivityCapturedLocalErase {
	override suspend fun erase(
		request: ActivitySourceEraseRequest,
	): ActivityCapturedSourceDeletionResult =
		database.deleteCapturedActivityFactsAfterConsentReset(
			expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
			expectedRevokedConsentEpoch = request.expectedRevokedConsentEpoch,
			deletedAtMs = request.deletedAtMs,
		)
}

/**
 * Source-wide Activity privacy action.
 *
 * Local revocation and quiescence are proven before imported mutation. Imported continuation is
 * one lineage per Room transaction, with each live or retained lineage durably fenced before its
 * payload or timing shell is removed from product visibility.
 */
@Singleton
internal class RoomActivitySourceErase @Inject constructor(
	private val localErase: ActivityCapturedLocalErase,
	private val importedErase: RoomEraseNextImportedActivity,
	@IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ActivitySourceErase {
	override suspend fun erase(
		request: ActivitySourceEraseRequest,
	): ActivitySourceEraseResult = withContext(ioDispatcher) {
		val local = try {
			localErase.erase(request)
		} catch (cancelled: CancellationException) {
			throw cancelled
		} catch (_: SQLiteException) {
			return@withContext ActivitySourceEraseResult.RetryableFailure(
				ActivitySourceEraseRetryableReason.STORAGE_UNAVAILABLE,
			)
		}
		val localCounts = when (local) {
			is ActivityCapturedSourceDeletionResult.Deleted -> LocalEraseCounts(
				logicalWindowCount = local.logicalWindowCount,
				revisionCount = local.revisionCount,
				registrationPlanCount = local.registrationPlanCount,
				fencedServiceRunCount = local.fencedServiceRunCount,
			)
			ActivityCapturedSourceDeletionResult.AlreadyDeleted -> LocalEraseCounts.EMPTY
			is ActivityCapturedSourceDeletionResult.Blocked ->
				return@withContext local.reason.toSourceEraseFailure()
		}

		var liveEntries = 0
		var retainedEntries = 0
		var physicalRuns = 0
		repeat(ActivityCapturedPortableFormatV1.MAX_ENTRIES) {
			when (val next = importedErase.eraseNext(
				expectedCollectedDataEpoch = request.expectedCollectedDataEpoch,
				deletedAtMs = request.deletedAtMs,
			)) {
				EraseNextImportedActivityResult.Complete -> return@withContext completedResult(
					localCounts,
					liveEntries,
					retainedEntries,
					physicalRuns,
				)
				is EraseNextImportedActivityResult.ErasedLive -> {
					liveEntries = Math.addExact(liveEntries, 1)
					physicalRuns = Math.addExact(physicalRuns, next.physicalRunCount)
				}
				is EraseNextImportedActivityResult.ErasedRetained -> {
					retainedEntries = Math.addExact(retainedEntries, 1)
					physicalRuns = Math.addExact(physicalRuns, next.physicalRunCount)
				}
				is EraseNextImportedActivityResult.Blocked -> return@withContext
					ActivitySourceEraseResult.Blocked(
						reason = next.reason.toPublicReason(),
						localProductErasedBeforeFailure = localCounts != LocalEraseCounts.EMPTY,
						importedEntriesErasedBeforeFailure = Math.addExact(
							liveEntries,
							retainedEntries,
						),
					)
				is EraseNextImportedActivityResult.Unverifiable -> return@withContext
					ActivitySourceEraseResult.Unverifiable(
						reason = next.reason.toPublicReason(),
						localProductErasedBeforeFailure = localCounts != LocalEraseCounts.EMPTY,
						importedEntriesErasedBeforeFailure = Math.addExact(liveEntries, retainedEntries),
					)
				is EraseNextImportedActivityResult.RetryableFailure -> return@withContext
					ActivitySourceEraseResult.RetryableFailure(
						reason = next.reason.toPublicReason(),
						localProductErasedBeforeFailure = localCounts != LocalEraseCounts.EMPTY,
						importedEntriesErasedBeforeFailure = Math.addExact(liveEntries, retainedEntries),
					)
			}
		}
		when (val remaining = importedErase.probeRemaining(request.expectedCollectedDataEpoch)) {
			ImportedActivityEraseRemainingResult.Complete -> completedResult(
				localCounts,
				liveEntries,
				retainedEntries,
				physicalRuns,
			)
			ImportedActivityEraseRemainingResult.Remaining ->
				ActivitySourceEraseResult.ContinuationRequired(
					localProductErasedBeforeContinuation = localCounts != LocalEraseCounts.EMPTY,
					importedLiveEntryCount = liveEntries,
					importedRetainedEntryCount = retainedEntries,
					importedPhysicalRunCount = physicalRuns,
				)
			is ImportedActivityEraseRemainingResult.Blocked -> ActivitySourceEraseResult.Blocked(
				reason = remaining.reason.toPublicReason(),
				localProductErasedBeforeFailure = localCounts != LocalEraseCounts.EMPTY,
				importedEntriesErasedBeforeFailure = Math.addExact(liveEntries, retainedEntries),
			)
			is ImportedActivityEraseRemainingResult.Unverifiable ->
				ActivitySourceEraseResult.Unverifiable(
					reason = remaining.reason.toPublicReason(),
					localProductErasedBeforeFailure = localCounts != LocalEraseCounts.EMPTY,
					importedEntriesErasedBeforeFailure = Math.addExact(
						liveEntries,
						retainedEntries,
					),
				)
			is ImportedActivityEraseRemainingResult.RetryableFailure ->
				ActivitySourceEraseResult.RetryableFailure(
					reason = remaining.reason.toPublicReason(),
					localProductErasedBeforeFailure = localCounts != LocalEraseCounts.EMPTY,
					importedEntriesErasedBeforeFailure = Math.addExact(
						liveEntries,
						retainedEntries,
					),
				)
		}
	}
}

private data class LocalEraseCounts(
	val logicalWindowCount: Int,
	val revisionCount: Int,
	val registrationPlanCount: Int,
	val fencedServiceRunCount: Int,
) {
	companion object {
		val EMPTY = LocalEraseCounts(0, 0, 0, 0)
	}
}

private fun completedResult(
	local: LocalEraseCounts,
	importedLiveEntries: Int,
	importedRetainedEntries: Int,
	importedPhysicalRuns: Int,
): ActivitySourceEraseResult {
	val importedCount = Math.addExact(importedLiveEntries, importedRetainedEntries)
	return if (local == LocalEraseCounts.EMPTY && importedCount == 0) {
		ActivitySourceEraseResult.AlreadyErased
	} else {
		ActivitySourceEraseResult.Erased(
			localLogicalWindowCount = local.logicalWindowCount,
			localRevisionCount = local.revisionCount,
			localRegistrationPlanCount = local.registrationPlanCount,
			localFencedServiceRunCount = local.fencedServiceRunCount,
			importedLiveEntryCount = importedLiveEntries,
			importedRetainedEntryCount = importedRetainedEntries,
			importedPhysicalRunCount = importedPhysicalRuns,
		)
	}
}

private fun ActivityCapturedSourceDeletionBlockedReason.toSourceEraseFailure():
	ActivitySourceEraseResult = when (this) {
	ActivityCapturedSourceDeletionBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED ->
		ActivitySourceEraseResult.Blocked(
			ActivitySourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED,
		)
	ActivityCapturedSourceDeletionBlockedReason.DESTINATION_OWNER_CHANGED ->
		ActivitySourceEraseResult.Blocked(
			ActivitySourceEraseBlockedReason.DESTINATION_OWNER_CHANGED,
		)
	ActivityCapturedSourceDeletionBlockedReason.POLICY_AUTHORITY_UNAVAILABLE ->
		ActivitySourceEraseResult.Blocked(
			ActivitySourceEraseBlockedReason.POLICY_AUTHORITY_UNAVAILABLE,
		)
	ActivityCapturedSourceDeletionBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE ->
		ActivitySourceEraseResult.Blocked(
			ActivitySourceEraseBlockedReason.CAPTURE_CONSENT_STILL_ELIGIBLE,
		)
	ActivityCapturedSourceDeletionBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED ->
		ActivitySourceEraseResult.Blocked(
			ActivitySourceEraseBlockedReason.CAPTURE_DEMAND_NOT_QUIESCED,
		)
	ActivityCapturedSourceDeletionBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED ->
		ActivitySourceEraseResult.Blocked(
			ActivitySourceEraseBlockedReason.CAPTURE_PROVIDER_NOT_QUIESCED,
		)
	ActivityCapturedSourceDeletionBlockedReason.DELETION_FENCE_CONFLICT ->
		ActivitySourceEraseResult.Blocked(
			ActivitySourceEraseBlockedReason.DELETION_FENCE_CONFLICT,
		)
	ActivityCapturedSourceDeletionBlockedReason.STALE_REQUEST ->
		ActivitySourceEraseResult.Blocked(ActivitySourceEraseBlockedReason.STALE_REQUEST)
	ActivityCapturedSourceDeletionBlockedReason.UNRECOGNIZED_PAYLOAD_PRESENT ->
		ActivitySourceEraseResult.Unverifiable(
			ActivitySourceEraseUnverifiableReason.UNRECOGNIZED_LOCAL_PAYLOAD,
		)
	ActivityCapturedSourceDeletionBlockedReason.MAINTENANCE_BOUND_EXCEEDED ->
		ActivitySourceEraseResult.Unverifiable(
			ActivitySourceEraseUnverifiableReason.MAINTENANCE_BOUND_EXCEEDED,
		)
	ActivityCapturedSourceDeletionBlockedReason.FACT_AUTHORITY_UNVERIFIABLE ->
		ActivitySourceEraseResult.Unverifiable(
			ActivitySourceEraseUnverifiableReason.LOCAL_FACT_AUTHORITY_UNVERIFIABLE,
		)
}

private fun SelectedImportedActivityDeletionBlockedReason.toPublicReason() = when (this) {
	SelectedImportedActivityDeletionBlockedReason.COLLECTED_DATA_EPOCH_CHANGED ->
		ActivitySourceEraseBlockedReason.SOURCE_EVIDENCE_AUTHORITY_CHANGED
	SelectedImportedActivityDeletionBlockedReason.STALE_SELECTION ->
		ActivitySourceEraseBlockedReason.IMPORTED_SELECTION_CHANGED
	SelectedImportedActivityDeletionBlockedReason.STALE_REQUEST ->
		ActivitySourceEraseBlockedReason.STALE_REQUEST
	SelectedImportedActivityDeletionBlockedReason.RETENTION_BOUNDARY ->
		ActivitySourceEraseBlockedReason.IMPORTED_RETENTION_BOUNDARY_CHANGED
}

private fun ImportedActivityProductFailure.toPublicReason() = when (this) {
	ImportedActivityProductFailure.SOURCE_EVIDENCE_STATE_MISSING,
	ImportedActivityProductFailure.STALE_COLLECTED_DATA_EPOCH,
	ImportedActivityProductFailure.STORED_EVIDENCE_UNVERIFIABLE,
	-> ActivitySourceEraseUnverifiableReason.IMPORTED_EVIDENCE_UNVERIFIABLE
	ImportedActivityProductFailure.ORIGIN_IDENTITY_CONFLICT ->
		ActivitySourceEraseUnverifiableReason.IMPORTED_ORIGIN_IDENTITY_CONFLICT
	ImportedActivityProductFailure.DEPENDENCY_OVERFLOW ->
		ActivitySourceEraseUnverifiableReason.DEPENDENCY_OVERFLOW
	ImportedActivityProductFailure.VALUE_OVERFLOW ->
		ActivitySourceEraseUnverifiableReason.VALUE_OVERFLOW
}

private fun PortableActivityTransferRetryableReason.toPublicReason() = when (this) {
	PortableActivityTransferRetryableReason.CONCURRENT_STATE_CHANGE ->
		ActivitySourceEraseRetryableReason.CONCURRENT_STATE_CHANGE
	PortableActivityTransferRetryableReason.STORAGE_UNAVAILABLE ->
		ActivitySourceEraseRetryableReason.STORAGE_UNAVAILABLE
}
