package com.adsamcik.tracker.stats.api.repository

data class ExportPortableCapturedWifiRequest(val logicalTrackingId: String) {
	init {
		require(logicalTrackingId.isNotBlank())
		require(logicalTrackingId.length <= WifiCapturedPortableFormatV1.MAX_LOCAL_IDENTITY_LENGTH)
	}
}

fun interface PortableCapturedWifiSink {
	/** Called only after the complete authenticated Room snapshot has settled. */
	suspend fun emit(entry: PortableCapturedWifiEntryV1)
}

interface ExportPortableCapturedWifi {
	suspend fun export(
		request: ExportPortableCapturedWifiRequest,
		sink: PortableCapturedWifiSink,
	): ExportPortableCapturedWifiResult
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
) {
	init {
		require(expectedCollectedDataEpoch >= 0L)
	}
}

/** Admission stores imported product evidence only and never grants live capture authority. */
interface ImportPortableCapturedWifi {
	suspend fun importEntry(request: ImportPortableCapturedWifiRequest): ImportPortableCapturedWifiResult
}

sealed interface ImportPortableCapturedWifiResult {
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

	data class Duplicate(val importRevision: Long) : ImportPortableCapturedWifiResult {
		init {
			require(importRevision > 0L)
		}
	}

	data class Blocked(val reason: PortableCapturedWifiImportBlockedReason) :
		ImportPortableCapturedWifiResult

	data class Unverifiable(val reason: PortableCapturedWifiImportUnverifiableReason) :
		ImportPortableCapturedWifiResult

	data class RetryableFailure(val reason: PortableWifiRetryableReason) :
		ImportPortableCapturedWifiResult
}

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

sealed interface ExportPortableCapturedWifiResult {
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

	data class Unavailable(val reason: PortableWifiUnavailableReason) : ExportPortableCapturedWifiResult
	data object Materializing : ExportPortableCapturedWifiResult
	data object Active : ExportPortableCapturedWifiResult
	data object Deleted : ExportPortableCapturedWifiResult
	data class Unverifiable(val reason: PortableWifiUnverifiableReason) : ExportPortableCapturedWifiResult
	data class RetryableFailure(val reason: PortableWifiRetryableReason) : ExportPortableCapturedWifiResult
}

enum class PortableWifiUnavailableReason {
	ENTRY_NOT_FOUND,
	SOURCE_NOT_CAPTURED,
	RETENTION_LIMIT,
	PROVIDER_UNAVAILABLE,
}

enum class PortableWifiUnverifiableReason {
	SOURCE_EVIDENCE_STATE_MISSING,
	CAPTURE_ATTRIBUTION_UNVERIFIABLE,
	PHYSICAL_MEMBERSHIP_UNVERIFIABLE,
	WRITER_AUTHORITY_UNVERIFIABLE,
	FACT_AUTHORITY_UNVERIFIABLE,
	DEPENDENCY_OVERFLOW,
}

enum class PortableWifiRetryableReason { STORAGE_UNAVAILABLE }
