package com.adsamcik.signalcollector.statistics.data

import com.squareup.moshi.JsonClass

/**
 * Object that contains data about specific statistic
 */
@JsonClass(generateAdapter = true)
data class Stat(val name: String, val type: StatType, val showPosition: Boolean, val data: List<StatData>)

enum class StatType {
    Table,
    Line,
    Bar,
    Pie,
    Scatter,
    Bubble
}
