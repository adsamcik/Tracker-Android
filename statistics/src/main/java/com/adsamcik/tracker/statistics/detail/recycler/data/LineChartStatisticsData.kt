package com.adsamcik.tracker.statistics.detail.recycler.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.github.mikephil.charting.data.Entry

class LineChartStatisticsData(@DrawableRes val iconRes: Int, @StringRes val titleRes: Int, val values: List<Entry>) :
		StatisticDetailData {
	override val type: StatisticDisplayType = StatisticDisplayType.LineChart
}

