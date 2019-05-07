package com.adsamcik.signalcollector.statistics.detail.recycler.data

import androidx.annotation.DrawableRes
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailType

class InformationStatisticsData(@DrawableRes val iconRes: Int, val titleRes: Int, val value: String) : StatisticDetailData {
	override val type: StatisticDetailType = StatisticDetailType.Information
}