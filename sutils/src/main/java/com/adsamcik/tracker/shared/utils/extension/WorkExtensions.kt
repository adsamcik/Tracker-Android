package com.adsamcik.tracker.shared.utils.extension

import androidx.work.Data
import com.adsamcik.tracker.common.extension.getLong
import com.adsamcik.tracker.shared.utils.debug.Reporter

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
