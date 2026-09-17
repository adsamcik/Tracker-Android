package com.adsamcik.tracker.diagnostics

/**
 * Fixed local-storage limits. They are not runtime or remote configuration.
 *
 * Minute-scale aggregation and rate windows are process-memory controls; only the 15-minute event
 * bucket is persisted.
 */
object TrackingDiagnosticStorageLimits {
	const val GLOBAL_EVENT_CAP = 512
	const val PER_SOURCE_EVENT_CAP = 128
	const val RETENTION_DAYS = 7L
	const val MAX_ENCODED_EVENT_BYTES = 512
	const val GLOBAL_ENCODED_BYTE_CAP = 256 * 1_024
	const val PER_SOURCE_ENCODED_BYTE_CAP = 64 * 1_024
	const val AGGREGATION_WINDOW_MILLIS = 60_000L
	const val RATE_LIMIT_WINDOW_MILLIS = 60_000L
	const val GLOBAL_RATE_LIMIT = 240
	const val PER_SOURCE_RATE_LIMIT = 60
	const val DEFAULT_PAGE_SIZE = 50
	const val MAX_PAGE_SIZE = 100

	internal const val RETENTION_MILLIS =
		RETENTION_DAYS * 24L * 60L * 60L * 1_000L
	internal const val MAX_AGGREGATED_OCCURRENCES = 65
	internal const val MAX_IN_MEMORY_AGGREGATION_KEYS = 256
}

/** Complete outcome of one local event-store attempt. */
enum class TrackingDiagnosticStorageResult {
	STORED,
	AGGREGATED,
	DROPPED_RATE_LIMIT,
	STORAGE_RETRYABLE,
	PERMANENT_REJECTED,
}

/**
 * Opaque keyset cursor for one diagnostic history query.
 *
 * It is local navigation state, not a stable event identifier or product authority.
 */
class TrackingDiagnosticPageCursor internal constructor(
	internal val beforeCoarseTimeBucket: Long,
	internal val beforeRowId: Long,
) {
	override fun toString(): String = "TrackingDiagnosticPageCursor(opaque)"
}

data class TrackingDiagnosticStoredMetrics(
	val encodedEnvelopeSizeBucket: TrackingDiagnosticSizeBucket?,
	val queueBacklogBucket: TrackingDiagnosticBacklogBucket?,
	val drainedEnvelopeCountBucket: TrackingDiagnosticCountBucket?,
	val remainingEnvelopeBacklogBucket: TrackingDiagnosticBacklogBucket?,
	val persistedEnvelopeCountBucket: TrackingDiagnosticCountBucket?,
)

/**
 * Payload-free event view for a later local health evaluator.
 *
 * Recorder-owned process/scope correlation remains in memory and never enters storage.
 */
data class TrackingDiagnosticStoredEvent(
	val source: TrackingDiagnosticSource,
	val purpose: TrackingDiagnosticPurpose,
	val pipelineStage: TrackingDiagnosticPipelineStage,
	val operation: TrackingDiagnosticOperation,
	val result: TrackingDiagnosticResult,
	val reason: TrackingDiagnosticReason,
	val lifecycle: TrackingDiagnosticEventLifecycle,
	val coarseTimeBucket: Long,
	val scopeDurationBucket: TrackingDiagnosticDurationBucket,
	val occurrenceCountBucket: TrackingDiagnosticCountBucket,
	val metrics: TrackingDiagnosticStoredMetrics,
)

data class TrackingDiagnosticPage(
	val events: List<TrackingDiagnosticStoredEvent>,
	val nextCursor: TrackingDiagnosticPageCursor?,
)

sealed interface TrackingDiagnosticReadResult {
	data class Page(val value: TrackingDiagnosticPage) : TrackingDiagnosticReadResult
	data object StorageRetryable : TrackingDiagnosticReadResult
	data object PermanentRejected : TrackingDiagnosticReadResult
}

enum class TrackingDiagnosticClearResult {
	CLEARED,
	STORAGE_RETRYABLE,
}

enum class TrackingDiagnosticMaintenanceResult {
	PRUNED,
	STORAGE_RETRYABLE,
}

/**
 * Bounded pull-only access for local health evaluation.
 *
 * There is deliberately no Flow, callback, listener, export, or upload surface.
 */
interface TrackingDiagnosticHistory {
	suspend fun querySource(
		source: TrackingDiagnosticSource,
		pageSize: Int = TrackingDiagnosticStorageLimits.DEFAULT_PAGE_SIZE,
		before: TrackingDiagnosticPageCursor? = null,
	): TrackingDiagnosticReadResult

	suspend fun querySourcePurpose(
		source: TrackingDiagnosticSource,
		purpose: TrackingDiagnosticPurpose,
		pageSize: Int = TrackingDiagnosticStorageLimits.DEFAULT_PAGE_SIZE,
		before: TrackingDiagnosticPageCursor? = null,
	): TrackingDiagnosticReadResult
}

/** Local deletion boundary used by diagnostics UI and app-wide collected-data deletion. */
interface TrackingDiagnosticDataControl {
	suspend fun clearAll(): TrackingDiagnosticClearResult
}

/**
 * Local maintenance seam. No worker is scheduled by this module; append also enforces all limits.
 */
interface TrackingDiagnosticMaintenance {
	suspend fun pruneExpiredAndOverflow(): TrackingDiagnosticMaintenanceResult
}

internal data class TrackingDiagnosticStoragePolicy(
	val globalEventCap: Int = TrackingDiagnosticStorageLimits.GLOBAL_EVENT_CAP,
	val perSourceEventCap: Int = TrackingDiagnosticStorageLimits.PER_SOURCE_EVENT_CAP,
	val retentionMillis: Long = TrackingDiagnosticStorageLimits.RETENTION_MILLIS,
	val maxEncodedEventBytes: Int = TrackingDiagnosticStorageLimits.MAX_ENCODED_EVENT_BYTES,
	val globalEncodedByteCap: Long =
		TrackingDiagnosticStorageLimits.GLOBAL_ENCODED_BYTE_CAP.toLong(),
	val perSourceEncodedByteCap: Long =
		TrackingDiagnosticStorageLimits.PER_SOURCE_ENCODED_BYTE_CAP.toLong(),
	val aggregationWindowMillis: Long =
		TrackingDiagnosticStorageLimits.AGGREGATION_WINDOW_MILLIS,
	val rateLimitWindowMillis: Long =
		TrackingDiagnosticStorageLimits.RATE_LIMIT_WINDOW_MILLIS,
	val globalRateLimit: Int = TrackingDiagnosticStorageLimits.GLOBAL_RATE_LIMIT,
	val perSourceRateLimit: Int = TrackingDiagnosticStorageLimits.PER_SOURCE_RATE_LIMIT,
	val maxAggregatedOccurrences: Int =
		TrackingDiagnosticStorageLimits.MAX_AGGREGATED_OCCURRENCES,
) {
	init {
		require(globalEventCap > 0)
		require(perSourceEventCap in 1..globalEventCap)
		require(retentionMillis > 0L)
		require(maxEncodedEventBytes > 0)
		require(globalEncodedByteCap >= maxEncodedEventBytes)
		require(perSourceEncodedByteCap >= maxEncodedEventBytes)
		require(aggregationWindowMillis >= 0L)
		require(rateLimitWindowMillis > 0L)
		require(globalRateLimit > 0)
		require(perSourceRateLimit in 1..globalRateLimit)
		require(maxAggregatedOccurrences > 0)
	}
}
