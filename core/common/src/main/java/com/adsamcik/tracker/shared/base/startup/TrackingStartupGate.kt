package com.adsamcik.tracker.shared.base.startup

import kotlinx.coroutines.delay

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
	): T? = if (isReadyGeneration(expectedGeneration)) operation() else null

	/**
	 * Runs low-frequency control-plane work while deletion is excluded. High-rate observation
	 * ingress should use transactional epoch/generation checks instead of this serialized path.
	 */
	suspend fun <T> withReadyGenerationOperation(
		expectedGeneration: Long,
		operation: suspend () -> T,
	): T? = if (isReadyGeneration(expectedGeneration)) operation() else null

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
		)
		is TrackingStartupResult.Blocked -> TrackingAdmissionStartupResult.Blocked(
			result.stage,
			result.failureCode,
		)
	}

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

sealed interface TrackingAdmissionStartupResult {
	data object Ready : TrackingAdmissionStartupResult

	data class RetryableFailure(
		val stage: TrackingStartupStage,
		val failureCode: String,
	) : TrackingAdmissionStartupResult

	data class Blocked(
		val stage: TrackingStartupStage,
		val failureCode: String,
	) : TrackingAdmissionStartupResult
}

sealed interface TrackingStartupResult {
	data class Ready(
		val legacyRecoveryPartial: Boolean,
		val liveCompletedThroughOrdinal: Long,
	) : TrackingStartupResult

	data class RetryableFailure(
		val stage: TrackingStartupStage,
		val failureCode: String,
	) : TrackingStartupResult

	data class Blocked(
		val stage: TrackingStartupStage,
		val failureCode: String,
	) : TrackingStartupResult
}

enum class TrackingStartupStage {
	STORAGE,
	LEGACY_IMPORT,
	PREVIOUS_EXIT,
	LEGACY_V27,
	LIVE_V2,
}
