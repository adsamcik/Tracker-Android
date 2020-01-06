package com.adsamcik.tracker.statistics.data

import androidx.annotation.StringRes
import com.adsamcik.tracker.statistics.database.data.StatData

data class Stat(
		val name: String,
		val data: StatData
)
