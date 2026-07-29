package com.adsamcik.tracker.stats.engine.reconstruction

import com.adsamcik.tracker.stats.api.reconstruction.LocationReconstructionObservation
import com.adsamcik.tracker.stats.api.reconstruction.ObservationHealth
import com.adsamcik.tracker.stats.api.reconstruction.ObservationQualityReason
import com.adsamcik.tracker.stats.api.reconstruction.WeightedLocationObservation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

data class LocationQualityConfiguration(
	val minimumSigmaM: Double = 3.0,
	val missingAccuracySigmaM: Double = 75.0,
	val approximatePermissionSigmaM: Double = 500.0,
	val poorAccuracyThresholdM: Double = 100.0,
	val staleThresholdMs: Long = 120_000L,
	val frozenThresholdMs: Long = 30_000L,
	val maximumFeasibleSpeedMps: Double = 140.0,
	val timingMotionAllowanceMps: Double = 35.0,
)

/**
 * Deterministic, conservative measurement-quality layer.
 *
 * Weak but valid observations remain usable with inflated covariance. Only evidence that is mock,
 * invalid at ingress, or physically impossible receives zero information weight.
 */
class LocationQualityAssessor(
	private val configuration: LocationQualityConfiguration = LocationQualityConfiguration(),
) {
	fun assess(
		observation: LocationReconstructionObservation,
		previous: LocationReconstructionObservation? = null,
	): WeightedLocationObservation {
		val reasons = linkedSetOf<ObservationQualityReason>()
		if (observation.isMock) reasons += ObservationQualityReason.MOCK_PROVIDER
		if (observation.ingressDisposition != "DELIVERED_VALID") {
			reasons += ObservationQualityReason.INVALID_INGRESS
		}

		var sigma = observation.horizontalAccuracyM
			?.takeIf { it > 0.0 }
			?.coerceAtLeast(configuration.minimumSigmaM)
			?: configuration.missingAccuracySigmaM.also {
				reasons += ObservationQualityReason.MISSING_ACCURACY
			}

		if (sigma >= configuration.poorAccuracyThresholdM) {
			reasons += ObservationQualityReason.POOR_ACCURACY
		}
		if (observation.permissionPrecision == "APPROXIMATE") {
			sigma = max(sigma, configuration.approximatePermissionSigmaM)
			reasons += ObservationQualityReason.APPROXIMATE_PERMISSION
		}
		if (observation.batchSize > 1) {
			// Samples in one callback share radio/GNSS state and are not independent.
			sigma *= sqrt(observation.batchSize.toDouble())
			reasons += ObservationQualityReason.BATCH_CORRELATION
		}
		val ageMs = observation.sourceAgeMs
		if (ageMs != null && ageMs > 0L) {
			sigma = sqrt(
				sigma * sigma +
					square(configuration.timingMotionAllowanceMps * ageMs / 1_000.0),
			)
			if (ageMs >= configuration.staleThresholdMs) {
				reasons += ObservationQualityReason.STALE_SOURCE
			}
		}
		observation.timeUncertaintyMs?.takeIf { it > 0L }?.let { uncertaintyMs ->
			sigma = sqrt(
				sigma * sigma +
					square(configuration.timingMotionAllowanceMps * uncertaintyMs / 1_000.0),
			)
		}

		if (previous != null) {
			val deltaSeconds = elapsedSeconds(previous, observation)
			if (deltaSeconds == null || deltaSeconds <= 0.0) {
				reasons += ObservationQualityReason.NON_MONOTONIC_TIME
			} else {
				val distanceM = distanceMeters(previous, observation)
				val impliedSpeed = distanceM / deltaSeconds
				if (impliedSpeed > configuration.maximumFeasibleSpeedMps) {
					reasons += ObservationQualityReason.MOTION_INFEASIBLE
				}
				if (
					distanceM < 0.25 &&
					deltaSeconds * 1_000.0 >= configuration.frozenThresholdMs
				) {
					reasons += ObservationQualityReason.DUPLICATE_OR_FROZEN
				}
			}
		}
		val bearingDeg = observation.bearingDeg
		val bearingAccuracyDeg = observation.bearingAccuracyDeg
		if (bearingDeg != null && (bearingAccuracyDeg == null || bearingAccuracyDeg > 90.0)) {
			reasons += ObservationQualityReason.UNRELIABLE_BEARING
		}

		val rejected = ObservationQualityReason.MOCK_PROVIDER in reasons ||
			ObservationQualityReason.INVALID_INGRESS in reasons ||
			ObservationQualityReason.MOTION_INFEASIBLE in reasons
		val weight = when {
			rejected -> 0.0
			ObservationQualityReason.NON_MONOTONIC_TIME in reasons -> 0.05
			ObservationQualityReason.STALE_SOURCE in reasons -> 0.1
			ObservationQualityReason.DUPLICATE_OR_FROZEN in reasons -> 0.2
			ObservationQualityReason.POOR_ACCURACY in reasons -> 0.35
			ObservationQualityReason.APPROXIMATE_PERMISSION in reasons -> 0.25
			else -> 1.0
		}
		val health = when {
			rejected -> ObservationHealth.REJECTED
			ObservationQualityReason.STALE_SOURCE in reasons -> ObservationHealth.STALE
			reasons.isNotEmpty() -> ObservationHealth.DEGRADED
			else -> ObservationHealth.HEALTHY
		}
		return WeightedLocationObservation(
			observation = observation,
			horizontalSigmaM = sigma,
			informationWeight = weight,
			stationaryProbability = stationaryProbability(observation),
			health = health,
			reasons = reasons,
		)
	}

	fun assessAll(
		observations: List<LocationReconstructionObservation>,
	): List<WeightedLocationObservation> {
		var previous: LocationReconstructionObservation? = null
		return observations.map { observation ->
			if (previous != null && !sharesTimeDomain(requireNotNull(previous), observation)) {
				previous = null
			}
			assess(observation, previous).also {
				if (it.health != ObservationHealth.REJECTED) previous = observation
			}
		}
	}

	private fun stationaryProbability(observation: LocationReconstructionObservation): Double {
		val activityProbability = when (observation.activity?.lowercase()) {
			"still" -> 0.98
			"walking", "running", "on_foot", "on_bicycle", "in_vehicle" -> 0.05
			else -> 0.5
		}
		val speedProbability = when (val speed = observation.platformSpeedMps) {
			null -> 0.5
			in 0.0..0.25 -> 0.98
			in 0.25..1.0 -> 0.65
			else -> 0.05
		}
		val stepProbability = when {
			observation.stepDelta == null -> 0.5
			observation.stepDelta == 0 -> 0.85
			else -> 0.05
		}
		return (0.45 * activityProbability + 0.4 * speedProbability + 0.15 * stepProbability)
			.coerceIn(0.0, 1.0)
	}

	private fun elapsedSeconds(
		previous: LocationReconstructionObservation,
		current: LocationReconstructionObservation,
	): Double? {
		if (!sharesTimeDomain(previous, current)) return null
		val previousElapsedRealtimeNanos = previous.elapsedRealtimeNanos
		val currentElapsedRealtimeNanos = current.elapsedRealtimeNanos
		if (previousElapsedRealtimeNanos != null && currentElapsedRealtimeNanos != null) {
			return (currentElapsedRealtimeNanos - previousElapsedRealtimeNanos) / 1e9
		}
		return (current.epochMs - previous.epochMs) / 1_000.0
	}

	private fun sharesTimeDomain(
		previous: LocationReconstructionObservation,
		current: LocationReconstructionObservation,
	): Boolean = when {
		previous.bootClockDomainId != null || current.bootClockDomainId != null ->
			previous.bootClockDomainId != null &&
				previous.bootClockDomainId == current.bootClockDomainId
		previous.clockDomainId != null || current.clockDomainId != null ->
			previous.clockDomainId != null &&
				previous.clockDomainId == current.clockDomainId
		else -> true
	}

	private fun distanceMeters(
		first: LocationReconstructionObservation,
		second: LocationReconstructionObservation,
	): Double {
		val latitudeRadians = (first.latitudeE7 + second.latitudeE7) / 2e7 * PI / 180.0
		val north = (second.latitudeE7 - first.latitudeE7) / 1e7 * PI / 180.0 * EARTH_RADIUS_M
		val longitudeDelta = wrappedLongitudeDeltaDegrees(
			second.longitudeE7 / 1e7,
			first.longitudeE7 / 1e7,
		)
		val east = longitudeDelta * PI / 180.0 *
			EARTH_RADIUS_M * cos(latitudeRadians)
		return sqrt(east * east + north * north)
	}

	private fun square(value: Double): Double = value * value

	private companion object {
		const val EARTH_RADIUS_M = 6_378_137.0
	}
}
