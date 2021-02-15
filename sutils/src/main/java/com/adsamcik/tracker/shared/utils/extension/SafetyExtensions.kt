package com.adsamcik.tracker.shared.utils.extension

import com.adsamcik.tracker.logger.Reporter

/**
 * Tries to call [func] and if exception occurs it logs it.
 *
 * @return true if no exception occurred, false otherwise.
 */
@Suppress("TooGenericExceptionCaught")
inline fun tryWithReport(func: () -> Unit): Boolean {
	return try {
		func()
		true
	} catch (e: Exception) {
		com.adsamcik.tracker.logger.Reporter.report(e)
		false
	}
}

/**
 * Tries to call [func] and if exception occurs it logs it.
 *
 * @return value if no exception occurred, result of [default] otherwise.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> tryWithResultAndReport(default: () -> T, func: () -> T): T {
	return try {
		func()
	} catch (e: Exception) {
		com.adsamcik.tracker.logger.Reporter.report(e)
		default()
	}
}
