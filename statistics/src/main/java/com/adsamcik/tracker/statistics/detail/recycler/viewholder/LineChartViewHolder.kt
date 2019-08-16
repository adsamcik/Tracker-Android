package com.adsamcik.tracker.statistics.detail.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.adsamcik.tracker.common.extension.dp
import com.adsamcik.tracker.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.tracker.common.style.StyleController
import com.adsamcik.tracker.common.style.StyleData
import com.adsamcik.tracker.statistics.detail.recycler.data.LineChartStatisticsData
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class LineChartViewHolder(root: View,
                          val title: TextView,
                          val chart: LineChart) : MultiTypeViewHolder<LineChartStatisticsData>(root) {


	private fun onColorChange(styleData: StyleData) {
		val foregroundColor = styleData.foregroundColor(false)

		chart.data.dataSets.forEach {
			if (it is LineDataSet) {
				it.color = foregroundColor
			}
		}

		chart.axisRight.apply {
			axisLineColor = foregroundColor
			textColor = foregroundColor
		}
	}

	override fun bind(value: LineChartStatisticsData, styleController: StyleController) {
		val context = itemView.context
		val resources = context.resources
		val titleText = resources.getString(value.titleRes)

		title.text = titleText
		val icon = ContextCompat.getDrawable(context, value.iconRes)
		title.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)

		val dataSet = LineDataSet(value.values, titleText).apply {
			setDrawCircles(false)
			setDrawValues(false)
			lineWidth = 1.dp.toFloat()
			mode = LineDataSet.Mode.CUBIC_BEZIER
			cubicIntensity = 0.4f
		}

		val data = LineData(dataSet)
		chart.data = data
		styleController.addListener(this::onColorChange)
		chart.invalidate()
	}

	override fun onRecycle(styleController: StyleController) {
		chart.clear()
		styleController.removeListener(this::onColorChange)
	}

}

