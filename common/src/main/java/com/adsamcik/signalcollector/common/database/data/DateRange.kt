package com.adsamcik.signalcollector.common.database.data

data class DateRange(override val start: Long, override val endInclusive: Long) : ClosedRange<Long>
