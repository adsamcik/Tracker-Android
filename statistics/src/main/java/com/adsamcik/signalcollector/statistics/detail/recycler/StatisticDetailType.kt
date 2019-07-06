package com.adsamcik.signalcollector.statistics.detail.recycler

import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeData

enum class StatisticDetailType {
	Information,
	Map,
	LineChart
}

typealias StatisticDetailData = MultiTypeData<StatisticDetailType>