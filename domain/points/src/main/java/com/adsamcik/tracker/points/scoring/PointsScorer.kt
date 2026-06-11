package com.adsamcik.tracker.points.scoring

import com.adsamcik.tracker.points.data.PointsScoringPolicy
import com.adsamcik.tracker.shared.base.data.ActivityInfo
import com.adsamcik.tracker.shared.model.Location
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure scoring logic extracted from [com.adsamcik.tracker.points.work.PointsWorker].
 * All methods are side-effect-free: data in → points out.
 */
class PointsScorer(private val policy: PointsScoringPolicy = PointsScoringPolicy()) {

	/**
	 * Calculates points based on slope segments derived from ordered location data.
	 * Requires at least two locations with non-null altitude.
	 *
	 * @return total points earned across all qualifying segments.
	 */
	fun calculateSlopePoints(locations: List<ScoringLocation>): Double {
		val slopeSegments = calculateSlope(locations)
		return slopeSegments.sumOf { segment ->
			val slopePositive = max(segment.slopeRadians, 0.0)
			val slopeBonus = sqrt(slopePositive / policy.halfSlope) * policy.slopeMultiplier
			val cappedSpeed = segment.speedMPS.coerceAtMost(MAX_SCORING_SPEED_MPS)
			segment.distance * policy.pointsPerMeterMps * cappedSpeed * (1.0 + slopeBonus)
		}
	}

	/**
	 * Fallback scoring when fewer than two altitude-bearing locations are available.
	 * Returns the best of step-based, distance-based, or duration-based estimates.
	 */
	fun calculateFallbackPoints(
		steps: Int,
		distanceMeters: Double,
		durationMinutes: Double,
	): Double {
		val stepPoints = steps.coerceAtLeast(0) * policy.fallbackPointsPerStep
		val distancePoints = distanceMeters.coerceAtLeast(0.0) * policy.fallbackPointsPerMeter
		val durationPoints = durationMinutes.coerceAtLeast(0.0) * policy.fallbackPointsPerMinute
		return max(stepPoints, max(distancePoints, durationPoints))
	}

	/**
	 * Builds slope segments from ordered location data.
	 * Each segment records the altitude change, slope angle, flat distance, and speed.
	 */
	fun calculateSlope(locationData: Collection<ScoringLocation>): Collection<SlopeData> {
		val firstLocation = locationData.first()
		var lastAltitude = requireNotNull(firstLocation.altitude)
		val slopeList = mutableListOf(
			SlopeData(
				firstLocation.location,
				firstLocation.activity,
				change = 0.0,
				slopeRadians = 0.0,
				distance = 0.0,
				speedMPS = 0.0,
			)
		)
		var prevLocation = firstLocation.location
		locationData.forEachIndexed { index, dbLocation ->
			val location = dbLocation.location
			val altitude = requireNotNull(location.altitude)
			val diff = abs(lastAltitude - altitude)
			if (index + 1 == locationData.size || diff > policy.altitudeThreshold) {
				val distance = prevLocation.distanceFlatMeters(location)
				val timeDelta = location.time - prevLocation.time
				if (timeDelta <= 0 || distance <= 0.0) return@forEachIndexed
				val timeDeltaSeconds = timeDelta / 1000.0
				val speed = distance / timeDeltaSeconds
				val slope = kotlin.math.atan(diff / distance)
				slopeList.add(
					SlopeData(
						location,
						dbLocation.activity,
						diff,
						slope,
						distance,
						speed,
					)
				)

				prevLocation = location
				lastAltitude = altitude
			}
		}

		return slopeList
	}

	data class ScoringLocation(
		val location: Location,
		val activity: ActivityInfo,
	) {
		val altitude: Double? get() = location.altitude
	}

	/**
	 * One segment of slope analysis between two qualifying location points.
	 */
	data class SlopeData(
		val location: Location,
		val activity: ActivityInfo,
		val change: Double,
		val slopeRadians: Double,
		val distance: Double,
		val speedMPS: Double,
	)

	companion object {
		/** Defense-in-depth speed cap: 50 m/s ≈ 180 km/h. */
		const val MAX_SCORING_SPEED_MPS = 50.0

		private const val EARTH_CIRCUMFERENCE_METERS = 40_075_000.0

		private fun Location.distanceFlatMeters(other: Location): Double {
			val lat1Rad = Math.toRadians(latitude)
			val lat2Rad = Math.toRadians(other.latitude)
			val latDistance = Math.toRadians(other.latitude - latitude)
			val lonDistance = Math.toRadians(other.longitude - longitude)
			val sinLatDistance = sin(latDistance / 2)
			val sinLonDistance = sin(lonDistance / 2)
			val a = sinLatDistance * sinLatDistance +
					cos(lat1Rad) * cos(lat2Rad) * sinLonDistance * sinLonDistance
			val c = 2 * atan2(sqrt(a), sqrt(1 - a))
			return (EARTH_CIRCUMFERENCE_METERS / (2 * Math.PI)) * c
		}
	}
}
