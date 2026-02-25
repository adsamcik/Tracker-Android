package com.adsamcik.tracker.stats.api.value

import kotlin.jvm.JvmInline

/** Latitude in E7 format (degrees × 1e7). Bounded [-900_000_000, 900_000_000]. */
@JvmInline
value class LatE7(val raw: Int) {
	init {
		require(raw in -900_000_000..900_000_000) { "Invalid latitude E7: $raw" }
	}

	fun toDegrees(): Double = raw / 1e7

	companion object {
		fun fromDegrees(degrees: Double): LatE7 = LatE7((degrees * 1e7).toInt())
	}
}
