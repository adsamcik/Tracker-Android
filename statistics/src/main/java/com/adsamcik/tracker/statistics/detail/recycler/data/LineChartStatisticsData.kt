package com.adsamcik.tracker.statistics.detail.recycler.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.detail.recycler.StatisticsDetailData
import com.github.mikephil.charting.data.Entry

/**
 * Data for line chart statistics.
 * Contains everything required for creation of line chart.
 */
class LineChartStatisticsData(
		@DrawableRes val iconRes: Int,
		@StringRes val titleRes: Int,
		val values: List<Entry>
) : StatisticsDetailData {
	override val type: StatisticDisplayType = StatisticDisplayType.LineChart
}

