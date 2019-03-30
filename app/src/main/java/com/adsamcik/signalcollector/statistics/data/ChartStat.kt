package com.adsamcik.signalcollector.statistics.data

data class ChartStat(val name: String, val chartType: ChartType, val data: List<Number>)

enum class ChartType {
	Line,
	Bar,
	Pie,
	Scatter,
	Bubble
}
