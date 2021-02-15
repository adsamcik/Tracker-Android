package com.adsamcik.tracker.points.data

import com.adsamcik.tracker.shared.base.database.data.DateRange

/**
 * Summary of points over a period of time
 */
data class PointsSummary(val intervalRange: DateRange, val value: Points)
