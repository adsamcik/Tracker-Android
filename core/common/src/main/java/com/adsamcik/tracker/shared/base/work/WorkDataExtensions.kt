package com.adsamcik.tracker.shared.base.work

import androidx.work.Data

/**
 * Returns the stored long, or null when [key] is absent.
 */
fun Data.tryGetLong(key: String): Long? {
	return if (keyValueMap.contains(key)) getLong(key, 0) else null
}

/**
 * Returns a stored non-negative long, or null when the key is absent or the value is negative.
 */
fun Data.getNonNegativeLongOrNull(key: String): Long? {
	val value = getLong(key, -1)
	return if (value < 0) {
		null
	} else {
		value
	}
}
