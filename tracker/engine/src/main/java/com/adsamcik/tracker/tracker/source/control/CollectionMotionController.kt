package com.adsamcik.tracker.tracker.source.control

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.CellSnapshotPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.PressureWindowPayload
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fuses cheap, independent evidence before changing collection cost.
 *
 * Missing location fixes are deliberately not evidence of being stationary. In particular, a
 * vehicle classification survives a GPS outage (for example a tunnel) and can be extended by
 * activity, cell handovers, pressure changes, or a later location fix.
 */
@Singleton
class CollectionMotionController @Inject constructor() {
	private val lock = Any()
	private val _policy = MutableStateFlow(CollectionMotionPolicy.unrestricted())
	val policy: StateFlow<CollectionMotionPolicy> = _policy.asStateFlow()

	private var session: ActiveMotionSession? = null

	fun startSession(
		logicalTrackingId: String,
		serviceRunId: String,
		startedElapsedRealtimeNanos: Long,
		initialMotion: Boolean = false,
	) = synchronized(lock) {
		require(logicalTrackingId.isNotBlank())
		require(serviceRunId.isNotBlank())
		require(startedElapsedRealtimeNanos >= 0L)
		val active = ActiveMotionSession(logicalTrackingId, serviceRunId, startedElapsedRealtimeNanos)
		if (initialMotion) active.markMoving(startedElapsedRealtimeNanos, MotionPolicyReason.ACTIVITY_TRANSITION)
		session = active
		_policy.value = active.policyAt(startedElapsedRealtimeNanos)
	}

	fun stopSession(serviceRunId: String) = synchronized(lock) {
		if (session?.serviceRunId != serviceRunId) return@synchronized
		session = null
		_policy.value = CollectionMotionPolicy.unrestricted()
	}

	fun onDurableEvidence(candidate: SourceEvidenceCandidate<*>) = synchronized(lock) {
		val active = session ?: return@synchronized
		if (candidate.logicalTrackingId?.value != active.logicalTrackingId ||
			candidate.serviceRunId?.value != active.serviceRunId ||
			candidate.observedElapsedRealtimeNanos < active.startedElapsedRealtimeNanos
		) return@synchronized

		val at = candidate.observedElapsedRealtimeNanos
		when (val payload = candidate.payload) {
			is ActivityRecognitionPayload -> active.onActivityRecognition(payload, at)
			is ActivityTransitionPayload -> active.onActivityTransition(payload, at)
			is StepCounterWindowPayload -> if (payload.deltaCount > 0L) {
				active.markPedestrian(at, MotionPolicyReason.STEP_COUNTER)
			}
			is LocationFixPayload -> active.onLocation(payload, at)
			is CellSnapshotPayload -> active.onCell(payload, at)
			is PressureWindowPayload -> active.onPressure(payload, at)
			else -> Unit
		}
		publish(active.policyAt(at))
	}

	/** Advances hysteresis when no sensor callback arrives; absence of GPS never marks a stop. */
	fun tick(nowElapsedRealtimeNanos: Long) = synchronized(lock) {
		require(nowElapsedRealtimeNanos >= 0L)
		session?.let { publish(it.policyAt(nowElapsedRealtimeNanos)) }
	}

	private fun publish(next: CollectionMotionPolicy) {
		if (_policy.value != next) _policy.value = next
	}

