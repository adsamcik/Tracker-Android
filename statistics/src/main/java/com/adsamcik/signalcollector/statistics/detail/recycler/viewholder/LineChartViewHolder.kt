package com.adsamcik.signalcollector.statistics.detail.recycler.viewholder

import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.adsamcik.signalcollector.common.color.ColorController
import com.adsamcik.signalcollector.common.color.ColorData
import com.adsamcik.signalcollector.common.extension.dp
import com.adsamcik.signalcollector.common.recycler.multitype.MultiTypeViewHolder
import com.adsamcik.signalcollector.statistics.detail.recycler.data.LineChartStatisticsData
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet

class LineChartViewHolder(root: View, val title: TextView, val chart: LineChart) : MultiTypeViewHolder<LineChartStatisticsData>(root) {


	private fun onColorChange(colorData: ColorData) {
		chart.data.dataSets.forEach {
			if (it is LineDataSet) {
				it.color = colorData.foregroundColor
			}
		}

		chart.axisRight.apply {
			axisLineColor = colorData.foregroundColor
			textColor = colorData.foregroundColor
		}
	}

	override fun bind(value: LineChartStatisticsData, colorController: ColorController) {
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
		colorController.addListener(this::onColorChange)
		chart.invalidate()
	}

	override fun onRecycle(colorController: ColorController) {
		chart.clear()
		colorController.removeListener(this::onColorChange)
	}

}