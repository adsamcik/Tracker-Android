package com.adsamcik.signalcollector.statistics.detail.recycler.data

import androidx.annotation.StringRes
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailType
import com.github.mikephil.charting.data.Entry

class LineChartStatisticsData(@StringRes val titleRes: Int, val values: List<Entry>) : StatisticDetailData {
	override val type: StatisticDetailType = StatisticDetailType.LineChart
}