	private data class ActiveMotionSession(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val startedElapsedRealtimeNanos: Long,
		var lastVehicleNanos: Long? = null,
		var vehicleExitNanos: Long? = null,
		var lastPedestrianNanos: Long? = null,
		var lastMovingNanos: Long? = null,
		var lastMotionReason: MotionPolicyReason = MotionPolicyReason.AWAITING_EVIDENCE,
		var stationaryCandidateNanos: Long? = null,
		var stationaryConfirmations: Int = 0,
		var lastStationaryConfirmationNanos: Long? = null,
		var previousLocation: TimedLocation? = null,
		var previousCellSignature: Set<String>? = null,
		var previousPressure: TimedPressure? = null,
	) {
		fun onActivityRecognition(payload: ActivityRecognitionPayload, at: Long) {
			if (payload.confidencePercent < MIN_ACTIVITY_CONFIDENCE) return
			when (payload.activityType) {
				ACTIVITY_IN_VEHICLE -> markVehicle(at, MotionPolicyReason.ACTIVITY_RECOGNITION)
				ACTIVITY_ON_BICYCLE -> markMoving(at, MotionPolicyReason.ACTIVITY_RECOGNITION)
				ACTIVITY_ON_FOOT, ACTIVITY_WALKING, ACTIVITY_RUNNING ->
					markPedestrian(at, MotionPolicyReason.ACTIVITY_RECOGNITION)
				ACTIVITY_STILL -> if (payload.confidencePercent >= STRONG_STILL_CONFIDENCE) {
					markStationaryCandidate(at)
				}
			}
		}

		fun onActivityTransition(payload: ActivityTransitionPayload, at: Long) {
			if (payload.transitionType == TRANSITION_ENTER) {
				when (payload.activityType) {
					ACTIVITY_IN_VEHICLE -> markVehicle(at, MotionPolicyReason.ACTIVITY_TRANSITION)
					ACTIVITY_ON_BICYCLE -> markMoving(at, MotionPolicyReason.ACTIVITY_TRANSITION)
					ACTIVITY_ON_FOOT, ACTIVITY_WALKING, ACTIVITY_RUNNING ->
						markPedestrian(at, MotionPolicyReason.ACTIVITY_TRANSITION)
					ACTIVITY_STILL -> markStationaryCandidate(at)
				}
			} else if (payload.transitionType == TRANSITION_EXIT &&
				payload.activityType == ACTIVITY_IN_VEHICLE
			) {
				vehicleExitNanos = at
			}
		}

		fun onLocation(payload: LocationFixPayload, at: Long) {
			val accurate = payload.horizontalAccuracyMeters.isFinite() &&
				payload.horizontalAccuracyMeters in 0f..MAX_MOTION_LOCATION_ACCURACY_METERS
			val speed = payload.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f }
			when {
				accurate && speed != null && speed >= VEHICLE_SPEED_METERS_PER_SECOND ->
					markVehicle(at, MotionPolicyReason.LOCATION_MOTION)
				accurate && speed != null && speed >= MOVING_SPEED_METERS_PER_SECOND ->
					markMoving(at, MotionPolicyReason.LOCATION_MOTION)
				accurate && previousLocation?.isMeaningfulDisplacement(payload, at) == true ->
					markMoving(at, MotionPolicyReason.LOCATION_MOTION)
				accurate && speed != null && speed <= STATIONARY_SPEED_METERS_PER_SECOND ->
					markStationaryCandidate(at)
			}
			if (accurate) previousLocation = TimedLocation(payload, at)
		}

		fun onCell(payload: CellSnapshotPayload, at: Long) {
			if (payload.observations.isEmpty()) return
			val signature = payload.observations.asSequence()
				.filter { it.registered }
				.map { "${it.radioType}:${it.identifierToken}" }
				.toSet()
			if (signature.isEmpty()) return
			val previous = previousCellSignature
			previousCellSignature = signature
			if (previous == null || previous == signature) return

			val recentVehicle = lastVehicleNanos?.let { at - it <= VEHICLE_CONTINUITY_NANOS } == true
			val recentMotion = lastMovingNanos?.let { at - it <= GENERAL_MOTION_HOLD_NANOS } == true
			when {
				recentVehicle -> markVehicle(at, MotionPolicyReason.CELL_CHANGE)
				recentMotion -> markMoving(at, MotionPolicyReason.CELL_CHANGE)
			}
		}

