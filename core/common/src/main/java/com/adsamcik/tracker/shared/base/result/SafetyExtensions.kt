package com.adsamcik.tracker.shared.base.result

import com.adsamcik.tracker.logging.api.ReporterFacade
import java.util.concurrent.CancellationException

/**
 * Tries to call [func] and if exception occurs it logs it.
 *
 * @return true if no exception occurred, false otherwise.
 */
@Deprecated(
	message = "Silently swallowing exceptions can hide critical failures on worker, export, and tracking paths. Use runWithReport instead.",
	replaceWith = ReplaceWith("runWithReport(func).isSuccess"),
	level = DeprecationLevel.WARNING
)
@Suppress("TooGenericExceptionCaught")
inline fun tryWithReport(func: () -> Unit): Boolean {
	return try {
		func()
		true
	} catch (e: Exception) {
		ReporterFacade.report(e)
		false
	}
}

/**
 * Tries to call [func] and if exception occurs it logs it.
 *
 * @return value if no exception occurred, result of [default] otherwise.
 */
@Deprecated(
	message = "Silently swallowing exceptions can hide critical failures on worker, export, and tracking paths. Use runWithResultAndReport instead.",
	replaceWith = ReplaceWith("runWithResultAndReport(func).getOrElse { default() }"),
	level = DeprecationLevel.WARNING
)
@Suppress("TooGenericExceptionCaught")
inline fun <T> tryWithResultAndReport(default: () -> T, func: () -> T): T {
	return try {
		func()
	} catch (e: Exception) {
		ReporterFacade.report(e)
		default()
	}
}

/**
 * Runs [func], reports failures, and returns the outcome as [Result].
 */
@Suppress("TooGenericExceptionCaught")
inline fun runWithReport(func: () -> Unit): Result<Unit> {
	return try {
		func()
		Result.success(Unit)
	} catch (e: Exception) {
		if (e is CancellationException) throw e
		ReporterFacade.report(e)
		Result.failure(e)
	}
}

/**
 * Runs [func], reports failures, and returns the outcome as [Result].
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> runWithResultAndReport(func: () -> T): Result<T> {
	return try {
		Result.success(func())
	} catch (e: Exception) {
		if (e is CancellationException) throw e
		ReporterFacade.report(e)
		Result.failure(e)
	}
}
