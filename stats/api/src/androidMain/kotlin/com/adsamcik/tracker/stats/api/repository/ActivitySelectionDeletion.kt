package com.adsamcik.tracker.stats.api.repository

/**
 * Product selection supplied by Activity history.
 *
 * [selection] is mandatory mutation authority. Imported selections carry the exact source-issued
 * revision, checksum, hierarchy, and read snapshot; implementations must never reconstruct them
 * from a presentation key.
 */
data class ActivitySelectionDeletionRequest(
	val selection: ActivityHistorySelection,
	val deletedAtMs: Long,
	val expectedCollectedDataEpoch: Long? = null,
) {
	val origin: ActivityHistoryOrigin
		get() = selection.origin

	init {
		require(deletedAtMs >= 0L)
		require(expectedCollectedDataEpoch == null || expectedCollectedDataEpoch >= 0L)
	}
}

/**
 * Deletes the exact local or imported Activity selection issued by History, preserving unrelated
 * data.
 */
interface ActivitySelectionDeletion {
	/**
	 * Deletes only the source-issued selection, rechecking its epoch/revision and applicable
	 * retention or live-session boundaries.
	 */
	suspend fun delete(
		request: ActivitySelectionDeletionRequest,
	): ActivitySelectionDeletionResult
}

/**
 * Outcome of deleting one Activity history selection; absence, stale authority and storage failure
 * remain distinct.
 */
sealed interface ActivitySelectionDeletionResult {
	/**
	 * The selected Activity record was deleted from its local or imported origin.
	 */
	data class Deleted(
		val origin: ActivityHistoryOrigin,
	) : ActivitySelectionDeletionResult

	/**
	 * The selected Activity origin has already been deleted.
	 */
	data class AlreadyDeleted(
		val origin: ActivityHistoryOrigin,
	) : ActivitySelectionDeletionResult

	/**
	 * No matching Activity selection remains in saved data.
	 */
	data object NotFound : ActivitySelectionDeletionResult

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: ActivitySelectionDeletionBlockedReason,
	) : ActivitySelectionDeletionResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: ActivitySelectionDeletionUnverifiableReason,
	) : ActivitySelectionDeletionResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: ActivitySelectionDeletionRetryableReason,
	) : ActivitySelectionDeletionResult
}

/**
 * Changes in active-session, selection, retention or data-epoch state that prevent the selected
 * deletion.
 */
enum class ActivitySelectionDeletionBlockedReason {
	ACTIVE_LOCAL_SESSION,
	STALE_SELECTION,
	RETENTION_BOUNDARY,
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_REQUEST,
}

/**
 * Missing or conflicting scope evidence and dependency limits that prevent identifying the
 * complete deletion scope.
 */
enum class ActivitySelectionDeletionUnverifiableReason {
	UNSUPPORTED_LOCAL_SCOPE,
	LEGACY_LOCAL_SCOPE,
	IMPORTED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
}

/**
 * Temporary storage, concurrent-change or day-repair conditions for which the same deletion may be
 * retried.
 */
enum class ActivitySelectionDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
	DAY_REPAIR_MATERIALIZING,
}
