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

/**
 * Deletes the exact local or imported Wi-Fi selection issued by the History reader.
 */
interface DeleteSelectedWifiHistory {
	/**
	 * Deletes only the source-issued Wi-Fi selection after rechecking its revision, epoch and
	 * applicable capture/retention boundaries.
	 */
	suspend fun delete(
		request: DeleteSelectedWifiHistoryRequest,
	): DeleteSelectedWifiHistoryResult
}

/** Authenticates one retained selected-deletion shell inside the caller's Room transaction. */
fun interface WifiDeletedHistoryReader {
	/**
	 * Reads the exact retained deletion witness inside the caller's Room transaction without
	 * recreating deleted payload.
	 */
	suspend fun readDeletedInTransaction(
		selection: WifiHistorySelection,
	): WifiDeletedHistoryResult
}

/**
 * Saved deletion-witness lookup outcome, separate from a currently visible Wi-Fi entry.
 */
sealed interface WifiDeletedHistoryResult {
	/**
	 * No verified deletion witness matches the selected Wi-Fi entry.
	 */
	data object NotDeleted : WifiDeletedHistoryResult
	/**
	 * The source can present the selected entry as deleted from its retained deletion witness.
	 */
	data class Deleted(val entry: WifiHistoryEntry) : WifiDeletedHistoryResult
	/**
	 * The source cannot verify the complete selected scope or stored lineage needed by this
	 * operation.
	 */
	data class Unverifiable(val reason: WifiHistoryDeletionUnverifiableReason) :
		WifiDeletedHistoryResult
}

/**
 * Selected Wi-Fi erase outcome with removed counts, prior deletion, missing data and specific
 * failure states.
 */
sealed interface DeleteSelectedWifiHistoryResult {
	/**
	 * The exact selected source scope was removed; any counts report the original scope that was
	 * fenced.
	 */
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

	/**
	 * The exact selected source scope was previously deleted and remains fenced against replay.
	 */
	data class AlreadyDeleted(val origin: WifiHistoryOrigin) : DeleteSelectedWifiHistoryResult
	/**
	 * No matching saved source entry or selection remains.
	 */
	data object NotFound : DeleteSelectedWifiHistoryResult
	/**
	 * Current capture, selection or privacy state prevents the requested source mutation.
	 */
	data class Blocked(val reason: WifiHistoryDeletionBlockedReason) : DeleteSelectedWifiHistoryResult
	/**
	 * The source cannot verify the complete selected scope or stored lineage needed by this
	 * operation.
	 */
	data class Unverifiable(val reason: WifiHistoryDeletionUnverifiableReason) :
		DeleteSelectedWifiHistoryResult
	/**
	 * A transient storage or concurrent-state failure prevents completion and permits retry.
	 */
	data class RetryableFailure(val reason: WifiHistoryDeletionRetryableReason) :
		DeleteSelectedWifiHistoryResult
}

/**
 * Live capture, stale selection/epoch, retention or incomplete scope preventing selected Wi-Fi
 * deletion.
 */
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

/**
 * Missing or inconsistent source, scope, writer or fact evidence preventing complete Wi-Fi
 * deletion.
 */
enum class WifiHistoryDeletionUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	PARTIAL_DELETION_STATE,
	ORIGIN_IDENTITY_CONFLICT,
	DEPENDENCY_OVERFLOW,
}

/**
 * Concurrent-state or storage failures allowing retry of the selected Wi-Fi deletion.
 */
enum class WifiHistoryDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
