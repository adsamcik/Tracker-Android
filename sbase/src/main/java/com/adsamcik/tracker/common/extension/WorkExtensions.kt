package com.adsamcik.tracker.common.extension

import androidx.work.Data
import com.adsamcik.tracker.common.debug.Reporter

fun Data.getLong(key: String): Long? {
	return if (keyValueMap.contains(key)) getLong(key, 0) else null
}

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

fun Data.getLongReportNull(key: String): Long? {
	val value = getLong(key)
	if (value == null) Reporter.report(IllegalArgumentException("Argument $key was not specified"))
	return value
}

