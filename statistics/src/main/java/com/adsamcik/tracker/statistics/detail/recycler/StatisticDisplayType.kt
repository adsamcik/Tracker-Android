package com.adsamcik.tracker.statistics.detail.recycler

import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeData

/**
 * How stats should be displayed.
 */
enum class StatisticDisplayType {
	Information,
	Map,
	LineChart
}

/**
 * Interface representing recycler data type.
 */
interface StatisticsDetailData : MultiTypeData<StatisticDisplayType>
