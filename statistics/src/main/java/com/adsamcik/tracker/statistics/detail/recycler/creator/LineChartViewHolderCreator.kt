package com.adsamcik.tracker.statistics.detail.recycler.creator

import android.view.LayoutInflater
import android.view.ViewGroup
import com.adsamcik.tracker.shared.utils.multitype.StyleMultiTypeViewHolder
import com.adsamcik.tracker.statistics.R
import com.adsamcik.tracker.statistics.detail.recycler.StatisticsDetailData
import com.adsamcik.tracker.statistics.detail.recycler.viewholder.LineChartViewHolder
import com.github.mikephil.charting.charts.LineChart

/**
 * View holder for LineCharts
 */
class LineChartViewHolderCreator : StatisticsViewHolderCreator {

	override fun createViewHolder(parent: ViewGroup): StyleMultiTypeViewHolder<StatisticsDetailData> {
		val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.layout_stats_detail_line_chart, parent, false)

		val chart = view.findViewById<LineChart>(R.id.line_chart).apply {
			isClickable = false
			setDrawGridBackground(false)
			setDrawBorders(false)
			setDrawMarkers(false)
			setTouchEnabled(false)
			legend.isEnabled = false
			xAxis.isEnabled = false
			description.isEnabled = false

			axisRight.labelCount = 5
			axisLeft.isEnabled = false
		}
		@Suppress("unchecked_cast")
		return LineChartViewHolder(
				view, view.findViewById(R.id.title),
				chart
		) as StyleMultiTypeViewHolder<StatisticsDetailData>
	}

}

