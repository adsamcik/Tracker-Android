package com.adsamcik.tracker.shared.base.startup

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Process-wide boundary between storage/recovery work and live tracking work.
 *
 * Implementations may retry durable recovery, but must cache only [TrackingStartupResult.Ready].
 * A closed gate means policy observers, provider registrations, source admission, and collected
 * Room workers must remain fail-closed; provider-only cleanup and deletion paths are deliberately
 * outside this contract.
 */
interface TrackingStartupGate {
	/**
	 * Compatibility name for admission call sites. Admission is intentionally not a weaker startup
	 * milestone: released-v27 recovery must be terminal before any live-v2 WAL mutation.
	 */
	val isAdmissionReady: Boolean
		get() = isReady

	/** Full released-history recovery is complete, so providers and canonical writers may run. */
	val isReady: Boolean

	/** Captured once for a fresh Start callback; negative means no command authority is available. */
	val currentCommandGeneration: Long get() = -1L

	/** Existing runtime Ready publications; collecting does not reconcile, poll or acquire a provider. */
	val runtimeReadyChanges: Flow<Unit>
		get() = flow { awaitReady(); emit(Unit) }

	/** User-requested system stopping suppresses acquisition while allowing cleanup and history. */
	val permitsNewCollection: Boolean get() = isReady

	/** Exact fresh manual preparation/delivery only; never authorizes providers or background work. */
	fun permitsPendingManualStart(commandGeneration: Long): Boolean = false

	/** Last explicit resume fences delayed observations and provider windows from the stopped span. */
	val collectionResumeBoundary: TrackingCollectionResumeBoundary? get() = null

	/** Process-local deletion/startup generation used to fence delayed workers. */
	val currentGeneration: Long
		get() = 0L

	/** True only while [expectedGeneration] is the currently admitted runtime generation. */
	fun isReadyGeneration(expectedGeneration: Long): Boolean =
		isReady && currentGeneration == expectedGeneration

	/**
	 * Linearizes one short, non-suspending external handoff with deletion closure. Production gates
	 * override this to make close wait for [operation]; the default keeps lightweight test gates
	 * source-compatible.
	 */
	fun <T> withReadyGeneration(
		expectedGeneration: Long,
		operation: () -> T,
	): T? = if (isReadyGeneration(expectedGeneration)) {
		operation()
	} else {
		null
	}

	/**
	 * Runs low-frequency control-plane work while deletion is excluded. High-rate observation
	 * ingress should use transactional epoch/generation checks instead of this serialized path.
	 */
	suspend fun <T> withReadyGenerationOperation(
		expectedGeneration: Long,
		operation: suspend () -> T,
	): T? = if (isReadyGeneration(expectedGeneration)) {
		operation()
	} else {
		null
	}

	/**
	 * Cancellable live work that may itself reserve a START. It holds neither startup nor command
	 * locks; callers still validate exact command/purpose authority at each effect boundary.
	 * Deletion cancels and physically joins its child before erasing data. This is not acquisition
	 * permission and does not replace serialized control-plane handoffs.
	 */
	suspend fun <T> withReadyGenerationWork(
		expectedGeneration: Long,
		operation: suspend () -> T,
	): T? = if (isReadyGeneration(expectedGeneration)) {
		operation()
	} else {
		null
	}

	/**
	 * One short retained-data handoff, fenced by storage/deletion rather than collection Stop.
	 * This never authorizes acquisition. A transfer already running may finish after tracking Off.
	 */
	fun <T> withRetainedDataGeneration(
		expectedGeneration: Long,
		operation: () -> T,
	): T? = withReadyGeneration(expectedGeneration, operation)

	/**
	 * Admits one structured file transfer. Deletion cancels and physically joins its child job,
	 * including stream closure and cancellation cleanup. The caller reconciles before admission;
	 * long I/O never holds startup or Stop control-plane locks. Retained-history transfers use
	 * storage readiness even when new collection is stopped or a newer Stop is being handled.
	 */
	suspend fun <T> withReadyGenerationTransfer(
		expectedGeneration: Long,
		operation: suspend () -> T,
	): T? = if (withRetainedDataGeneration(expectedGeneration) { true } == true) {
		operation()
	} else {
		null
	}

	/**
	 * Compatibility entry point for live admission. It deliberately reconciles the full gate.
	 */
	suspend fun reconcileAdmission(
		retryFailedStorage: Boolean = false,
	): TrackingAdmissionStartupResult = when (val result = reconcile(retryFailedStorage)) {
		is TrackingStartupResult.Ready -> TrackingAdmissionStartupResult.Ready
		is TrackingStartupResult.RetryableFailure -> TrackingAdmissionStartupResult.RetryableFailure(
			result.stage,
			result.failureCode,
			result.databaseRetryable,
		)
		is TrackingStartupResult.Blocked -> TrackingAdmissionStartupResult.Blocked(
			result.stage,
			result.failureCode,
			result.databaseContainment,
		)
	}

	/** Reconciles storage and recovery; explicit retry permits another failed-storage open attempt. */
	suspend fun reconcile(retryFailedStorage: Boolean = false): TrackingStartupResult

