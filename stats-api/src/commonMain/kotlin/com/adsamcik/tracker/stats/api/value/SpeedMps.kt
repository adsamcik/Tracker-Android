package com.adsamcik.tracker.stats.api.value

import kotlin.jvm.JvmInline

/** Speed in meters per second. Always >= 0. */
@JvmInline
value class SpeedMps(val raw: Float) : Comparable<SpeedMps> {
	init {
		require(raw >= 0f) { "Speed cannot be negative: $raw m/s" }
	}

	override fun compareTo(other: SpeedMps) = raw.compareTo(other.raw)
	operator fun plus(other: SpeedMps) = SpeedMps(raw + other.raw)
	operator fun minus(other: SpeedMps) = SpeedMps((raw - other.raw).coerceAtLeast(0f))

	companion object {
		val ZERO = SpeedMps(0f)
		fun coerced(raw: Float) = SpeedMps(raw.coerceAtLeast(0f))
		fun orNull(raw: Float?): SpeedMps? = raw?.let { if (it >= 0f) SpeedMps(it) else null }
	}
}
