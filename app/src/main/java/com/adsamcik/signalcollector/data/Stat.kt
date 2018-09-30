package com.adsamcik.signalcollector.data

import com.squareup.moshi.JsonClass

/**
 * Object that contains data about specific statistic
 */
@JsonClass(generateAdapter = true)
data class Stat(val name: String, val type: String, val showPosition: Boolean, val data: List<StatData>)
