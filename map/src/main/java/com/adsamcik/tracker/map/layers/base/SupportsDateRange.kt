package com.adsamcik.tracker.map.layers.base

/** Optional capability for layers to accept a date range filter. */
interface SupportsDateRange {
    var dateRange: LongRange
}
