package com.adsamcik.tracker.tracker.control

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Deterministic local-ENU, constant-velocity Kalman filter for horizontal position.
 *
 * It deliberately has no Android dependencies and keeps covariance with the estimate. Inputs are
 * accepted only in monotonic order by [EvidenceLedger]. A large gap resets the filter rather than
 * projecting an apparently precise bridge across unknown travel.
 */
class HorizontalKalmanEstimator(
	private val gapNanos: Long,
	private val nisThreshold: Double,
	private val processAccelerationStdMetersPerSecondSquared: Double = 2.5,
) {
	init {
		require(gapNanos > 0L)
		require(nisThreshold > 0.0 && nisThreshold.isFinite())
		require(
			processAccelerationStdMetersPerSecondSquared > 0.0 &&
				processAccelerationStdMetersPerSecondSquared.isFinite(),
		)
	}

	private data class FilterState(
		val origin: GeoPoint,
		var xMeters: Double,
		var yMeters: Double,
		var vxMetersPerSecond: Double,
		var vyMetersPerSecond: Double,
		var covariance: Array<DoubleArray>,
		var elapsedRealtimeNanos: Long,
	)

	private var state: FilterState? = null

	fun observe(
		observation: ControlEvidence.LocationObservation,
		elapsedRealtimeNanos: Long,
	): HorizontalEstimatorResult {
		if (!observation.ingressAccepted || observation.position == null) {
			return HorizontalEstimatorResult(
				sourceEventId = observation.sourceEventId,
				decision = HorizontalEstimatorDecision.IGNORED,
				reason = observation.rejectionReason ?: "INGRESS_REJECTED",
			)
		}

		val measurement = requireNotNull(observation.position)
		val accuracy = observation.horizontalAccuracyMeters
			?.coerceAtLeast(MINIMUM_ACCURACY_METERS)
			?: DEFAULT_ACCURACY_METERS
		val current = state
		if (current == null) {
			val initialized = initialize(measurement, accuracy, elapsedRealtimeNanos)
			state = initialized
			return initializedResult(observation.sourceEventId, initialized, HorizontalEstimatorDecision.INITIALIZED)
		}

		val deltaNanos = elapsedRealtimeNanos - current.elapsedRealtimeNanos
		if (deltaNanos < 0L) {
			return resultFor(
				sourceEventId = observation.sourceEventId,
				decision = HorizontalEstimatorDecision.REJECTED_OUT_OF_ORDER,
				state = current,
				reason = "NON_MONOTONIC_MEASUREMENT",
			)
		}
		if (deltaNanos > gapNanos) {
			val reset = initialize(measurement, accuracy, elapsedRealtimeNanos)
			state = reset
			return initializedResult(
				sourceEventId = observation.sourceEventId,
				state = reset,
				decision = HorizontalEstimatorDecision.GAP_RESET,
				gapOpened = true,
			)
		}

		val predicted = predict(current, deltaNanos)
		val (measurementX, measurementY) = toEnu(predicted.origin, measurement)
		val innovationX = measurementX - predicted.xMeters
		val innovationY = measurementY - predicted.yMeters
		val measurementVariance = accuracy * accuracy
		val s00 = predicted.covariance[0][0] + measurementVariance
		val s01 = predicted.covariance[0][1]
		val s10 = predicted.covariance[1][0]
		val s11 = predicted.covariance[1][1] + measurementVariance
		val determinant = s00 * s11 - s01 * s10
		if (!determinant.isFinite() || determinant <= MINIMUM_DETERMINANT) {
			state = predicted
			return resultFor(
				sourceEventId = observation.sourceEventId,
				decision = HorizontalEstimatorDecision.REJECTED_OUTLIER,
				state = predicted,
				reason = "SINGULAR_INNOVATION_COVARIANCE",
			)
		}
		val inverseS00 = s11 / determinant
		val inverseS01 = -s01 / determinant
		val inverseS10 = -s10 / determinant
		val inverseS11 = s00 / determinant
		val nis = innovationX * (inverseS00 * innovationX + inverseS01 * innovationY) +
			innovationY * (inverseS10 * innovationX + inverseS11 * innovationY)
		if (!nis.isFinite() || nis > nisThreshold) {
			// Keep the predicted state, but never use the rejected coordinate as a new anchor.
			state = predicted
			return resultFor(
				sourceEventId = observation.sourceEventId,
				decision = HorizontalEstimatorDecision.REJECTED_OUTLIER,
				state = predicted,
				nis = if (nis.isFinite()) nis else null,
				reason = "NIS_GATE",
			)
		}

		val gain = Array(STATE_DIMENSION) { DoubleArray(MEASUREMENT_DIMENSION) }
		for (row in 0 until STATE_DIMENSION) {
			val p0 = predicted.covariance[row][0]
			val p1 = predicted.covariance[row][1]
			gain[row][0] = p0 * inverseS00 + p1 * inverseS10
			gain[row][1] = p0 * inverseS01 + p1 * inverseS11
		}
		predicted.xMeters += gain[0][0] * innovationX + gain[0][1] * innovationY
		predicted.yMeters += gain[1][0] * innovationX + gain[1][1] * innovationY
		predicted.vxMetersPerSecond += gain[2][0] * innovationX + gain[2][1] * innovationY
		predicted.vyMetersPerSecond += gain[3][0] * innovationX + gain[3][1] * innovationY
		predicted.covariance = josephCovariance(predicted.covariance, gain, measurementVariance)
		state = predicted
		return resultFor(
			sourceEventId = observation.sourceEventId,
			decision = HorizontalEstimatorDecision.ACCEPTED,
			state = predicted,
			nis = nis,
		)
	}

	fun reset() {
		state = null
	}

	private fun initialize(
		position: GeoPoint,
		accuracyMeters: Double,
		elapsedRealtimeNanos: Long,
	): FilterState {
		val positionVariance = accuracyMeters * accuracyMeters
		return FilterState(
			origin = position,
			xMeters = 0.0,
			yMeters = 0.0,
			vxMetersPerSecond = 0.0,
			vyMetersPerSecond = 0.0,
			covariance = arrayOf(
				doubleArrayOf(positionVariance, 0.0, 0.0, 0.0),
				doubleArrayOf(0.0, positionVariance, 0.0, 0.0),
				doubleArrayOf(0.0, 0.0, INITIAL_VELOCITY_VARIANCE, 0.0),
				doubleArrayOf(0.0, 0.0, 0.0, INITIAL_VELOCITY_VARIANCE),
			),
			elapsedRealtimeNanos = elapsedRealtimeNanos,
		)
	}

	private fun initializedResult(
		sourceEventId: String,
		state: FilterState,
		decision: HorizontalEstimatorDecision,
		gapOpened: Boolean = false,
	): HorizontalEstimatorResult = resultFor(
		sourceEventId = sourceEventId,
		decision = decision,
		state = state,
		gapOpened = gapOpened,
	)

	private fun predict(current: FilterState, deltaNanos: Long): FilterState {
		val dt = deltaNanos.toDouble() / NANOS_PER_SECOND
		val covariance = multiply4x4(
			multiply4x4(transitionMatrix(dt), current.covariance),
			transpose4x4(transitionMatrix(dt)),
		)
		val accelerationVariance =
			processAccelerationStdMetersPerSecondSquared * processAccelerationStdMetersPerSecondSquared
		val dt2 = dt * dt
		val dt3 = dt2 * dt
		val dt4 = dt2 * dt2
		val qPosition = accelerationVariance * dt4 / 4.0
		val qPositionVelocity = accelerationVariance * dt3 / 2.0
		val qVelocity = accelerationVariance * dt2
		covariance[0][0] += qPosition
		covariance[1][1] += qPosition
		covariance[0][2] += qPositionVelocity
		covariance[2][0] += qPositionVelocity
		covariance[1][3] += qPositionVelocity
		covariance[3][1] += qPositionVelocity
		covariance[2][2] += qVelocity
		covariance[3][3] += qVelocity
		return current.copy(
			xMeters = current.xMeters + current.vxMetersPerSecond * dt,
			yMeters = current.yMeters + current.vyMetersPerSecond * dt,
			covariance = covariance,
			elapsedRealtimeNanos = current.elapsedRealtimeNanos + deltaNanos,
		)
	}

	private fun resultFor(
		sourceEventId: String,
		decision: HorizontalEstimatorDecision,
		state: FilterState,
		nis: Double? = null,
		gapOpened: Boolean = false,
		reason: String? = null,
	): HorizontalEstimatorResult = HorizontalEstimatorResult(
		sourceEventId = sourceEventId,
		decision = decision,
		position = fromEnu(state.origin, state.xMeters, state.yMeters),
		horizontalUncertaintyMeters = uncertaintyMeters(state.covariance),
		speedMetersPerSecond = sqrt(
			state.vxMetersPerSecond * state.vxMetersPerSecond +
				state.vyMetersPerSecond * state.vyMetersPerSecond,
		),
		normalizedInnovationSquared = nis,
		gapOpened = gapOpened,
		reason = reason,
	)

	private fun transitionMatrix(dt: Double): Array<DoubleArray> = arrayOf(
		doubleArrayOf(1.0, 0.0, dt, 0.0),
		doubleArrayOf(0.0, 1.0, 0.0, dt),
		doubleArrayOf(0.0, 0.0, 1.0, 0.0),
		doubleArrayOf(0.0, 0.0, 0.0, 1.0),
	)

	private fun josephCovariance(
		prior: Array<DoubleArray>,
		gain: Array<DoubleArray>,
		measurementVariance: Double,
	): Array<DoubleArray> {
		val a = Array(STATE_DIMENSION) { row ->
			DoubleArray(STATE_DIMENSION) { column ->
				when {
					row == column -> 1.0
					else -> 0.0
				}
			}.also {
				it[0] -= gain[row][0]
				it[1] -= gain[row][1]
			}
		}
		val propagated = multiply4x4(multiply4x4(a, prior), transpose4x4(a))
		for (row in 0 until STATE_DIMENSION) {
			for (column in 0 until STATE_DIMENSION) {
				propagated[row][column] += measurementVariance * (
					gain[row][0] * gain[column][0] + gain[row][1] * gain[column][1]
				)
			}
		}
		// Round-off can break symmetry by a few ULPs; restore it before the next innovation.
		for (row in 0 until STATE_DIMENSION) {
			for (column in row + 1 until STATE_DIMENSION) {
				val symmetric = (propagated[row][column] + propagated[column][row]) / 2.0
				propagated[row][column] = symmetric
				propagated[column][row] = symmetric
			}
			propagated[row][row] = max(propagated[row][row], MINIMUM_VARIANCE)
		}
		return propagated
	}

	private fun toEnu(origin: GeoPoint, point: GeoPoint): Pair<Double, Double> {
		val originLatitudeRadians = origin.latitude * DEGREES_TO_RADIANS
		val east = wrappedLongitudeDeltaDegrees(point.longitude, origin.longitude) *
			DEGREES_TO_RADIANS * longitudeScaleMeters(originLatitudeRadians)
		val north = (point.latitude - origin.latitude) * DEGREES_TO_RADIANS * EARTH_RADIUS_METERS
		return east to north
	}

	private fun fromEnu(origin: GeoPoint, eastMeters: Double, northMeters: Double): GeoPoint {
		val originLatitudeRadians = origin.latitude * DEGREES_TO_RADIANS
		val latitude = origin.latitude + northMeters / EARTH_RADIUS_METERS / DEGREES_TO_RADIANS
		val longitude = normalizeLongitudeDegrees(
			origin.longitude + eastMeters /
				longitudeScaleMeters(originLatitudeRadians) / DEGREES_TO_RADIANS,
		)
		return GeoPoint(latitude, longitude)
	}

	private fun longitudeScaleMeters(latitudeRadians: Double): Double =
		max(EARTH_RADIUS_METERS * abs(cos(latitudeRadians)), MINIMUM_LONGITUDE_SCALE_METERS)

	private fun wrappedLongitudeDeltaDegrees(longitude: Double, reference: Double): Double {
		var delta = longitude - reference
		while (delta > 180.0) delta -= 360.0
		while (delta < -180.0) delta += 360.0
		return delta
	}

	private fun normalizeLongitudeDegrees(longitude: Double): Double {
		var normalized = longitude
		while (normalized > 180.0) normalized -= 360.0
		while (normalized < -180.0) normalized += 360.0
		return normalized
	}

	private fun uncertaintyMeters(covariance: Array<DoubleArray>): Double {
		val xx = covariance[0][0]
		val xy = covariance[0][1]
		val yy = covariance[1][1]
		val maximumEigenvalue = (xx + yy + sqrt((xx - yy) * (xx - yy) + 4.0 * xy * xy)) / 2.0
		return sqrt(max(maximumEigenvalue, 0.0))
	}

	private fun multiply4x4(
		left: Array<DoubleArray>,
		right: Array<DoubleArray>,
	): Array<DoubleArray> = Array(STATE_DIMENSION) { row ->
		DoubleArray(STATE_DIMENSION) { column ->
			var value = 0.0
			for (index in 0 until STATE_DIMENSION) value += left[row][index] * right[index][column]
			value
		}
	}

	private fun transpose4x4(matrix: Array<DoubleArray>): Array<DoubleArray> =
		Array(STATE_DIMENSION) { row -> DoubleArray(STATE_DIMENSION) { column -> matrix[column][row] } }

	private companion object {
		const val STATE_DIMENSION = 4
		const val MEASUREMENT_DIMENSION = 2
		const val NANOS_PER_SECOND = 1_000_000_000.0
		const val EARTH_RADIUS_METERS = 6_371_000.0
		const val DEGREES_TO_RADIANS = 0.017453292519943295
		const val MINIMUM_ACCURACY_METERS = 3.0
		const val DEFAULT_ACCURACY_METERS = 50.0
		const val INITIAL_VELOCITY_VARIANCE = 25.0
		const val MINIMUM_VARIANCE = 1e-9
		const val MINIMUM_DETERMINANT = 1e-12
		const val MINIMUM_LONGITUDE_SCALE_METERS = 1.0
	}
}
