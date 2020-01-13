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

typealias StatisticDetailData = MultiTypeData<StatisticDisplayType>
