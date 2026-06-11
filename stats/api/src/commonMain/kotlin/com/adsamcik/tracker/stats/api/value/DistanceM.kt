package com.adsamcik.tracker.stats.api.value

import kotlin.jvm.JvmInline

/** Distance in meters. Always >= 0. */
@JvmInline
value class DistanceM(val raw: Float) : Comparable<DistanceM> {
	init {
		require(raw >= 0f) { "Distance cannot be negative: $raw m" }
	}

	override fun compareTo(other: DistanceM) = raw.compareTo(other.raw)
	operator fun plus(other: DistanceM) = DistanceM(raw + other.raw)

	companion object {
		val ZERO = DistanceM(0f)
		fun coerced(raw: Float) = DistanceM(raw.coerceAtLeast(0f))
		fun orNull(raw: Float?): DistanceM? = raw?.let { if (it >= 0f) DistanceM(it) else null }
	}
}
