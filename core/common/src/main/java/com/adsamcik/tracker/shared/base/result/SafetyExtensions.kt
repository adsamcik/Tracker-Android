package com.adsamcik.tracker.shared.base.result

import java.util.concurrent.CancellationException

/**
 * Runs [block] and returns its outcome without converting coroutine cancellation into a failure.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
	return try {
		Result.success(block())
	} catch (e: Exception) {
		if (e is CancellationException) throw e
		Result.failure(e)
	}
}
