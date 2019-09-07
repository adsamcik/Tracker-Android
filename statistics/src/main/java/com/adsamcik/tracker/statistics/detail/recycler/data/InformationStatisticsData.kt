package com.adsamcik.tracker.statistics.detail.recycler.data

import androidx.annotation.DrawableRes
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.tracker.statistics.detail.recycler.StatisticDetailType

class InformationStatisticsData(
		@DrawableRes val iconRes: Int, val titleRes: Int,
		val value: String
) : StatisticDetailData {
	override val type: StatisticDetailType = StatisticDetailType.Information
}

