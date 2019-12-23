package com.adsamcik.tracker.shared.base.database.data

data class DateRange(override val start: Long, override val endInclusive: Long) : ClosedRange<Long>