	/**
	 * Suspends without polling until this process reaches full runtime readiness. The default keeps
	 * simple test doubles source-compatible; production implementations should signal this directly.
	 */
	suspend fun awaitReady(): TrackingStartupResult.Ready {
		var retryDelayMillis = DEFAULT_RETRY_DELAY_MILLIS
		while (true) {
			when (val result = reconcile()) {
				is TrackingStartupResult.Ready -> return result
				is TrackingStartupResult.RetryableFailure -> {
					delay(retryDelayMillis)
					retryDelayMillis = (retryDelayMillis * 2L).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
				}
				is TrackingStartupResult.Blocked -> delay(BLOCKED_RECHECK_DELAY_MILLIS)
			}
		}
	}

	private companion object {
		const val DEFAULT_RETRY_DELAY_MILLIS = 500L
		const val MAX_RETRY_DELAY_MILLIS = 30_000L
		const val BLOCKED_RECHECK_DELAY_MILLIS = 15L * 60L * 1_000L
	}
}

/** UTC and boot-relative resume boundary that excludes observations collected while tracking was Off. */
data class TrackingCollectionResumeBoundary(
	val wallTimeMs: Long,
	val bootId: String,
	val elapsedRealtimeNanos: Long,
) {
	init {
		require(wallTimeMs >= 0L && bootId.isNotBlank() && elapsedRealtimeNanos >= 0L)
	}

	/** Uses the monotonic boundary within the same boot and UTC milliseconds across boots. */
	fun permitsObservation(observedBootId: String, observedElapsedNanos: Long, observedWallTimeMs: Long): Boolean =
		if (observedBootId == bootId) {
			observedElapsedNanos >= elapsedRealtimeNanos
		} else {
			observedWallTimeMs >= wallTimeMs
		}

	/** Accepts only provider windows starting at or after the resume time in UTC milliseconds. */
	fun permitsWindow(windowStartWallTimeMs: Long): Boolean = windowStartWallTimeMs >= wallTimeMs
}

/** Admission-facing view of the full startup result; no intermediate state grants collection. */
sealed interface TrackingAdmissionStartupResult {
	/** Full recovery completed, allowing ordinary admission checks to proceed. */
	data object Ready : TrackingAdmissionStartupResult

	/** A recoverable stage failure with a payload-free code and optional database retry reason. */
	data class RetryableFailure(
		val stage: TrackingStartupStage,
		val failureCode: String,
		val databaseRetryable: TrackingDatabaseRetryable? = null,
	) : TrackingAdmissionStartupResult

	/** A stage requiring remediation; preserved database data must not be overwritten. */
	data class Blocked(
		val stage: TrackingStartupStage,
		val failureCode: String,
		val databaseContainment: TrackingDatabaseContainment? = null,
	) : TrackingAdmissionStartupResult
}

/** Full storage and recovery outcome, separating retryable failure from preserved-data containment. */
sealed interface TrackingStartupResult {
	/** Recovery progress, including honest partial legacy coverage and the completed live ordinal. */
	data class Ready(
		val legacyRecoveryPartial: Boolean,
		val liveCompletedThroughOrdinal: Long,
	) : TrackingStartupResult

	/** A recoverable stage failure with a payload-free code and optional database retry reason. */
	data class RetryableFailure(
		val stage: TrackingStartupStage,
		val failureCode: String,
		val databaseRetryable: TrackingDatabaseRetryable? = null,
	) : TrackingStartupResult

	/** A stage requiring remediation; preserved database data must not be overwritten. */
	data class Blocked(
		val stage: TrackingStartupStage,
		val failureCode: String,
		val databaseContainment: TrackingDatabaseContainment? = null,
	) : TrackingStartupResult
}

/** Reason and non-destructive remediation for an unsupported or unreadable preserved database. */
data class TrackingDatabaseContainment(
	val reason: TrackingDatabaseContainmentReason,
	val remediation: TrackingDatabaseRemediation =
		TrackingDatabaseRemediation.PRESERVE_AND_BACK_UP_MANUALLY,
)

/** Stable reasons why startup cannot safely open or migrate the saved database. */
enum class TrackingDatabaseContainmentReason {
	STALE_DEVELOPMENT_V28,
	UNSUPPORTED_DATABASE_VERSION,
	UNRECOGNIZED_DATABASE_SCHEMA,
	INVALID_FINAL_V28_MARKER,
	INCOMPLETE_FINAL_V28_SCHEMA,
	UNREADABLE_DATABASE,
	DEVELOPMENT_V27_MIGRATION_VALIDATION_FAILED,
	FINAL_V28_OPEN_VALIDATION_FAILED,
}

/** Permitted action for preserved data that automated recovery cannot safely open. */
enum class TrackingDatabaseRemediation {
	PRESERVE_AND_BACK_UP_MANUALLY,
}

/** Storage failure that can be retried without replacing saved data. */
data class TrackingDatabaseRetryable(
	val reason: TrackingDatabaseRetryableReason,
)

/** Distinguishes transient contention from other operational storage failures. */
enum class TrackingDatabaseRetryableReason {
	CONTENDED,
	OPERATIONALLY_UNAVAILABLE,
}

/** Ordered recovery stages reported by the shared startup gate. */
enum class TrackingStartupStage {
	STORAGE,
	LEGACY_IMPORT,
	PREVIOUS_EXIT,
	LEGACY_V27,
	LIVE_V2,
}
