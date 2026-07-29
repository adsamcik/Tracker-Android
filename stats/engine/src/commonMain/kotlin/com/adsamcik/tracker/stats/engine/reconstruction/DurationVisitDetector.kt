package com.adsamcik.tracker.stats.engine.reconstruction

import com.adsamcik.tracker.stats.api.reconstruction.TrajectoryStateEstimate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

data class DurationVisitConfiguration(
	val minimumDurationMs: Long = 2 * 60_000L,
	val maximumRadiusM: Double = 75.0,
	val maximumStationarySpeedMps: Double = 1.0,
	val minimumStationaryProbability: Double = 0.6,
)

data class DetectedVisitInterval(
	val startTimeMs: Long,
	val endTimeMs: Long,
	val startElapsedRealtimeNanos: Long?,
	val endElapsedRealtimeNanos: Long?,
	val clockDomainId: String?,
	val bootClockDomainId: String?,
	val arrivalUncertaintyMs: Long,
	val departureUncertaintyMs: Long,
	val centroidLatE7: Int,
	val centroidLonE7: Int,
	val covarianceEastEastM2: Double,
	val covarianceEastNorthM2: Double,
	val covarianceNorthNorthM2: Double,
	val probability: Double,
)

/**
 * Cadence-independent visit baseline based on elapsed duration rather than sample counts.
 */
class DurationVisitDetector(
	private val configuration: DurationVisitConfiguration = DurationVisitConfiguration(),
) {
	fun detect(states: List<TrajectoryStateEstimate>): List<DetectedVisitInterval> {
		if (states.isEmpty()) return emptyList()
		val visits = mutableListOf<DetectedVisitInterval>()
		val candidate = mutableListOf<TrajectoryStateEstimate>()
		for (state in states) {
			val stationary = state.stationaryProbability >= configuration.minimumStationaryProbability &&
				state.speedMps <= configuration.maximumStationarySpeedMps
			val crossesTimeDomain =
				candidate.isNotEmpty() && !sharesTimeDomain(candidate.last(), state)
			if (
				!stationary ||
				crossesTimeDomain ||
				candidate.isNotEmpty() && exceedsRadius(candidate, state)
			) {
				finalize(candidate)?.let(visits::add)
				candidate.clear()
			}
			if (stationary) candidate += state
		}
		finalize(candidate)?.let(visits::add)
		return visits
	}

	private fun finalize(candidate: List<TrajectoryStateEstimate>): DetectedVisitInterval? {
		if (candidate.isEmpty()) return null
		val first = candidate.first()
		val last = candidate.last()
		if (durationMs(first, last) < configuration.minimumDurationMs) return null
		val latitude = candidate.map { it.latitudeE7.toLong() }.average().toInt()
		val longitude = centroidLongitudeE7(candidate).toInt()
		val averageProbability = candidate.map(TrajectoryStateEstimate::stationaryProbability).average()
		val averageCovarianceEe =
			candidate.map(TrajectoryStateEstimate::positionCovarianceEastEastM2).average()
		val averageCovarianceEn =
			candidate.map(TrajectoryStateEstimate::positionCovarianceEastNorthM2).average()
		val averageCovarianceNn =
			candidate.map(TrajectoryStateEstimate::positionCovarianceNorthNorthM2).average()
		val arrivalUncertainty = if (candidate.size > 1) {
			durationMs(first, candidate[1]).coerceAtLeast(0L) / 2
		} else {
			configuration.minimumDurationMs / 2
		}
		val departureUncertainty = if (candidate.size > 1) {
			durationMs(candidate[candidate.lastIndex - 1], last).coerceAtLeast(0L) / 2
		} else {
			configuration.minimumDurationMs / 2
		}
		return DetectedVisitInterval(
			startTimeMs = first.epochMs,
			endTimeMs = last.epochMs,
			startElapsedRealtimeNanos = first.elapsedRealtimeNanos,
			endElapsedRealtimeNanos = last.elapsedRealtimeNanos,
			clockDomainId = first.clockDomainId,
			bootClockDomainId = first.bootClockDomainId,
			arrivalUncertaintyMs = arrivalUncertainty,
			departureUncertaintyMs = departureUncertainty,
			centroidLatE7 = latitude,
			centroidLonE7 = longitude,
			covarianceEastEastM2 = averageCovarianceEe,
			covarianceEastNorthM2 = averageCovarianceEn,
			covarianceNorthNorthM2 = averageCovarianceNn,
			probability = averageProbability.coerceIn(0.0, 1.0),
		)
	}

	private fun exceedsRadius(
		candidate: List<TrajectoryStateEstimate>,
		next: TrajectoryStateEstimate,
	): Boolean {
		val centroidLat = candidate.map { it.latitudeE7.toLong() }.average()
		val centroidLon = centroidLongitudeE7(candidate)
		val latitudeRadians = centroidLat / 1e7 * PI / 180.0
		val north = (next.latitudeE7 - centroidLat) / 1e7 * PI / 180.0 * EARTH_RADIUS_M
		val east = wrappedLongitudeDeltaDegrees(next.longitudeE7 / 1e7, centroidLon / 1e7) *
			PI / 180.0 *
			EARTH_RADIUS_M * cos(latitudeRadians)
		val measurementRadius = sqrt(east * east + north * north)
		val uncertaintyAllowance = sqrt(
			(next.positionCovarianceEastEastM2 + next.positionCovarianceNorthNorthM2)
				.coerceAtLeast(0.0),
		)
		return measurementRadius > configuration.maximumRadiusM + uncertaintyAllowance
	}

	private fun centroidLongitudeE7(candidate: List<TrajectoryStateEstimate>): Double {
		val referenceDegrees = candidate.first().longitudeE7 / 1e7
		val averageDegrees = referenceDegrees + candidate
			.map { wrappedLongitudeDeltaDegrees(it.longitudeE7 / 1e7, referenceDegrees) }
			.average()
		return normalizeLongitudeDegrees(averageDegrees) * 1e7
	}

	private fun durationMs(
		first: TrajectoryStateEstimate,
		second: TrajectoryStateEstimate,
	): Long {
		if (sharesTimeDomain(first, second)) {
			val firstElapsed = first.elapsedRealtimeNanos
			val secondElapsed = second.elapsedRealtimeNanos
			if (firstElapsed != null && secondElapsed != null) {
				return (secondElapsed - firstElapsed) / NANOS_PER_MILLISECOND
			}
		}
		return second.epochMs - first.epochMs
	}

	private fun sharesTimeDomain(
		first: TrajectoryStateEstimate,
		second: TrajectoryStateEstimate,
	): Boolean = when {
		first.bootClockDomainId != null || second.bootClockDomainId != null ->
			first.bootClockDomainId != null &&
				first.bootClockDomainId == second.bootClockDomainId
		first.clockDomainId != null || second.clockDomainId != null ->
			first.clockDomainId != null &&
				first.clockDomainId == second.clockDomainId
		else -> true
	}

	private companion object {
		const val EARTH_RADIUS_M = 6_378_137.0
		const val NANOS_PER_MILLISECOND = 1_000_000L
	}
}
