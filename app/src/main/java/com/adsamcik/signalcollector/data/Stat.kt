package com.adsamcik.signalcollector.data

/**
 * Object that contains data about specific statistic
 */
data class Stat(val name: String, val type: String, val showPosition: Boolean, val data: List<StatData>)
