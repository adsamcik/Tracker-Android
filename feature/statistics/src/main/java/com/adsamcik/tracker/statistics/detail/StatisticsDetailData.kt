package com.adsamcik.tracker.statistics.detail

/**
 * Base interface for statistics detail data
 */
sealed interface StatisticsDetailData

/**
 * Information statistics data containing simple key-value information
 */
data class InformationStatisticsData(
    val titleRes: Int,
    val iconRes: Int,
    val value: String
) : StatisticsDetailData

/**
 * Line chart statistics data for chart visualization
 */
data class LineChartStatisticsData(
    val titleRes: Int,
    val values: List<Float>
) : StatisticsDetailData

/**
 * Map statistics data for location visualization
 */
data class MapStatisticsData(
    val bounds: Any?, // Simplified - was likely LatLngBounds
    val locations: List<Any> // Simplified - was likely location data
) : StatisticsDetailData
