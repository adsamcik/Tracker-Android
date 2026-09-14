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

/** Receives one fully authenticated logical Pressure entry. */
fun interface PortablePressureEntrySink {
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
	suspend fun importEntry(request: ImportPortablePressureRequest): ImportPortablePressureResult
}

sealed interface ImportPortablePressureResult {
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

	data class Duplicate(val importRevision: Long) : ImportPortablePressureResult {
		init {
			require(importRevision > 0L)
		}
	}

	data class Blocked(
		val reason: PortablePressureImportBlockedReason,
	) : ImportPortablePressureResult

	data class Unverifiable(
		val reason: PortablePressureImportUnverifiableReason,
	) : ImportPortablePressureResult

	data class RetryableFailure(
		val reason: PortablePressureTransferRetryableReason,
	) : ImportPortablePressureResult
}

enum class PortablePressureImportBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	RECEIPT_CONFLICT,
	OPAQUE_IDENTITY_CONFLICT,
	DELETED_RUN,
}

enum class PortablePressureImportUnverifiableReason {
	ENTRY_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	RUN_OVERFLOW,
	WINDOW_OVERFLOW,
	TOTAL_WINDOW_OVERFLOW,
	REVISION_OVERFLOW,
}

/** No-entry and unverifiable outcomes cannot be mistaken for successful empty data. */
sealed interface ExportPortablePressureResult {
	data class Exported(val entryCount: Int) : ExportPortablePressureResult {
		init {
			require(entryCount > 0)
		}
	}

	data object NoEntries : ExportPortablePressureResult

	data class Unverifiable(
		val reason: PortablePressureExportUnverifiableReason,
	) : ExportPortablePressureResult

	data class RetryableFailure(
		val reason: PortablePressureTransferRetryableReason,
	) : ExportPortablePressureResult
}

/** Stable fail-closed categories for Pressure export qualification. */
enum class PortablePressureExportUnverifiableReason {
	SOURCE_EVIDENCE_UNAVAILABLE,
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	ENTRY_MATERIALIZING,
	DEPENDENCY_OVERFLOW,
}

enum class PortablePressureTransferRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
}
