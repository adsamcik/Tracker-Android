package com.adsamcik.tracker.statistics.data

data class ChartStat(val name: String, val chartType: ChartType, val data: List<Pair<Long, Double>>)

enum class ChartType {
	Line,
	Bar,
	Pie,
	Scatter,
	Bubble
}

