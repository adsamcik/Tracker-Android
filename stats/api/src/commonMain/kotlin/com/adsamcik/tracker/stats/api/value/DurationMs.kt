package com.adsamcik.tracker.stats.api.value

import kotlin.jvm.JvmInline

/** Duration in milliseconds. Always >= 0. */
@JvmInline
value class DurationMs(val raw: Long) : Comparable<DurationMs> {
	init {
		require(raw >= 0L) { "Duration cannot be negative: $raw ms" }
	}

	override fun compareTo(other: DurationMs) = raw.compareTo(other.raw)
	operator fun plus(other: DurationMs) = DurationMs(raw + other.raw)

	fun toSeconds(): Double = raw / 1000.0

	companion object {
		val ZERO = DurationMs(0L)
		fun fromSeconds(seconds: Double) = DurationMs((seconds * 1000).toLong().coerceAtLeast(0L))
	}
}
