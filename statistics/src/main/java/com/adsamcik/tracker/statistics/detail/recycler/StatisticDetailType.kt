package com.adsamcik.tracker.statistics.detail.recycler

import com.adsamcik.tracker.common.recycler.multitype.MultiTypeData

enum class StatisticDetailType {
	Information,
	Map,
	LineChart
}

typealias StatisticDetailData = MultiTypeData<StatisticDetailType>
