package com.adsamcik.tracker.stats.api.repository

/**
 * One nonblank bounded logical recording identity for native captured Wi-Fi export.
 */
data class ExportPortableCapturedWifiRequest(val logicalTrackingId: String) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(logicalTrackingId.length <= WifiCapturedPortableFormatV1.MAX_LOCAL_IDENTITY_LENGTH)
	}
}

/**
 * Receives one prepared portable Wi-Fi entry after its source snapshot has closed.
 */
fun interface PortableCapturedWifiSink {
	/** Called only after the complete authenticated Room snapshot has settled. */
	suspend fun emit(entry: PortableCapturedWifiEntryV1)
}

/**
 * Read-only native Wi-Fi entry export that does not activate tracking.
 */
interface ExportPortableCapturedWifi {
	/**
	 * Prepares the selected native Wi-Fi entry in a bounded snapshot, then emits it outside the
	 * database transaction; cancellation propagates.
	 */
	suspend fun export(
		request: ExportPortableCapturedWifiRequest,
		sink: PortableCapturedWifiSink,
	): ExportPortableCapturedWifiResult
}

/**
 * Exact selected native portable snapshot for same-transaction origin collision resolution.
 * This read grants no imported, deletion, provider, or writer authority and performs no sink I/O.
 */
interface ReadLocalPortableCapturedWifi {
	/**
	 * Reads the exact native Wi-Fi hierarchy inside the caller's Room transaction without sink I/O or
	 * recording activation.
	 */
	suspend fun readInTransaction(
		request: ExportPortableCapturedWifiRequest,
	): ReadLocalPortableCapturedWifiResult
}

/**
 * In-transaction native portable snapshot or the specific export outcome that prevented it.
 */
sealed interface ReadLocalPortableCapturedWifiResult {
	/**
	 * Contains the complete prepared native Wi-Fi entry for the caller's current transaction.
	 */
	data class Ready(val entry: PortableCapturedWifiEntryV1) : ReadLocalPortableCapturedWifiResult
	/**
	 * The native Wi-Fi snapshot could not be prepared; preserves its specific export outcome.
	 */
	data class Outcome(val result: ExportPortableCapturedWifiResult) : ReadLocalPortableCapturedWifiResult
}

/** Bounded provenance copied into immutable Wi-Fi-local imported authority. */
data class PortableCapturedWifiImportReceipt(
	val jobId: String,
	val entryKey: String,
	val sourceName: String,
	val receivedAtMs: Long,
) {
	init {
		listOf(jobId, entryKey, sourceName).forEach { value ->
			require(value.isNotBlank())
			require(value.length <= WifiCapturedPortableFormatV1.MAX_IMPORT_RECEIPT_FIELD_LENGTH)
		}
		require(receivedAtMs >= 0L)
	}
}

