package com.adsamcik.tracker.stats.api.repository

/**
 * Finite, half-open wall-time selection for portable Steps export.
 *
 * A qualifying logical entry overlaps the interval when its exact envelope starts before
 * [toExclusiveMs] and ends after [fromInclusiveMs]. Every member run is then exported so a
 * range boundary cannot split replacement ownership.
 */
data class ExportPortableStepsRequest(
	val fromInclusiveMs: Long,
	val toExclusiveMs: Long,
) {
	init {
		require(fromInclusiveMs >= 0L)
		require(toExclusiveMs > fromInclusiveMs)
	}
}

/** Streaming entry sink shared by the product reader and the file-format adapter. */
fun interface PortableStepsEntrySink {
	/** Accepts one completely verified logical entry. */
	suspend fun emit(entry: PortableStepsEntryV1)
}

/** Read-only product contract. Implementations must never acquire or retain a provider demand. */
interface ExportPortableSteps {
	/**
	 * Streams all qualifying entries in canonical order for [request]. Implementations must validate
	 * and bound the complete point-in-time snapshot before the first [sink] emission, then perform
	 * external I/O outside the storage transaction. Once emission starts, the only valid result is
	 * [ExportPortableStepsResult.Exported], whose count must equal the entries accepted by [sink].
	 */
	suspend fun export(
		request: ExportPortableStepsRequest,
		sink: PortableStepsEntrySink,
	): ExportPortableStepsResult
}

/** Typed product-read outcome; no-entry and unverifiable are not successful empty exports. */
sealed interface ExportPortableStepsResult {
	/** A non-empty export completed with exactly [entryCount] entries. */
	data class Exported(val entryCount: Int) : ExportPortableStepsResult {
		init {
			require(entryCount > 0)
		}
	}

	/** The selected interval contains no qualifying Steps entries. */
	data object NoEntries : ExportPortableStepsResult

	/** Durable source evidence cannot truthfully produce a portable entry. */
	data class Unverifiable(
		val reason: PortableStepsExportUnverifiableReason,
	) : ExportPortableStepsResult

	/** A transient storage or state race prevented a stable export. */
	data class RetryableFailure(
		val reason: PortableStepsTransferRetryableReason,
	) : ExportPortableStepsResult
}

/** Stable categories explaining why an export cannot be verified. */
enum class PortableStepsExportUnverifiableReason {
	SOURCE_EVIDENCE_UNAVAILABLE,
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	ENTRY_MATERIALIZING,
	RETENTION_CROSSES_ENTRY,
	DEPENDENCY_OVERFLOW,
	RANGE_UNSUPPORTED,
}

/** Authoritative source-local import command. One call owns one logical-entry transaction. */
interface ImportPortableSteps {
	/**
	 * Applies or rejects [entry] atomically through the Steps-local authoritative writer.
	 * Implementations must snapshot caller-owned collections and recompute
	 * [PortableStepsIntegrity.expectedEntryChecksum] inside the transaction boundary before any
	 * identity, deletion-fence, retention, or destination mutation. They must durably preserve the
	 * already-portable opaque identities and each original deletion-scope digest verbatim; deriving
	 * either value again from a local imported identity would break replay and no-resurrection.
	 */
	suspend fun importEntry(entry: PortableStepsEntryV1): ImportPortableStepsResult
}

/** Import outcomes remain distinct so replay, privacy fences, and conflicts cannot be collapsed. */
sealed interface ImportPortableStepsResult {
	/** The complete logical entry was durably applied. */
	data class Applied(
		val physicalRunCount: Int,
		val factCount: Int,
	) : ImportPortableStepsResult {
		init {
			require(physicalRunCount > 0)
			require(factCount >= 0)
		}
	}

	/** Same opaque identity and same canonical semantic checksum; the database was unchanged. */
	data object Duplicate : ImportPortableStepsResult

	/** A durable exact run-scope deletion fence rejected the complete logical entry. */
	data object DeletedScope : ImportPortableStepsResult

	/** At least one member is older than the monotonic retained floor; nothing was imported. */
	data object OutsideRetention : ImportPortableStepsResult

	/** Same opaque identity with different durable content; nothing was imported. */
	data class Conflict(
		val scope: PortableStepsConflictScope,
	) : ImportPortableStepsResult

	/** Required source attribution or correction repair cannot be verified. */
	data class Unverifiable(
		val reason: PortableStepsImportUnverifiableReason,
	) : ImportPortableStepsResult

	/** A transient storage or state race prevented the atomic import. */
	data class RetryableFailure(
		val reason: PortableStepsTransferRetryableReason,
	) : ImportPortableStepsResult
}

/** Identity scope whose durable content disagrees with the import. */
enum class PortableStepsConflictScope {
	LOGICAL_ENTRY,
	PHYSICAL_RUN,
	FACT,
}

/** Stable categories explaining why an import cannot be verified. */
enum class PortableStepsImportUnverifiableReason {
	ATTRIBUTION_UNVERIFIABLE,
	DAY_REPAIR_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
}

/** Stable retry categories shared by export and import. */
enum class PortableStepsTransferRetryableReason {
	CONCURRENT_STATE_CHANGE,
	STORAGE_UNAVAILABLE,
	DAY_REPAIR_MATERIALIZING,
}
