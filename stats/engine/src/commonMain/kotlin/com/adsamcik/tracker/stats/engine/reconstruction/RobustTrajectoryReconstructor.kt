package com.adsamcik.tracker.stats.engine.reconstruction

import com.adsamcik.tracker.stats.api.reconstruction.LocationReconstructionObservation
import com.adsamcik.tracker.stats.api.reconstruction.ObservationHealth
import com.adsamcik.tracker.stats.api.reconstruction.TrajectoryEstimateKind
import com.adsamcik.tracker.stats.api.reconstruction.TrajectoryReconstructionResult
import com.adsamcik.tracker.stats.api.reconstruction.TrajectoryStateEstimate
import com.adsamcik.tracker.stats.api.reconstruction.WeightedLocationObservation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class TrajectoryReconstructionConfiguration(
	val accelerationNoiseMps2: Double = 3.0,
	val initialVelocitySigmaMps: Double = 15.0,
	val stationaryVelocitySigmaMps: Double = 0.35,
	val robustPositionNisThreshold: Double = 9.21,
	val maximumPredictionGapSeconds: Double = 300.0,
	val algorithmVersion: String = "robust_cv_rts_v1",
	val configurationVersion: String = "default_v1",
)

/**
 * Robust constant-velocity Kalman filter followed by a full-session RTS backward pass.
 *
 * It is deliberately pure Kotlin and has no Android or database dependency, so recorded evidence
 * can be replayed byte-for-byte on JVM and Android. The live tracker remains untouched.
 */
