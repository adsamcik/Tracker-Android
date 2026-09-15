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
	suspend fun delete(
		request: DeleteImportedPressureEntryRequest,
	): DeleteImportedPressureEntryResult
}

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

enum class ImportedPressureEntryDeletionStaleReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	IMPORT_REVISION_CHANGED,
}

enum class ImportedPressureEntryDeletionUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	PARTIAL_DELETION_STATE,
	DEPENDENCY_OVERFLOW,
	RETENTION_BOUNDARY,
}

enum class ImportedPressureEntryDeletionRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
