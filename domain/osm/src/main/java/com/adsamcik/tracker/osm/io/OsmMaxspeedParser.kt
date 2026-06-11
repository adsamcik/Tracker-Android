package com.adsamcik.tracker.osm.io

import kotlin.math.round

/**
 * Pure parser for the OSM `maxspeed=*` tag.
 *
 * Phase 2a supports the formats we actually see in user-imported regions:
 *  - `"50"`, `"  90 "` -> 50, 90 km/h
 *  - `"60 mph"`, `"35mph"` -> rounded km/h
 *  - `"50 km/h"` -> 50 km/h (suffix tolerated even though it's redundant)
 *
 * Country-zone shortcuts (`DE:urban`, `RO:rural`, `walk`, `none`, `signals`,
 * `variable`, ...) intentionally fall back to the road-class default rather
 * than shipping a 200-entry table that's stale the day it's written. Phase 2b
 * can introduce a country resolver if needed.
 *
 * Returns `null` when no usable number could be extracted.
 */
object OsmMaxspeedParser {

	private const val MPH_TO_KMH = 1.609344
	private const val KNOTS_TO_KMH = 1.852

	fun parseKmh(raw: String?): Int? {
		if (raw.isNullOrBlank()) return null
		val lower = raw.trim().lowercase()

		val hasMph = lower.endsWith("mph")
		val hasKnots = lower.endsWith("knots")
		val cleaned = when {
			hasMph -> lower.removeSuffix("mph").trim()
			hasKnots -> lower.removeSuffix("knots").trim()
			lower.endsWith("km/h") -> lower.removeSuffix("km/h").trim()
			lower.endsWith("kmh") -> lower.removeSuffix("kmh").trim()
			else -> lower
		}

		val number = cleaned.toDoubleOrNull() ?: return null
		if (number <= 0.0 || number > 400.0) return null

		val kmh = when {
			hasMph -> number * MPH_TO_KMH
			hasKnots -> number * KNOTS_TO_KMH
			else -> number
		}
		return round(kmh).toInt().coerceIn(1, 400)
	}
}
