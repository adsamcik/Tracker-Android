package com.adsamcik.tracker.stats.api.value

import kotlin.jvm.JvmInline

/** Step count. Always >= 0. */
@JvmInline
value class StepCount(val raw: Int) : Comparable<StepCount> {
	init {
		require(raw >= 0) { "Step count cannot be negative: $raw" }
	}

	override fun compareTo(other: StepCount) = raw.compareTo(other.raw)
	operator fun plus(other: StepCount) = StepCount(raw + other.raw)

	companion object {
		val ZERO = StepCount(0)
		fun coerced(raw: Int) = StepCount(raw.coerceAtLeast(0))
	}
}
