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
) {
	val origin: ActivityHistoryOrigin
		get() = selection.origin

	init {
		require(deletedAtMs >= 0L)
	}
}

interface ActivitySelectionDeletion {
	suspend fun delete(
		request: ActivitySelectionDeletionRequest,
	): ActivitySelectionDeletionResult
}

sealed interface ActivitySelectionDeletionResult {
	data class Deleted(
		val origin: ActivityHistoryOrigin,
	) : ActivitySelectionDeletionResult

	data class AlreadyDeleted(
		val origin: ActivityHistoryOrigin,
	) : ActivitySelectionDeletionResult

	data object NotFound : ActivitySelectionDeletionResult

	data class Blocked(
		val reason: ActivitySelectionDeletionBlockedReason,
	) : ActivitySelectionDeletionResult

	data class Unverifiable(
		val reason: ActivitySelectionDeletionUnverifiableReason,
	) : ActivitySelectionDeletionResult

	data class RetryableFailure(
		val reason: ActivitySelectionDeletionRetryableReason,
	) : ActivitySelectionDeletionResult
}

enum class ActivitySelectionDeletionBlockedReason {
	ACTIVE_LOCAL_SESSION,
	STALE_SELECTION,
	RETENTION_BOUNDARY,
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_REQUEST,
}

enum class ActivitySelectionDeletionUnverifiableReason {
	UNSUPPORTED_LOCAL_SCOPE,
	LEGACY_LOCAL_SCOPE,
	IMPORTED_EVIDENCE_UNVERIFIABLE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
}

enum class ActivitySelectionDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
	DAY_REPAIR_MATERIALIZING,
}
