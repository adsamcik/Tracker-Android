package com.adsamcik.signalcollector.statistics.detail.recycler.creator

import android.view.ViewGroup
import com.adsamcik.signalcollector.common.misc.extension.dp
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailData
import com.adsamcik.signalcollector.statistics.detail.recycler.StatisticDetailViewHolderCreator
import com.adsamcik.signalcollector.statistics.detail.recycler.ViewHolder
import com.adsamcik.signalcollector.statistics.detail.recycler.viewholder.LineChartViewHolder
import com.github.mikephil.charting.charts.LineChart

class LineChartViewHolderCreator : StatisticDetailViewHolderCreator {

	override fun createViewHolder(parent: ViewGroup): ViewHolder<StatisticDetailData> {
		val chart = LineChart(parent.context).apply {
			layoutParams = ViewGroup.LayoutParams(
					ViewGroup.LayoutParams.MATCH_PARENT,
					200.dp)

			isClickable = false
			setDrawGridBackground(false)
			setDrawBorders(false)
			setDrawMarkers(false)
		}
		@Suppress("unchecked_cast")
		return LineChartViewHolder(chart) as ViewHolder<StatisticDetailData>
	}

}