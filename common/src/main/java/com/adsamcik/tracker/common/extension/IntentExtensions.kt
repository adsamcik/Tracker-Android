package com.adsamcik.tracker.common.extension

import android.content.Intent
import com.adsamcik.tracker.common.debug.Reporter

fun Intent.getLongExtra(key: String): Long? {
	return if (hasExtra(key)) getLongExtra(key, 0) else null
}

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


fun Intent.getLongExtraReportNull(key: String): Long? {
	val value = getLongExtra(key)
	if (value == null) Reporter.report(IllegalArgumentException("Argument $key was not specified"))
	return value
}
