package com.adsamcik.signalcollector.data

import com.vimeo.stag.UseStag

/**
 * Object that contains data about specific statistic
 */
@UseStag
data class Stat(val name: String, val type: String, val showPosition: Boolean, val data: List<StatData>)