		fun onPressure(payload: PressureWindowPayload, at: Long) {
			val previous = previousPressure
			previousPressure = TimedPressure(payload.meanHectopascals, at)
			if (previous == null || at - previous.atNanos > PRESSURE_EVIDENCE_WINDOW_NANOS) return
			if (abs(payload.meanHectopascals - previous.meanHectopascals) < PRESSURE_CHANGE_HECTOPASCALS) return
			when {
				lastVehicleNanos?.let { at - it <= VEHICLE_CONTINUITY_NANOS } == true ->
					markVehicle(at, MotionPolicyReason.PRESSURE_CHANGE)
				lastMovingNanos?.let { at - it <= GENERAL_MOTION_HOLD_NANOS } == true ->
					markMoving(at, MotionPolicyReason.PRESSURE_CHANGE)
			}
		}

		fun markVehicle(at: Long, reason: MotionPolicyReason) {
			lastVehicleNanos = max(lastVehicleNanos ?: Long.MIN_VALUE, at)
			vehicleExitNanos = null
			markMoving(at, reason)
		}

		fun markPedestrian(at: Long, reason: MotionPolicyReason) {
			lastPedestrianNanos = max(lastPedestrianNanos ?: Long.MIN_VALUE, at)
			markMoving(at, reason)
		}

		fun markMoving(at: Long, reason: MotionPolicyReason) {
			lastMovingNanos = max(lastMovingNanos ?: Long.MIN_VALUE, at)
			lastMotionReason = reason
			stationaryCandidateNanos = null
			stationaryConfirmations = 0
			lastStationaryConfirmationNanos = null
		}

		fun markStationaryCandidate(at: Long) {
			if (lastMovingNanos?.let { at < it } == true) return
			if (stationaryCandidateNanos == null) stationaryCandidateNanos = at
			val previousConfirmation = lastStationaryConfirmationNanos
			if (previousConfirmation == null || at - previousConfirmation >= STATIONARY_CONFIRMATION_SPACING_NANOS) {
				stationaryConfirmations++
				lastStationaryConfirmationNanos = at
			}
		}

		fun policyAt(now: Long): CollectionMotionPolicy {
			val vehicleAt = lastVehicleNanos
			if (vehicleAt != null) {
				val exitedAfterEvidence = vehicleExitNanos?.let { it >= vehicleAt } == true
				val hold = if (exitedAfterEvidence) VEHICLE_EXIT_HOLD_NANOS else VEHICLE_CONTINUITY_NANOS
				if (now - vehicleAt <= hold) {
					return movingPolicy(
						CollectionMotionState.VEHICLE,
						if (lastMotionReason in continuityReasons) lastMotionReason else MotionPolicyReason.VEHICLE_CONTINUITY,
						95,
					)
				}
			}
			lastPedestrianNanos?.let { if (now - it <= PEDESTRIAN_HOLD_NANOS) {
				return movingPolicy(CollectionMotionState.PEDESTRIAN, lastMotionReason, 90)
			} }
			lastMovingNanos?.let { if (now - it <= GENERAL_MOTION_HOLD_NANOS) {
				return movingPolicy(CollectionMotionState.MOVING, lastMotionReason, 80)
			} }

			val stationarySince = stationaryCandidateNanos
			if (stationarySince != null && stationaryConfirmations >= REQUIRED_STATIONARY_CONFIRMATIONS &&
				now - stationarySince >= STATIONARY_DWELL_NANOS &&
				(lastMovingNanos == null || lastMovingNanos!! <= stationarySince)
			) {
				return CollectionMotionPolicy(
					motionState = CollectionMotionState.STATIONARY,
					confidencePercent = 90,
					reason = MotionPolicyReason.STATIONARY_CONFIRMED,
					locationStrategy = LocationCollectionStrategy.PASSIVE_WHILE_STATIONARY,
					expensiveNetworkScansAllowed = false,
					continuousPressureAllowed = false,
					lowLatencyStepReporting = false,
				)
			}
			return CollectionMotionPolicy.awaitingEvidence()
		}

