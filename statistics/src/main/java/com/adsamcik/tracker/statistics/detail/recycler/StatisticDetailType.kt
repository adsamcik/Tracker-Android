package com.adsamcik.tracker.statistics.detail.recycler

import com.adsamcik.recycler.adapter.implementation.multitype.MultiTypeData

enum class StatisticDetailType {
	Information,
	Map,
	LineChart
}

typealias StatisticDetailData = MultiTypeData<StatisticDetailType>
