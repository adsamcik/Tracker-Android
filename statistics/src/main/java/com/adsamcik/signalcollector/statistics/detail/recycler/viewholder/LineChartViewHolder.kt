package com.adsamcik.signalcollector.statistics.detail.recycler.viewholder

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.adsamcik.signalcollector.common.misc.extension.dp
import com.adsamcik.signalcollector.statistics.detail.recycler.ViewHolder
import com.adsamcik.signalcollector.statistics.detail.recycler.data.LineChartStatisticsData
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class LineChartViewHolder(root: View, val title: TextView, val chart: LineChart) : ViewHolder<LineChartStatisticsData>(root) {
	override fun bind(value: LineChartStatisticsData) {
		val context = itemView.context
		val resources = context.resources
		val titleText = resources.getString(value.titleRes)

		title.text = titleText
		val icon = ContextCompat.getDrawable(context, value.iconRes)
		title.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)

		val dataSet = LineDataSet(value.values, titleText).apply {
			color = Color.WHITE
			setDrawCircles(false)
			lineWidth = 1.dp.toFloat()
			mode = LineDataSet.Mode.CUBIC_BEZIER
			cubicIntensity = 0.2f
		}

		val data = LineData(dataSet)
		chart.data = data
		chart.invalidate()
	}

	override fun onRecycle() {
		super.onRecycle()
		chart.clear()
	}

}