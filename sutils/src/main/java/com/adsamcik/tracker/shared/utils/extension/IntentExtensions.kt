package com.adsamcik.tracker.shared.utils.extension

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import com.adsamcik.tracker.logger.Reporter

/**
 * Try get long value from Intent.
 */
fun Intent.tryGetLongExtra(key: String): Long? {
	return if (hasExtra(key)) getLongExtra(key, 0) else null
}

/**
 * Get positive long value from Intent. Reports exception if value is not valid and returns null.
 */
fun Intent.getPositiveLongExtraReportNull(key: String): Long? {
	val value = getLongExtra(key, -1)
	return if (value < 0) {
		if (hasExtra(key)) {
			Reporter.report(IllegalArgumentException("Argument $key had invalid negative value of $value"))
		} else {
			Reporter.report(IllegalArgumentException("Argument $key was not specified"))
		}
		null
	} else {
		value
	}
}

/**
 * Get long from Intent and report if null.
 */
fun Intent.getLongExtraReportNull(key: String): Long? {
	val value = tryGetLongExtra(key)
	if (value == null) Reporter.report(IllegalArgumentException("Argument $key was not specified"))
	return value
}
