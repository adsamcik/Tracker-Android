package com.adsamcik.tracker.shared.base.database.data

/**
 * Represents a range of dates from [start] (inclusive) to [endInclusive] (inclusive).
 */
data class DateRange(override val start: Long, override val endInclusive: Long) : ClosedRange<Long>
