package com.adsamcik.tracker.shared.utils.extension

import androidx.work.Data
import com.adsamcik.tracker.shared.base.extension.tryGetLong
import com.adsamcik.tracker.logger.Reporter

fun Data.getPositiveLongReportNull(key: String): Long? {
	val value = getLong(key, -1)
	return if (value < 0) {
		if (keyValueMap.contains(key)) {
			com.adsamcik.tracker.logger.Reporter.report(IllegalArgumentException("Argument $key had invalid negative value of $value"))
		} else {
			com.adsamcik.tracker.logger.Reporter.report(IllegalArgumentException("Argument $key was not specified"))
		}
		null
	} else {
		value
	}
}

fun Data.getLongReportNull(key: String): Long? {
	val value = tryGetLong(key)
	if (value == null) com.adsamcik.tracker.logger.Reporter.report(IllegalArgumentException("Argument $key was not specified"))
	return value
}
