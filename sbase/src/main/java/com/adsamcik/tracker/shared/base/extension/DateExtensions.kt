package com.adsamcik.tracker.shared.base.extension

import java.time.ZonedDateTime

/**
 * Is before or equal to other date.
 */
fun ZonedDateTime.isBeforeOrEqual(other: ZonedDateTime) = isBefore(other) || isEqual(other)

/**
 * Is after or equal to other date.
 */
fun ZonedDateTime.isAfterOrEqual(other: ZonedDateTime) = isAfter(other) || isEqual(other)
