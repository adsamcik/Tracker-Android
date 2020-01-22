package com.adsamcik.tracker.statistics.detail.recycler.data

import androidx.annotation.DrawableRes
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDisplayType
import com.adsamcik.tracker.statistics.detail.recycler.StatisticsDetailData

class InformationStatisticsData(
		@DrawableRes val iconRes: Int, val titleRes: Int,
		val value: String
) : StatisticsDetailData {
	override val type: StatisticDisplayType = StatisticDisplayType.Information
}

