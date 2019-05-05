package com.adsamcik.signalcollector.statistics.data

import com.squareup.moshi.JsonClass

/**
 * Object that contains data about specific row of in statistics
 */
@JsonClass(generateAdapter = true)
data class StatData internal constructor(var id: String, var value: String)
