package com.adsamcik.signalcollector.data

import com.vimeo.stag.UseStag

/**
 * Object that contains data about specific row of in statistics
 */
@UseStag
data class StatData internal constructor(var id: String, var value: String)
