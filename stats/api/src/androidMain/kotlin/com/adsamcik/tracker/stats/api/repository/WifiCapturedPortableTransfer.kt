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
