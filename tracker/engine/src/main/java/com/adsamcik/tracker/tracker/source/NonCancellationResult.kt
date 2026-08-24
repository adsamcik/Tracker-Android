package com.adsamcik.tracker.tracker.source

import kotlinx.coroutines.CancellationException

/**
 * Converts operational failures to [Result] without turning coroutine cancellation into a retry.
 * Use this instead of Kotlin's [runCatching] around suspend work.
 */
internal inline fun <T> runCatchingNonCancellation(block: () -> T): Result<T> = try {
	Result.success(block())
} catch (cancelled: CancellationException) {
	throw cancelled
} catch (failure: Exception) {
	Result.failure(failure)
}
