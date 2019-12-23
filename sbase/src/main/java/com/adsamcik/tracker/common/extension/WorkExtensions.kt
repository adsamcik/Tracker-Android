package com.adsamcik.tracker.common.extension

import androidx.work.Data

fun Data.getLong(key: String): Long? {
	return if (keyValueMap.contains(key)) getLong(key, 0) else null
}

