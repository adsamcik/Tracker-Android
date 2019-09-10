package com.adsamcik.tracker.common.extension

import com.adsamcik.tracker.common.debug.Reporter

@Suppress("TooGenericExceptionCaught")
inline fun tryWithReport(func: () -> Unit): Boolean {
	return try {
		func()
		true
	} catch (e: Exception) {
		Reporter.report(e)
		false
	}
}

@Suppress("TooGenericExceptionCaught")
inline fun <T> tryWithResultAndReport(default: () -> T, func: () -> T): T {
	return try {
		func()
	} catch (e: Exception) {
		Reporter.report(e)
		default()
	}
}
