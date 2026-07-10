package com.adsamcik.tracker.stats.engine.ski

import com.adsamcik.tracker.stats.api.ski.SkiLift

/**
 * Result of proximity scoring for a track against ski infrastructure.
 */
data class LiftProximityResult(
	/** Number of LIFT_UP segments that matched a known lift */
	val matchedLiftSegments: Int,
	/** Total LIFT_UP segments checked */
	val totalLiftSegments: Int,
	/** Confidence boost (0-20) */
	val confidenceBoost: Int,
	/** Whether ANY segment was on a known lift (enables single-cycle detection) */
	val onKnownLift: Boolean
)

/**
 * Scores GPS track segments against known ski lift locations.
 * Pure function, no Android dependencies.
 *
 * For each LIFT_UP segment, finds the nearest known lift within a radius
 * and checks if the track direction matches the lift direction.
 */
object SkiLiftProximityScorer {

	/** Maximum distance in meters to consider a track point "near" a lift */
	private const val PROXIMITY_RADIUS_M = 500.0

	/**
	 * Score lift segments against known ski infrastructure.
	 *
	 * @param segments state machine output
	 * @param locations GPS track points
	 * @param nearbyLiftFinder function that returns lifts near a coordinate.
	 *   Signature: (lat, lon, radiusDeg) -> List<SkiLift>
	 *   This abstraction keeps stats-engine free of Android/Room/SQLite deps.
	 */
	fun score(
		segments: List<SkiStateSegment>,
		locations: List<SkiLocationPoint>,
		nearbyLiftFinder: (Double, Double, Double) -> List<SkiLift>
	): LiftProximityResult {
		val liftSegments = segments.filter { it.state == SkiState.LIFT_UP }
		if (liftSegments.isEmpty()) {
			return LiftProximityResult(0, 0, 0, false)
		}

		var matched = 0
		for (segment in liftSegments) {
			val segmentLocations = locations.filter {
				it.timeMs in segment.startMs..segment.endMs
			}
			if (segmentLocations.size < 2) continue

			// Use midpoint of segment for lift search
			val midIdx = segmentLocations.size / 2
			val midPoint = segmentLocations[midIdx]
			val radiusDeg = PROXIMITY_RADIUS_M / 111_000.0

			val nearbyLifts = nearbyLiftFinder(
				midPoint.latitudeDeg,
				midPoint.longitudeDeg,
				radiusDeg
			)
			if (nearbyLifts.isEmpty()) continue

			// Check if track direction roughly matches any nearby lift
			val trackStart = segmentLocations.first()
			val trackEnd = segmentLocations.last()

			for (lift in nearbyLifts) {
				if (isDirectionMatch(trackStart, trackEnd, lift)) {
					matched++
					break
				}
			}
		}

		val totalLift = liftSegments.size

		val boost = when {
			matched >= 3 -> 20
			matched >= 2 -> 15
			matched >= 1 -> 10
			else -> 0
		}

		return LiftProximityResult(
			matchedLiftSegments = matched,
			totalLiftSegments = totalLift,
			confidenceBoost = boost,
			onKnownLift = matched > 0
		)
	}

	/**
	 * Check if track direction roughly matches lift direction (or reverse).
	 * Lifts go from start to end but we don't know which end is "bottom".
	 * We check if the track's bearing is within ~60° of either lift direction.
	 */
	internal fun isDirectionMatch(
		trackStart: SkiLocationPoint,
		trackEnd: SkiLocationPoint,
		lift: SkiLift
	): Boolean {
		val trackBearing = bearing(
			trackStart.latitudeDeg, trackStart.longitudeDeg,
			trackEnd.latitudeDeg, trackEnd.longitudeDeg
		)
		val liftBearing = bearing(lift.startLat, lift.startLon, lift.endLat, lift.endLon)

		// Allow match in either direction (lift might be mapped in reverse)
		val diff1 = bearingDifference(trackBearing, liftBearing)
		val diff2 = bearingDifference(trackBearing, (liftBearing + 180) % 360)

		return diff1 <= 60.0 || diff2 <= 60.0
	}

	internal fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
		val dLon = Math.toRadians(lon2 - lon1)
		val lat1R = Math.toRadians(lat1)
		val lat2R = Math.toRadians(lat2)
		val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2R)
		val x = kotlin.math.cos(lat1R) * kotlin.math.sin(lat2R) -
				kotlin.math.sin(lat1R) * kotlin.math.cos(lat2R) * kotlin.math.cos(dLon)
		return (Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360
	}

	internal fun bearingDifference(b1: Double, b2: Double): Double {
		val diff = kotlin.math.abs(b1 - b2) % 360
		return if (diff > 180) 360 - diff else diff
	}
}
