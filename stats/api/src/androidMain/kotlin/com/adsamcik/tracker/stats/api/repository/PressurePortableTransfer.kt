package com.adsamcik.tracker.stats.api.repository

/** Finite half-open wall-time selection for a source-local Pressure export. */
data class ExportPortablePressureRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
) {
	init {
		require(fromInclusiveMs >= 0L)
		require(toExclusiveMs > fromInclusiveMs)
	}
}

/** Exact saved records for a session bundle; a native seed includes its original replacement group. */
data class ExportSelectedPortablePressureRequest(
	val nativeSegmentIds: List<Long>,
	val importedEntryIdentities: List<PortablePressureOpaqueIdentity>,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
		require(nativeSegmentIds.all { it > 0L })
		require(nativeSegmentIds.size <= PressurePortableFormatV1.MAX_TOTAL_RUNS)
		require(importedEntryIdentities.size <= PressurePortableFormatV1.MAX_ENTRIES)
		require(nativeSegmentIds.isNotEmpty() || importedEntryIdentities.isNotEmpty())
	}
}

/** Receives one fully authenticated logical Pressure entry. */
fun interface PortablePressureEntrySink {
	/**
	 * Accepts one prepared Pressure entry; the caller owns staging and discarding bytes if the
	 * enclosing export fails.
	 */
	suspend fun emit(entry: PortablePressureEntryV1)
}

/** Read-only export contract. Implementations must never acquire or retain provider demand. */
interface ExportPortablePressure {
	/**
	 * Authenticates and bounds the complete Room snapshot before the first external sink call.
	 * A range boundary may select a logical entry, but it never splits its replacement-run members.
	 */
	suspend fun export(
		request: ExportPortablePressureRequest,
		sink: PortablePressureEntrySink,
	): ExportPortablePressureResult

	/**
	 * Selects only the named native groups/imported entries, without a date fallback. Missing or
	 * changed selected data fails the transfer; unrelated sources do not invalidate this snapshot.
	 * The sink remains responsible for discarding staged bytes when export does not succeed.
	 */
	suspend fun export(
		request: ExportSelectedPortablePressureRequest,
		sink: PortablePressureEntrySink,
	): ExportPortablePressureResult
}

/** Bounded receipt provenance copied into the immutable Pressure-origin row. */
data class PortablePressureImportReceipt(
	val jobId: String,
	val entryKey: String,
	val sourceName: String,
	val receivedAtMs: Long,
) {
	init {
		listOf(jobId, entryKey, sourceName).forEach { value ->
			require(value.isNotBlank())
			require(value.length <= PressurePortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH)
		}
		require(receivedAtMs >= 0L)
	}
}

/** One Pressure-only admission transaction over already-decoded portable v1 evidence. */
data class ImportPortablePressureRequest(
	val entry: PortablePressureEntryV1,
	val receipt: PortablePressureImportReceipt,
	val expectedCollectedDataEpoch: Long,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/** Import admission never creates live capture authority or a live Pressure fact. */
interface ImportPortablePressure {
	/**
	 * Admits one decoded Pressure entry and file receipt under the expected data epoch; imports do
	 * not grant live capture authority.
	 */
	suspend fun importEntry(request: ImportPortablePressureRequest): ImportPortablePressureResult
}

/**
 * Imported Pressure admission outcome, reporting saved revision/run/window counts independently of
 * rejected transfers.
 */
sealed interface ImportPortablePressureResult {
	/**
	 * Reports the imported revision and physical-run/window counts saved for the Pressure entry.
	 */
	data class Applied(
		val importRevision: Long,
		val physicalRunCount: Int,
		val windowCount: Int,
	) : ImportPortablePressureResult {
		init {
			require(importRevision > 0L)
			require(physicalRunCount > 0)
			require(windowCount >= 0)
		}
	}

	/**
	 * The same Pressure entry is already represented at the returned import revision.
	 */
	data class Duplicate(val importRevision: Long) : ImportPortablePressureResult {
		init {
			require(importRevision > 0L)
		}
	}

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(
		val reason: PortablePressureImportBlockedReason,
	) : ImportPortablePressureResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: PortablePressureImportUnverifiableReason,
	) : ImportPortablePressureResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PortablePressureTransferRetryableReason,
	) : ImportPortablePressureResult
}

/**
 * Existing deletion, retention, receipt, identity or data-epoch conflicts that prevent imported
 * Pressure admission.
 */
enum class PortablePressureImportBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	RECEIPT_CONFLICT,
	OPAQUE_IDENTITY_CONFLICT,
	DELETED_ENTRY,
	DELETED_RUN,
	DELETED_WINDOW,
	RETENTION_TRUNCATED,
}

/**
 * Invalid entry or stored evidence and exceeded hierarchy/revision/value limits during Pressure
 * admission.
 */
enum class PortablePressureImportUnverifiableReason {
	ENTRY_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	RUN_OVERFLOW,
	WINDOW_OVERFLOW,
	TOTAL_WINDOW_OVERFLOW,
	REVISION_OVERFLOW,
	VALUE_OVERFLOW,
}

/** No-entry and unverifiable outcomes cannot be mistaken for successful empty data. */
sealed interface ExportPortablePressureResult {
	/**
	 * The complete Pressure export emitted the reported positive number of logical entries.
	 */
	data class Exported(val entryCount: Int) : ExportPortablePressureResult {
		init {
			require(entryCount > 0)
		}
	}

	/**
	 * No retained Pressure entry matches the export selection.
	 */
	data object NoEntries : ExportPortablePressureResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(
		val reason: PortablePressureExportUnverifiableReason,
	) : ExportPortablePressureResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(
		val reason: PortablePressureTransferRetryableReason,
	) : ExportPortablePressureResult
}

/** Stable fail-closed categories for Pressure export qualification. */
enum class PortablePressureExportUnverifiableReason {
	SOURCE_EVIDENCE_UNAVAILABLE,
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	CONFLICTING_ORIGIN_IDENTITY,
	ENTRY_MATERIALIZING,
	DEPENDENCY_OVERFLOW,
}

/**
 * Concurrent-state or storage failure permitting retry of the Pressure transfer.
 */
enum class PortablePressureTransferRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
