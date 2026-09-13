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
	STORAGE_UNAVAILABLE,
}
