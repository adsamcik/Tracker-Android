package com.adsamcik.tracker.map.layers.base

/** Optional capability for v2 layers to accept a date range filter. */
interface SupportsDateRange {
    var dateRange: LongRange
}
