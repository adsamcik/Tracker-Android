package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.tracker.failure.isTrackingOperationalFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout

/**
 * Serializes service teardown and replacement-session initialization.
 */
internal class TrackingLifecycleBarrier {
	private val mutex = Mutex()

	suspend fun <T> runAfter(
		previousTeardown: Job?,
		waitTimeoutMillis: Long? = null,
		block: suspend () -> T,
	): T {
		val awaitTurn: suspend () -> Unit = {
			previousTeardown?.join()
			mutex.lock()
		}
		if (waitTimeoutMillis == null) {
			awaitTurn()
		} else {
			require(waitTimeoutMillis > 0L) { "Lifecycle wait timeout must be positive" }
			withTimeout(waitTimeoutMillis) { awaitTurn() }
		}
		return try {
			block()
		} finally {
			mutex.unlock()
		}
	}
}

internal suspend fun <T> retryTrackingShutdown(
	maxAttempts: Int = 3,
	retryDelayMillis: Long = 100L,
	maxRetryDelayMillis: Long = retryDelayMillis,
	attemptTimeoutMillis: Long? = null,
	shutdown: suspend () -> T,
): T {
	require(maxAttempts > 0) { "Shutdown attempts must be positive" }
	require(retryDelayMillis >= 0L) { "Shutdown retry delay must not be negative" }
	require(maxRetryDelayMillis >= retryDelayMillis) {
		"Maximum shutdown retry delay must not be shorter than the initial delay"
	}
	require(attemptTimeoutMillis == null || attemptTimeoutMillis > 0L) {
		"Shutdown attempt timeout must be positive"
	}

	var lastFailure: Exception? = null
	var nextDelayMillis = retryDelayMillis
	repeat(maxAttempts) { attempt ->
		try {
			return if (attemptTimeoutMillis == null) {
				shutdown()
			} else {
				withTimeout(attemptTimeoutMillis) { shutdown() }
			}
		} catch (exception: TimeoutCancellationException) {
			lastFailure = IllegalStateException(
				"Tracking shutdown attempt timed out after ${attemptTimeoutMillis}ms",
				exception,
			)
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			if (!exception.isTrackingOperationalFailure() && exception !is TrackingShutdownRetryException) {
				throw exception
			}
			lastFailure = exception
		}
		if (attempt < maxAttempts - 1) {
			delay(nextDelayMillis)
			nextDelayMillis = (nextDelayMillis * 2).coerceAtMost(maxRetryDelayMillis)
		}
	}

	throw IllegalStateException("Tracking shutdown failed after $maxAttempts attempts", lastFailure)
}

/** Marks an expected coordinator contention result as safe to retry at the service boundary. */
internal class TrackingShutdownRetryException(code: String) : Exception(code)

/**
 * Keeps teardown ownership until cleanup succeeds or the hosting coroutine is cancelled.
 *
 * A destroyed service cannot safely give up after a fixed number of attempts: provider actors and
 * callbacks are process-scoped and may otherwise remain live until process death. The capped
 * backoff bounds retry wakeups while preserving the durable STOPPING/cleanup-required state.
 */
internal suspend fun <T> retryTrackingShutdownUntilSuccess(
	retryDelayMillis: Long = 1_000L,
	maxRetryDelayMillis: Long = 60_000L,
	attemptTimeoutMillis: Long? = null,
	onFailure: (attempt: Long, failure: Exception) -> Unit = { _, _ -> },
	shutdown: suspend () -> T,
): T {
	require(retryDelayMillis > 0L) { "Shutdown retry delay must be positive" }
	require(maxRetryDelayMillis >= retryDelayMillis) {
		"Maximum shutdown retry delay must not be shorter than the initial delay"
	}
	require(attemptTimeoutMillis == null || attemptTimeoutMillis > 0L) {
		"Shutdown attempt timeout must be positive"
	}

	var attempt = 0L
	var nextDelayMillis = retryDelayMillis
	while (true) {
		attempt = (attempt + 1L).coerceAtMost(Long.MAX_VALUE)
		try {
			return if (attemptTimeoutMillis == null) {
				shutdown()
			} else {
				withTimeout(attemptTimeoutMillis) { shutdown() }
			}
		} catch (exception: TimeoutCancellationException) {
			onFailure(
				attempt,
				IllegalStateException(
					"Tracking shutdown attempt timed out after ${attemptTimeoutMillis}ms",
					exception,
				),
			)
		} catch (exception: CancellationException) {
			throw exception
		} catch (exception: Exception) {
			onFailure(attempt, exception)
		}

		delay(nextDelayMillis)
		nextDelayMillis = when {
			nextDelayMillis >= maxRetryDelayMillis -> maxRetryDelayMillis
			nextDelayMillis > Long.MAX_VALUE / 2L -> maxRetryDelayMillis
			else -> (nextDelayMillis * 2L).coerceAtMost(maxRetryDelayMillis)
		}
	}
}