/** One Wi-Fi-only admission transaction over an already-decoded captured-product entry. */
data class ImportPortableCapturedWifiRequest(
	val entry: PortableCapturedWifiEntryV1,
	val receipt: PortableCapturedWifiImportReceipt,
	val expectedCollectedDataEpoch: Long,
	/** Version-three source files contain independent readings; omitted readings remain saved. */
	val mergeSelectedReadings: Boolean = false,
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/** Admission stores imported product evidence only and never grants live capture authority. */
interface ImportPortableCapturedWifi {
	/**
	 * Admits decoded Wi-Fi content and file provenance under the original job epoch; selected-reading
	 * merges keep omitted saved readings.
	 */
	suspend fun importEntry(request: ImportPortableCapturedWifiRequest): ImportPortableCapturedWifiResult
}

/**
 * Imported Wi-Fi admission outcome, reporting the saved revision separately from conflicts and
 * retryable failures.
 */
sealed interface ImportPortableCapturedWifiResult {
	/**
	 * Reports the imported revision and physical-run/observation counts saved for the Wi-Fi entry.
	 */
	data class Applied(
		val importRevision: Long,
		val physicalRunCount: Int,
		val observationCount: Int,
	) : ImportPortableCapturedWifiResult {
		init {
			require(importRevision > 0L)
			require(physicalRunCount > 0)
			require(observationCount >= 0)
		}
	}

	/**
	 * The same Wi-Fi content is already represented at the returned import revision.
	 */
	data class Duplicate(val importRevision: Long) : ImportPortableCapturedWifiResult {
		init {
			require(importRevision > 0L)
		}
	}

	/**
	 * The stated lifecycle, selection or privacy boundary prevents this operation from continuing.
	 */
	data class Blocked(val reason: PortableCapturedWifiImportBlockedReason) :
		ImportPortableCapturedWifiResult

	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(val reason: PortableCapturedWifiImportUnverifiableReason) :
		ImportPortableCapturedWifiResult

	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(val reason: PortableWifiRetryableReason) :
		ImportPortableCapturedWifiResult
}

/**
 * Receipt, identity, correction, deletion, retention or data-epoch boundaries that prevent Wi-Fi
 * admission.
 */
enum class PortableCapturedWifiImportBlockedReason {
	COLLECTED_DATA_EPOCH_CHANGED,
	RETENTION_BOUNDARY,
	RECEIPT_CONFLICT,
	CORRECTION_CONFLICT,
	OPAQUE_IDENTITY_CONFLICT,
	DELETED_ENTRY,
	DELETED_RUN,
	DELETED_SCOPE,
}

/**
 * Invalid entry or stored source evidence and exceeded run/zone/observation/revision bounds during
 * Wi-Fi admission.
 */
enum class PortableCapturedWifiImportUnverifiableReason {
	ENTRY_INVALID,
	SOURCE_EVIDENCE_STATE_MISSING,
	STORED_EVIDENCE_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
	RUN_OVERFLOW,
	ZONE_OVERFLOW,
	OBSERVATION_OVERFLOW,
	REVISION_OVERFLOW,
}

/**
 * Prepared native Wi-Fi export outcome, distinguishing active, materializing, deleted and
 * unavailable data.
 */
sealed interface ExportPortableCapturedWifiResult {
	/**
	 * The prepared native Wi-Fi entry was emitted with its identity, checksum and represented counts.
	 */
	data class Exported(
		val entryIdentity: PortableWifiOpaqueIdentity,
		val contentChecksum: PortableWifiDigest,
		val physicalRunCount: Int,
		val observationCount: Int,
	) : ExportPortableCapturedWifiResult {
		init {
			require(physicalRunCount > 0 && observationCount >= 0)
		}
	}

	/**
	 * The requested complete export is unavailable for the stated capture, retention or privacy
	 * reason.
	 */
	data class Unavailable(val reason: PortableWifiUnavailableReason) : ExportPortableCapturedWifiResult
	/**
	 * The requested native Wi-Fi product has not finished projection into a complete saved snapshot.
	 */
	data object Materializing : ExportPortableCapturedWifiResult
	/**
	 * The selected native Wi-Fi recording is still active and cannot provide a completed-entry
	 * export.
	 */
	data object Active : ExportPortableCapturedWifiResult
	/**
	 * The selected native Wi-Fi entry has been deleted.
	 */
	data object Deleted : ExportPortableCapturedWifiResult
	/**
	 * The required stored scope or source lineage could not be verified for this operation.
	 */
	data class Unverifiable(val reason: PortableWifiUnverifiableReason) : ExportPortableCapturedWifiResult
	/**
	 * A temporary storage or concurrent-state failure prevents completion and allows a retry.
	 */
	data class RetryableFailure(val reason: PortableWifiRetryableReason) : ExportPortableCapturedWifiResult
}

/**
 * Missing capture, retention or provider state that leaves the requested Wi-Fi entry unavailable
 * for export.
 */
enum class PortableWifiUnavailableReason {
	ENTRY_NOT_FOUND,
	SOURCE_NOT_CAPTURED,
	RETENTION_LIMIT,
	PROVIDER_UNAVAILABLE,
}

/**
 * Missing or inconsistent capture, physical-membership, writer or fact evidence and exceeded read
 * dependencies.
 */
enum class PortableWifiUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
	WRITER_AUTHORITY_UNVERIFIABLE,
	FACT_AUTHORITY_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
}

/**
 * Storage failure that permits retrying a Wi-Fi transfer.
 */
enum class PortableWifiRetryableReason { STORAGE_UNAVAILABLE }