class RobustTrajectoryReconstructor(
	private val qualityAssessor: LocationQualityAssessor = LocationQualityAssessor(),
	private val configuration: TrajectoryReconstructionConfiguration =
		TrajectoryReconstructionConfiguration(),
) {
	val algorithmVersion: String
		get() = configuration.algorithmVersion

	val configurationVersion: String
		get() = configuration.configurationVersion

	fun reconstruct(
		observations: List<LocationReconstructionObservation>,
	): TrajectoryReconstructionResult {
		if (observations.isEmpty()) return emptyResult()
		val weighted = qualityAssessor.assessAll(observations)
		val usable = weighted.filter { it.informationWeight > 0.0 }
		if (usable.isEmpty()) {
			return emptyResult(rejected = weighted.mapTo(linkedSetOf()) { it.observation.sourceId })
		}

		val filtered = ArrayList<TrajectoryStateEstimate>(usable.size)
		val smoothed = ArrayList<TrajectoryStateEstimate>(usable.size)
		for (segment in splitIntoContinuousSegments(usable)) {
			val plane = LocalPlane(segment.first().observation)
			val frames = ArrayList<FilterFrame>(segment.size)
			var previous: FilterFrame? = null
			for (measurement in segment) {
				val point = plane.toMeters(measurement.observation)
				val frame = if (previous == null) {
					initialFrame(measurement, point)
				} else {
					nextFrame(previous, measurement, point)
				}
				frames += frame
				previous = frame
			}
			val smoothedStates = smooth(frames)
			filtered += frames.map { frame ->
				frame.toEstimate(
					plane,
					frame.filteredState,
					frame.filteredCovariance,
					TrajectoryEstimateKind.FILTERED,
				)
			}
			smoothed += frames.mapIndexed { index, frame ->
				frame.toEstimate(
					plane,
					smoothedStates[index].first,
					smoothedStates[index].second,
					TrajectoryEstimateKind.SMOOTHED,
				)
			}
		}

		return TrajectoryReconstructionResult(
			algorithmVersion = configuration.algorithmVersion,
			configurationVersion = configuration.configurationVersion,
			filtered = filtered,
			smoothed = smoothed,
			rejectedSourceIds = weighted
				.filter { it.informationWeight == 0.0 }
				.mapTo(linkedSetOf()) { it.observation.sourceId },
		)
	}

	private fun initialFrame(
		measurement: WeightedLocationObservation,
		point: Point,
	): FilterFrame {
		val observation = measurement.observation
		val state = doubleArrayOf(point.eastM, point.northM, 0.0, 0.0)
		val platformSpeedMps = observation.platformSpeedMps
		val bearingDeg = observation.bearingDeg
		val bearingAccuracyDeg = observation.bearingAccuracyDeg
		if (
			platformSpeedMps != null &&
			bearingDeg != null &&
			bearingAccuracyDeg?.let { it <= 90.0 } == true
		) {
			val bearingRadians = bearingDeg * PI / 180.0
			state[EAST_VELOCITY] = platformSpeedMps * sin(bearingRadians)
			state[NORTH_VELOCITY] = platformSpeedMps * cos(bearingRadians)
		}
		val positionVariance = square(
			measurement.horizontalSigmaM / sqrt(max(measurement.informationWeight, MINIMUM_WEIGHT)),
		)
		val covariance = diagonal(
			positionVariance,
			positionVariance,
			square(configuration.initialVelocitySigmaMps),
			square(configuration.initialVelocitySigmaMps),
		)
		return FilterFrame(
			measurement = measurement,
			predictedState = state.copyOf(),
			predictedCovariance = covariance.copyOf(),
			filteredState = state,
			filteredCovariance = covariance,
			transitionFromPrevious = identity(),
		)
	}

	private fun nextFrame(
		previous: FilterFrame,
		measurement: WeightedLocationObservation,
		point: Point,
	): FilterFrame {
		val dt = requireNotNull(
			elapsedSeconds(previous.measurement.observation, measurement.observation),
		).coerceAtLeast(MINIMUM_DT_SECONDS)
		val transition = transition(dt)
		val predictedState = multiplyVector(transition, previous.filteredState)
		val predictedCovariance = add(
			multiplyMatrix(
				multiplyMatrix(transition, previous.filteredCovariance),
				transpose(transition),
			),
			processNoise(dt),
		)
		val positionVariance = square(measurement.horizontalSigmaM) /
			max(measurement.informationWeight, MINIMUM_WEIGHT)
		val residualEast = point.eastM - predictedState[EAST_POSITION]
		val residualNorth = point.northM - predictedState[NORTH_POSITION]
		val innovation00 = predictedCovariance[index(EAST_POSITION, EAST_POSITION)] + positionVariance
		val innovation01 = predictedCovariance[index(EAST_POSITION, NORTH_POSITION)]
		val innovation11 = predictedCovariance[index(NORTH_POSITION, NORTH_POSITION)] + positionVariance
		val inverseInnovation = inverse2(innovation00, innovation01, innovation11)
		val nis = if (inverseInnovation == null) {
			configuration.robustPositionNisThreshold
		} else {
			residualEast * (inverseInnovation.first * residualEast + inverseInnovation.second * residualNorth) +
				residualNorth * (inverseInnovation.second * residualEast + inverseInnovation.third * residualNorth)
		}
		val robustVariance = positionVariance * max(
			1.0,
			nis / configuration.robustPositionNisThreshold,
		)
		var stateAndCovariance = updatePair(
			predictedState,
			predictedCovariance,
			EAST_POSITION,
			NORTH_POSITION,
			point.eastM,
			point.northM,
			robustVariance,
		)

		val observation = measurement.observation
		val platformSpeedMps = observation.platformSpeedMps
		val bearingDeg = observation.bearingDeg
		val bearingAccuracyDeg = observation.bearingAccuracyDeg
		if (
			platformSpeedMps != null &&
			bearingDeg != null &&
			bearingAccuracyDeg?.let { it <= 90.0 } == true
		) {
			val bearingRadians = bearingDeg * PI / 180.0
			val eastVelocity = platformSpeedMps * sin(bearingRadians)
			val northVelocity = platformSpeedMps * cos(bearingRadians)
			val bearingSigmaRadians =
				bearingAccuracyDeg.coerceAtLeast(1.0) * PI / 180.0
			val velocitySigma = sqrt(
				square(observation.platformSpeedAccuracyMps ?: 2.5) +
					square(platformSpeedMps * bearingSigmaRadians),
			)
			stateAndCovariance = updatePair(
				stateAndCovariance.first,
				stateAndCovariance.second,
				EAST_VELOCITY,
				NORTH_VELOCITY,
				eastVelocity,
				northVelocity,
				square(velocitySigma.coerceAtLeast(0.25)),
			)
		}
		if (measurement.stationaryProbability >= 0.75) {
			val stationaryVariance = square(configuration.stationaryVelocitySigmaMps) /
				measurement.stationaryProbability
			stateAndCovariance = updatePair(
				stateAndCovariance.first,
				stateAndCovariance.second,
				EAST_VELOCITY,
				NORTH_VELOCITY,
				0.0,
				0.0,
				stationaryVariance,
			)
		}
		return FilterFrame(
			measurement = measurement,
			predictedState = predictedState,
			predictedCovariance = predictedCovariance,
			filteredState = stateAndCovariance.first,
			filteredCovariance = stateAndCovariance.second,
			transitionFromPrevious = transition,
		)
	}

	private fun smooth(frames: List<FilterFrame>): List<Pair<DoubleArray, DoubleArray>> {
		val output = MutableList(frames.size) { index ->
			frames[index].filteredState.copyOf() to frames[index].filteredCovariance.copyOf()
		}
		for (index in frames.lastIndex - 1 downTo 0) {
			val current = frames[index]
			val next = frames[index + 1]
			val inversePredicted = inverse(next.predictedCovariance) ?: continue
			val gain = multiplyMatrix(
				multiplyMatrix(current.filteredCovariance, transpose(next.transitionFromPrevious)),
				inversePredicted,
			)
			val nextSmoothed = output[index + 1]
			val stateCorrection = multiplyVector(
				gain,
				subtract(nextSmoothed.first, next.predictedState),
			)
			val smoothedState = add(current.filteredState, stateCorrection)
			val smoothedCovariance = add(
				current.filteredCovariance,
				multiplyMatrix(
					multiplyMatrix(gain, subtract(nextSmoothed.second, next.predictedCovariance)),
					transpose(gain),
				),
			)
			output[index] = smoothedState to symmetrize(smoothedCovariance)
		}
		return output
	}

	private fun FilterFrame.toEstimate(
		plane: LocalPlane,
		state: DoubleArray,
		covariance: DoubleArray,
		kind: TrajectoryEstimateKind,
	): TrajectoryStateEstimate {
		val coordinate = plane.toE7(state[EAST_POSITION], state[NORTH_POSITION])
		return TrajectoryStateEstimate(
			sourceId = measurement.observation.sourceId,
			epochMs = measurement.observation.epochMs,
			elapsedRealtimeNanos = measurement.observation.elapsedRealtimeNanos,
			clockDomainId = measurement.observation.clockDomainId,
			bootClockDomainId = measurement.observation.bootClockDomainId,
			latitudeE7 = coordinate.first,
			longitudeE7 = coordinate.second,
			velocityEastMps = state[EAST_VELOCITY],
			velocityNorthMps = state[NORTH_VELOCITY],
			positionCovarianceEastEastM2 =
				covariance[index(EAST_POSITION, EAST_POSITION)].coerceAtLeast(0.0),
			positionCovarianceEastNorthM2 =
				covariance[index(EAST_POSITION, NORTH_POSITION)],
			positionCovarianceNorthNorthM2 =
				covariance[index(NORTH_POSITION, NORTH_POSITION)].coerceAtLeast(0.0),
			stationaryProbability = measurement.stationaryProbability,
			kind = kind,
			observationHealth = measurement.health,
			observationWeight = measurement.informationWeight,
		)
	}

	private fun updatePair(
		state: DoubleArray,
		covariance: DoubleArray,
		firstIndex: Int,
		secondIndex: Int,
		firstValue: Double,
		secondValue: Double,
		variance: Double,
	): Pair<DoubleArray, DoubleArray> {
		val s00 = covariance[index(firstIndex, firstIndex)] + variance
		val s01 = covariance[index(firstIndex, secondIndex)]
		val s11 = covariance[index(secondIndex, secondIndex)] + variance
		val inverse = inverse2(s00, s01, s11) ?: return state to covariance
		val gain = Array(STATE_SIZE) { row ->
			doubleArrayOf(
				covariance[index(row, firstIndex)] * inverse.first +
					covariance[index(row, secondIndex)] * inverse.second,
				covariance[index(row, firstIndex)] * inverse.second +
					covariance[index(row, secondIndex)] * inverse.third,
			)
		}
		val residual0 = firstValue - state[firstIndex]
		val residual1 = secondValue - state[secondIndex]
		val updatedState = DoubleArray(STATE_SIZE) { row ->
			state[row] + gain[row][0] * residual0 + gain[row][1] * residual1
		}
		val kh = DoubleArray(MATRIX_SIZE)
		for (row in 0 until STATE_SIZE) {
			kh[index(row, firstIndex)] = gain[row][0]
			kh[index(row, secondIndex)] = gain[row][1]
		}
		val identityMinusKh = subtract(identity(), kh)
		// Joseph form preserves positive semi-definiteness under floating-point error.
		val updatedCovariance = add(
			multiplyMatrix(
				multiplyMatrix(identityMinusKh, covariance),
				transpose(identityMinusKh),
			),
			measurementNoiseContribution(gain, variance),
		)
		return updatedState to symmetrize(updatedCovariance)
	}

	private fun measurementNoiseContribution(
		gain: Array<DoubleArray>,
		variance: Double,
	): DoubleArray = DoubleArray(MATRIX_SIZE) { flatIndex ->
		val row = flatIndex / STATE_SIZE
		val column = flatIndex % STATE_SIZE
		variance * (gain[row][0] * gain[column][0] + gain[row][1] * gain[column][1])
	}

	private fun processNoise(dt: Double): DoubleArray {
		val q = square(configuration.accelerationNoiseMps2)
		val dt2 = dt * dt
		val dt3 = dt2 * dt
		val dt4 = dt2 * dt2
		return doubleArrayOf(
			q * dt4 / 4, 0.0, q * dt3 / 2, 0.0,
			0.0, q * dt4 / 4, 0.0, q * dt3 / 2,
			q * dt3 / 2, 0.0, q * dt2, 0.0,
			0.0, q * dt3 / 2, 0.0, q * dt2,
		)
	}

	private fun transition(dt: Double): DoubleArray = doubleArrayOf(
		1.0, 0.0, dt, 0.0,
		0.0, 1.0, 0.0, dt,
		0.0, 0.0, 1.0, 0.0,
		0.0, 0.0, 0.0, 1.0,
	)

	private fun splitIntoContinuousSegments(
		measurements: List<WeightedLocationObservation>,
	): List<List<WeightedLocationObservation>> {
		val segments = mutableListOf<MutableList<WeightedLocationObservation>>()
		for (measurement in measurements) {
			val current = segments.lastOrNull()
			val previous = current?.lastOrNull()
			val deltaSeconds = previous?.let {
				elapsedSeconds(it.observation, measurement.observation)
			}
			if (
				current == null ||
				deltaSeconds == null ||
				deltaSeconds <= 0.0 ||
				deltaSeconds > configuration.maximumPredictionGapSeconds
			) {
				segments += mutableListOf(measurement)
			} else {
				current += measurement
			}
		}
		return segments
	}

	private fun elapsedSeconds(
		previous: LocationReconstructionObservation,
		current: LocationReconstructionObservation,
	): Double? {
		if (!sharesTimeDomain(previous, current)) return null
		if (
			previous.elapsedRealtimeNanos != null &&
			current.elapsedRealtimeNanos != null
		) {
			val currentElapsed = requireNotNull(current.elapsedRealtimeNanos)
			val previousElapsed = requireNotNull(previous.elapsedRealtimeNanos)
			return (currentElapsed - previousElapsed) / 1e9
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

	private fun emptyResult(rejected: Set<String> = emptySet()) = TrajectoryReconstructionResult(
		algorithmVersion = configuration.algorithmVersion,
		configurationVersion = configuration.configurationVersion,
		filtered = emptyList(),
		smoothed = emptyList(),
		rejectedSourceIds = rejected,
	)

	private data class FilterFrame(
		val measurement: WeightedLocationObservation,
		val predictedState: DoubleArray,
		val predictedCovariance: DoubleArray,
		val filteredState: DoubleArray,
		val filteredCovariance: DoubleArray,
		val transitionFromPrevious: DoubleArray,
	)

	private data class Point(val eastM: Double, val northM: Double)

	private class LocalPlane(reference: LocationReconstructionObservation) {
		private val latitude0Deg = reference.latitudeE7 / 1e7
		private val longitude0Deg = reference.longitudeE7 / 1e7
		private val longitudeScale = (
			EARTH_RADIUS_M * kotlin.math.abs(cos(latitude0Deg * PI / 180.0))
			).coerceAtLeast(MINIMUM_LONGITUDE_SCALE_M)

		fun toMeters(observation: LocationReconstructionObservation): Point = Point(
			eastM = wrappedLongitudeDeltaDegrees(
				observation.longitudeE7 / 1e7,
				longitude0Deg,
			) * PI / 180.0 *
				longitudeScale,
			northM = (observation.latitudeE7 / 1e7 - latitude0Deg) * PI / 180.0 *
				EARTH_RADIUS_M,
		)

		fun toE7(eastM: Double, northM: Double): Pair<Int, Int> {
			val latitude = (
				latitude0Deg + northM / EARTH_RADIUS_M * 180.0 / PI
				).coerceIn(-90.0, 90.0)
			val longitude = normalizeLongitudeDegrees(
				longitude0Deg + eastM / longitudeScale * 180.0 / PI,
			)
			return (latitude * 1e7).roundToInt() to (longitude * 1e7).roundToInt()
		}
	}

	private data class SymmetricInverse2(
		val first: Double,
		val second: Double,
		val third: Double,
	)

	private fun inverse2(a: Double, b: Double, d: Double): SymmetricInverse2? {
		val determinant = a * d - b * b
		if (!determinant.isFinite() || determinant <= MATRIX_EPSILON) return null
		return SymmetricInverse2(d / determinant, -b / determinant, a / determinant)
	}

	private fun inverse(matrix: DoubleArray): DoubleArray? {
		val augmented = Array(STATE_SIZE) { row ->
			DoubleArray(STATE_SIZE * 2) { column ->
				when {
					column < STATE_SIZE -> matrix[index(row, column)]
					column - STATE_SIZE == row -> 1.0
					else -> 0.0
				}
			}
		}
		for (column in 0 until STATE_SIZE) {
			var pivot = column
			for (row in column + 1 until STATE_SIZE) {
				if (kotlin.math.abs(augmented[row][column]) >
					kotlin.math.abs(augmented[pivot][column])
				) {
					pivot = row
				}
			}
			if (kotlin.math.abs(augmented[pivot][column]) <= MATRIX_EPSILON) return null
			if (pivot != column) {
				val swap = augmented[pivot]
				augmented[pivot] = augmented[column]
				augmented[column] = swap
			}
			val scale = augmented[column][column]
			for (entry in 0 until STATE_SIZE * 2) augmented[column][entry] /= scale
			for (row in 0 until STATE_SIZE) {
				if (row == column) continue
				val factor = augmented[row][column]
				for (entry in 0 until STATE_SIZE * 2) {
					augmented[row][entry] -= factor * augmented[column][entry]
				}
			}
		}
		return DoubleArray(MATRIX_SIZE) { flatIndex ->
			augmented[flatIndex / STATE_SIZE][STATE_SIZE + flatIndex % STATE_SIZE]
		}
	}

	private fun multiplyVector(matrix: DoubleArray, vector: DoubleArray): DoubleArray =
		DoubleArray(STATE_SIZE) { row ->
			var value = 0.0
			for (column in 0 until STATE_SIZE) {
				value += matrix[index(row, column)] * vector[column]
			}
			value
		}

	private fun multiplyMatrix(first: DoubleArray, second: DoubleArray): DoubleArray =
		DoubleArray(MATRIX_SIZE) { flatIndex ->
			val row = flatIndex / STATE_SIZE
			val column = flatIndex % STATE_SIZE
			var value = 0.0
			for (inner in 0 until STATE_SIZE) {
				value += first[index(row, inner)] * second[index(inner, column)]
			}
			value
		}

	private fun transpose(matrix: DoubleArray): DoubleArray =
		DoubleArray(MATRIX_SIZE) { flatIndex ->
			matrix[index(flatIndex % STATE_SIZE, flatIndex / STATE_SIZE)]
		}

	private fun add(first: DoubleArray, second: DoubleArray): DoubleArray =
		DoubleArray(first.size) { first[it] + second[it] }

	private fun subtract(first: DoubleArray, second: DoubleArray): DoubleArray =
		DoubleArray(first.size) { first[it] - second[it] }

	private fun symmetrize(matrix: DoubleArray): DoubleArray = matrix.copyOf().also { result ->
		for (row in 0 until STATE_SIZE) {
			for (column in row + 1 until STATE_SIZE) {
				val average = (matrix[index(row, column)] + matrix[index(column, row)]) / 2.0
				result[index(row, column)] = average
				result[index(column, row)] = average
			}
		}
	}

	private fun diagonal(a: Double, b: Double, c: Double, d: Double): DoubleArray =
		doubleArrayOf(
			a, 0.0, 0.0, 0.0,
			0.0, b, 0.0, 0.0,
			0.0, 0.0, c, 0.0,
			0.0, 0.0, 0.0, d,
		)

	private fun identity(): DoubleArray = diagonal(1.0, 1.0, 1.0, 1.0)

	private fun index(row: Int, column: Int): Int = row * STATE_SIZE + column

	private fun square(value: Double): Double = value * value

	private companion object {
		const val STATE_SIZE = 4
		const val MATRIX_SIZE = STATE_SIZE * STATE_SIZE
		const val EAST_POSITION = 0
		const val NORTH_POSITION = 1
		const val EAST_VELOCITY = 2
		const val NORTH_VELOCITY = 3
		const val EARTH_RADIUS_M = 6_378_137.0
		const val MINIMUM_WEIGHT = 0.01
		const val MINIMUM_DT_SECONDS = 0.001
		const val MINIMUM_LONGITUDE_SCALE_M = 1.0
		const val MATRIX_EPSILON = 1e-12
	}
}
