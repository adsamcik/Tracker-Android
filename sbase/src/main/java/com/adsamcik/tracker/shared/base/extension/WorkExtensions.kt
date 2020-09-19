package com.adsamcik.tracker.shared.base.extension

import androidx.work.Data

/**
 * Try get long from data
 */
fun Data.tryGetLong(key: String): Long? {
	return if (keyValueMap.contains(key)) getLong(key, 0) else null
}

