package com.adsamcik.tracker.stats.api.repository

/** Exact optimistic selection for deleting one authenticated imported Pressure entry. */
data class DeleteImportedPressureEntryRequest(
	val identity: ImportedPressureHistoryIdentity,
	val expectedImportRevision: Long,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedImportRevision > 0L)
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/** Deletes only portable-origin Pressure facts; live capture authority is outside this contract. */
interface DeleteImportedPressureEntry {
	/**
	 * Rechecks the exact imported revision and data epoch before atomically fencing and removing its
	 * Pressure lineage.
	 */
	suspend fun delete(
		request: DeleteImportedPressureEntryRequest,
	): DeleteImportedPressureEntryResult
}

/**
 * Exact imported Pressure deletion outcome, including stale selection, unverifiable scope and
 * retryable failures.
 */
sealed interface DeleteImportedPressureEntryResult {
	/** The exact authenticated entry lineage was fenced and removed atomically. */
	data object Deleted : DeleteImportedPressureEntryResult

	/** This exact entry revision was previously deleted and remains durably fenced. */
	data object AlreadyDeleted : DeleteImportedPressureEntryResult

	/** Neither a retained entry lineage nor an authenticated deletion marker exists. */
	data object NotFound : DeleteImportedPressureEntryResult

	/** The optimistic selection no longer names current local authority. */
	data class StaleSelection(
		val reason: ImportedPressureEntryDeletionStaleReason,
	) : DeleteImportedPressureEntryResult

	/** Retained storage cannot prove one complete deletion scope. */
	data class Unverifiable(
		val reason: ImportedPressureEntryDeletionUnverifiableReason,
	) : DeleteImportedPressureEntryResult

	/** A transient storage race or failure rolled back the complete transaction. */
	data class RetryableFailure(
		val reason: ImportedPressureEntryDeletionRetryableReason,
	) : DeleteImportedPressureEntryResult
}

/**
 * Data-epoch or import-revision changes requiring a new History selection before deletion.
 */
enum class ImportedPressureEntryDeletionStaleReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	IMPORT_REVISION_CHANGED,
}

/**
 * Missing or inconsistent stored evidence, retention or dependency bounds preventing a complete
 * Pressure deletion.
 */
enum class ImportedPressureEntryDeletionUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	PARTIAL_DELETION_STATE,
	DEPENDENCY_OVERFLOW,
	RETENTION_BOUNDARY,
}

/**
 * Storage or concurrent-state failures for which the selected Pressure deletion can be retried.
 */
enum class ImportedPressureEntryDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
