package com.adsamcik.tracker.statistics.data

import com.adsamcik.tracker.statistics.database.data.CacheStatData

/**
 * Object that contains data about specific statistic
 */
data class TableStat(val name: String, val showPosition: Boolean, val data: List<CacheStatData>)