		private fun movingPolicy(
			state: CollectionMotionState,
			reason: MotionPolicyReason,
			confidence: Int,
		) = CollectionMotionPolicy(
			motionState = state,
			confidencePercent = confidence,
			reason = reason,
			locationStrategy = LocationCollectionStrategy.FULL_FIDELITY,
			expensiveNetworkScansAllowed = true,
			continuousPressureAllowed = true,
			lowLatencyStepReporting = true,
		)
	}

	private data class TimedLocation(val payload: LocationFixPayload, val atNanos: Long) {
		fun isMeaningfulDisplacement(next: LocationFixPayload, nextAtNanos: Long): Boolean {
			if (nextAtNanos - atNanos !in 1..LOCATION_DISPLACEMENT_WINDOW_NANOS) return false
			val distance = haversineMeters(
				payload.latitudeDegrees,
				payload.longitudeDegrees,
				next.latitudeDegrees,
				next.longitudeDegrees,
			)
			val uncertainty = payload.horizontalAccuracyMeters + next.horizontalAccuracyMeters
			return distance >= max(MINIMUM_DISPLACEMENT_METERS, uncertainty.toDouble())
		}
	}

	private data class TimedPressure(val meanHectopascals: Double, val atNanos: Long)

	private companion object {
		const val ACTIVITY_STILL = StableActivityTypeCode.STILL
		const val ACTIVITY_WALKING = StableActivityTypeCode.WALKING
		const val ACTIVITY_RUNNING = StableActivityTypeCode.RUNNING
		const val ACTIVITY_ON_BICYCLE = StableActivityTypeCode.ON_BICYCLE
		const val ACTIVITY_IN_VEHICLE = StableActivityTypeCode.IN_VEHICLE
		const val ACTIVITY_ON_FOOT = StableActivityTypeCode.ON_FOOT
		const val TRANSITION_ENTER = 0
		const val TRANSITION_EXIT = 1
		const val MIN_ACTIVITY_CONFIDENCE = 55
		const val STRONG_STILL_CONFIDENCE = 75
		const val REQUIRED_STATIONARY_CONFIRMATIONS = 2
		const val MAX_MOTION_LOCATION_ACCURACY_METERS = 75f
		const val VEHICLE_SPEED_METERS_PER_SECOND = 5f
		const val MOVING_SPEED_METERS_PER_SECOND = 0.8f
		const val STATIONARY_SPEED_METERS_PER_SECOND = 0.2f
		const val MINIMUM_DISPLACEMENT_METERS = 25.0
		const val PRESSURE_CHANGE_HECTOPASCALS = 0.12
		const val NANOS_PER_SECOND = 1_000_000_000L
		const val STATIONARY_CONFIRMATION_SPACING_NANOS = 20 * NANOS_PER_SECOND
		const val STATIONARY_DWELL_NANOS = 90 * NANOS_PER_SECOND
		const val GENERAL_MOTION_HOLD_NANOS = 90 * NANOS_PER_SECOND
		const val PEDESTRIAN_HOLD_NANOS = 120 * NANOS_PER_SECOND
		const val VEHICLE_EXIT_HOLD_NANOS = 90 * NANOS_PER_SECOND
		const val VEHICLE_CONTINUITY_NANOS = 10 * 60 * NANOS_PER_SECOND
		const val PRESSURE_EVIDENCE_WINDOW_NANOS = 60 * NANOS_PER_SECOND
		const val LOCATION_DISPLACEMENT_WINDOW_NANOS = 2 * 60 * NANOS_PER_SECOND
		val continuityReasons = setOf(MotionPolicyReason.CELL_CHANGE, MotionPolicyReason.PRESSURE_CHANGE)
	}
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
	val earthRadiusMeters = 6_371_000.0
	val latitudeDelta = Math.toRadians(lat2 - lat1)
	val longitudeDelta = Math.toRadians(lon2 - lon1)
	val a = sin(latitudeDelta / 2).let { it * it } +
		cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
		sin(longitudeDelta / 2).let { it * it }
	return earthRadiusMeters * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
}
