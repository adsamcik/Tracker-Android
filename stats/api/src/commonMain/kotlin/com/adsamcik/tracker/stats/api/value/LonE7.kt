package com.adsamcik.tracker.stats.api.value

import com.adsamcik.tracker.shared.model.geo.CheckedLongitudeE7
import kotlin.jvm.JvmInline

/** Longitude in E7 format (degrees × 1e7). Bounded [-1_800_000_000, 1_800_000_000]. */
@JvmInline
value class LonE7(val raw: Int) {
	init {
		require(raw in -1_800_000_000..1_800_000_000) { "Invalid longitude E7: $raw" }
	}

	fun toDegrees(): Double = raw / 1e7

	/** Canonical spatial identity; read-time compatibility may retain raw +180° elsewhere. */
	fun canonicalSpatial(): LonE7 = LonE7(CheckedLongitudeE7.requireE7(raw.toLong()).value)

	companion object {
		fun fromDegrees(degrees: Double): LonE7 =
			LonE7(CheckedLongitudeE7.requireDegrees(degrees).value)
	}
}
