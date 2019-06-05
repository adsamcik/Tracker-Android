package com.adsamcik.signalcollector.statistics.detail.recycler.viewholder

import com.adsamcik.signalcollector.statistics.detail.recycler.ViewHolder
import com.adsamcik.signalcollector.statistics.detail.recycler.data.LineChartStatisticsData
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class LineChartViewHolder(val chart: LineChart) : ViewHolder<LineChartStatisticsData>(chart) {
	override fun bind(value: LineChartStatisticsData) {
		val title = chart.context.getString(value.titleRes)
		val dataSet = LineDataSet(value.values, title)

		val data = LineData(dataSet)
		chart.data = data
		chart.invalidate()
	}

	override fun onRecycle() {
		super.onRecycle()
		chart.clear()
	}

}