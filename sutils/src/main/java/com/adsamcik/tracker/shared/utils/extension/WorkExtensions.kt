package com.adsamcik.tracker.shared.utils.extension

import androidx.work.Data
import com.adsamcik.tracker.logger.Reporter
import com.adsamcik.tracker.shared.base.extension.tryGetLong

/**
 * Tries to get positive long from data.
 * Returns null and reports it if the value is not found or negative.
 */
fun Data.getPositiveLongReportNull(key: String): Long? {
	val value = getLong(key, -1)
	return if (value < 0) {
		if (keyValueMap.contains(key)) {
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
 * Tries to get long from data.
 * Returns null if it is not found and reports it.
 */
fun Data.getLongReportNull(key: String): Long? {
	val value = tryGetLong(key)
	if (value == null) {
		Reporter.report(IllegalArgumentException("Argument $key was not specified"))
	}
	return value
}
