package com.adsamcik.tracker.shared.utils.extension

import com.adsamcik.tracker.shared.base.logging.ReporterFacade

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
		ReporterFacade.report(e)
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
		ReporterFacade.report(e)
		default()
	}
}
