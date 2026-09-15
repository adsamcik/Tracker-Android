package com.adsamcik.tracker.stats.api.repository

/** Exact optimistic deletion of one source-issued Wi-Fi history selection. */
data class DeleteSelectedWifiHistoryRequest(
	val selection: WifiHistorySelection,
	val expectedCollectedDataEpoch: Long,
	val deletedAtMs: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(deletedAtMs >= 0L)
	}
}

interface DeleteSelectedWifiHistory {
	suspend fun delete(
		request: DeleteSelectedWifiHistoryRequest,
	): DeleteSelectedWifiHistoryResult
}

/** Authenticates one retained selected-deletion shell inside the caller's Room transaction. */
fun interface WifiDeletedHistoryReader {
	suspend fun readDeletedInTransaction(
		selection: WifiHistorySelection,
	): WifiDeletedHistoryResult
}

sealed interface WifiDeletedHistoryResult {
	data object NotDeleted : WifiDeletedHistoryResult
	data class Deleted(val entry: WifiHistoryEntry) : WifiDeletedHistoryResult
	data class Unverifiable(val reason: WifiHistoryDeletionUnverifiableReason) :
		WifiDeletedHistoryResult
}

sealed interface DeleteSelectedWifiHistoryResult {
	data class Deleted(
		val origin: WifiHistoryOrigin,
		val physicalRunCount: Int,
		val observationIdentityCount: Int,
	) : DeleteSelectedWifiHistoryResult {
		init {
			require(physicalRunCount > 0)
			require(observationIdentityCount >= 0)
		}
	}

	data class AlreadyDeleted(val origin: WifiHistoryOrigin) : DeleteSelectedWifiHistoryResult
	data object NotFound : DeleteSelectedWifiHistoryResult
	data class Blocked(val reason: WifiHistoryDeletionBlockedReason) : DeleteSelectedWifiHistoryResult
	data class Unverifiable(val reason: WifiHistoryDeletionUnverifiableReason) :
		DeleteSelectedWifiHistoryResult
	data class RetryableFailure(val reason: WifiHistoryDeletionRetryableReason) :
		DeleteSelectedWifiHistoryResult
}

enum class WifiHistoryDeletionBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	STALE_SELECTION,
	STALE_REQUEST,
	ACTIVE_CAPTURE,
	MATERIALIZING,
	RETENTION_BOUNDARY,
	MIXED_CAPTURE_SCOPE,
	ORIGINAL_SCOPE_MISSING,
	ORIGINAL_SCOPE_REBOUND,
}

enum class WifiHistoryDeletionUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	PARTIAL_DELETION_STATE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
}

enum class WifiHistoryDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
