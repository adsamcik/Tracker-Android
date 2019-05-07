package com.adsamcik.signalcollector.statistics.fragment

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProviders
import com.adsamcik.signalcollector.R
import com.adsamcik.signalcollector.common.color.ColorManager
import com.adsamcik.signalcollector.common.color.ColorSupervisor
import com.adsamcik.signalcollector.common.misc.extension.dpAsPx
import com.adsamcik.signalcollector.common.misc.extension.observe
import com.adsamcik.signalcollector.statistics.data.ChartStat
import com.adsamcik.signalcollector.statistics.data.ChartType
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet


class FragmentStatsSessionChart : AppCompatDialogFragment() {
	private lateinit var viewModel: StatsChartVM

	private var chart: LineChart? = null

	private var rootView: View? = null

	private lateinit var colorManager: ColorManager

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
		return inflater.inflate(R.layout.layout_loading, container, false).also { rootView = it }
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		colorManager = ColorSupervisor.createColorManager()

		viewModel = ViewModelProviders.of(this)[StatsChartVM::class.java]
		viewModel.chartStat.observe(this) {
			if (it != null)
				drawChart(it)
		}
	}

	private fun drawChart(stat: ChartStat) {
		val context = context!!

		val chart = chart ?: LineChart(context).also {
			(rootView as ViewGroup).apply {
				removeAllViews()
				addView(it)
			}
		}

		val data = stat.data.map { Entry(it.first.toFloat(), it.second.toFloat()) }
		val dataSet = LineDataSet(data, "data").apply {
			color = Color.BLACK
			fillColor = Color.BLACK
			mode = LineDataSet.Mode.LINEAR
			setDrawCircles(false)
			setDrawValues(false)
			setDrawFilled(true)
		}

		chart.apply {
			isScaleXEnabled = false
			isScaleYEnabled = false
			isAutoScaleMinMaxEnabled = true
			isHighlightPerTapEnabled = false
			isHighlightPerDragEnabled = false
			legend.isEnabled = false
			description.isEnabled = false

			this.data = LineData(dataSet)
			xAxis.apply {
				setDrawGridLines(false)
				setDrawLabels(false)
				setDrawAxisLine(false)
			}

			axisLeft.apply {
				setDrawGridLines(false)
				setDrawAxisLine(false)
			}

			axisRight.apply {
				setDrawLabels(false)
				setDrawGridLines(false)
				setDrawAxisLine(false)
			}

			setDrawBorders(false)
			setDrawMarkers(false)
			setDrawGridBackground(false)
		}

		if (this.chart == null) {
			colorManager.addListener { _, foregroundColor, backgroundColor ->
				chart.let {
					it.setBackgroundColor(backgroundColor)
					dataSet.color = foregroundColor
					dataSet.fillColor = foregroundColor
					it.axisLeft.apply {
						textColor = foregroundColor
						axisLineColor = foregroundColor
					}
					it.xAxis.apply {
						textColor = foregroundColor
						axisLineColor = foregroundColor
					}
					it.axisRight.apply {
						textColor = foregroundColor
						axisLineColor = foregroundColor
					}
				}
			}
		} else
			colorManager.notifyChangeOn(chart)
	}

	override fun onStart() {
		super.onStart()

		val context = context!!

		arguments?.let { bundle ->
			val name = bundle.getString(ARG_NAME)
			val type = ChartType.values()[bundle.getInt(ARG_TYPE)]
			val times = bundle.getLongArray(ARG_DATA_TIME)
			val values = bundle.getDoubleArray(ARG_DATA_VALUE)

			/*GlobalScope.launch {
				val allAltitudes = locationData.mapNotNull {
					val altitude = it.altitude
					if (altitude == null) null else ((it.location.time - from) / Constants.SECOND_IN_MILLISECONDS) to altitude
				}
				viewModel.chartStat.postValue(ChartStat("test", ChartType.Line, allAltitudes))
			}*/
		}

		val metrics = resources.displayMetrics
		val width = metrics.widthPixels
		val height = metrics.heightPixels
		dialog?.window?.setLayout(width - 48.dpAsPx, height - 48.dpAsPx)
	}

	companion object {
		private const val ARG_NAME = "name"
		private const val ARG_TYPE = "type"
		private const val ARG_DATA_TIME = "data_time"
		private const val ARG_DATA_VALUE = "data_value"

		private const val TAG = "StatsSessionChart"

		fun newSessionInstance(fragmentManager: FragmentManager, data: ChartStat) {
			val bundle = Bundle().apply {
				putString(ARG_NAME, data.name)
				putInt(ARG_TYPE, data.chartType.ordinal)
				val start = data.data.first()
				putLongArray(ARG_DATA_TIME, data.data.map { it.first - start.first }.toLongArray())
				putDoubleArray(ARG_DATA_VALUE, data.data.map { it.second }.toDoubleArray())
			}
			FragmentStatsSessionChart().apply { arguments = bundle }.show(fragmentManager, TAG)
		}
	}
}

class StatsChartVM : ViewModel() {
	var chartStat: MutableLiveData<ChartStat> = MutableLiveData()
}