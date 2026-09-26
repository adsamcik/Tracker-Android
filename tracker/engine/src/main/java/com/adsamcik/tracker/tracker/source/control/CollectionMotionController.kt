package com.adsamcik.tracker.tracker.source.control

import com.adsamcik.tracker.tracker.source.model.ActivityRecognitionPayload
import com.adsamcik.tracker.tracker.source.model.ActivityTransitionPayload
import com.adsamcik.tracker.tracker.source.model.LocationFixPayload
import com.adsamcik.tracker.tracker.source.model.SourceEvidenceCandidate
import com.adsamcik.tracker.tracker.source.model.StepCounterWindowPayload
import com.adsamcik.tracker.tracker.source.model.StableActivityTypeCode
import com.adsamcik.tracker.tracker.source.model.SourceKind
import javax.inject.Inject
import javax.inject.Singleton
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
 * vehicle classification survives a bounded GPS outage (for example a tunnel). Only fresh primary
 * evidence can renew that hold; radio changes and pressure drift do not prove renewed movement.
 */
@Singleton
class CollectionMotionController @Inject constructor() {
	private val lock = Any()
	private val _policy = MutableStateFlow(CollectionMotionPolicy.unrestricted())
	val policy: StateFlow<CollectionMotionPolicy> = _policy.asStateFlow()

	private var session: ActiveMotionSession? = null

	/** One authorized collection owner can span background work and joins/leaves of live focus. */
	fun configureScope(scopeId: String, clockDomainId: String, effectiveSinceNanos: Long) = synchronized(lock) {
		require(scopeId.isNotBlank() && clockDomainId.isNotBlank() && effectiveSinceNanos >= 0L)
		val previous = session
		if (previous?.serviceRunId == scopeId && previous.clockDomainId == clockDomainId &&
			previous.startedElapsedRealtimeNanos == effectiveSinceNanos && !previous.sessionBound
		) return@synchronized
		session = ActiveMotionSession(scopeId, scopeId, effectiveSinceNanos, sessionBound = false,
			clockDomainId = clockDomainId)
		_policy.value = CollectionMotionPolicy.awaitingEvidence()
	}

	fun stopScope(scopeId: String) = stopSession(scopeId)

	/** Caller has already matched the transient helper's exact current authority and registration. */
	fun onMotionAssistance(
		scopeId: String,
		clockDomainId: String,
		observedElapsedRealtimeNanos: Long,
		receivedElapsedRealtimeNanos: Long,
		activityType: Int,
		transitionType: Int,
	) = synchronized(lock) {
		val active = session ?: return@synchronized
		if (active.serviceRunId != scopeId || !active.acceptTime(clockDomainId,
			observedElapsedRealtimeNanos, receivedElapsedRealtimeNanos, MOTION_HINT_MAX_AGE_NANOS)
		) return@synchronized
		active.onActivityTransition(ActivityTransitionPayload(activityType, transitionType,
			observedElapsedRealtimeNanos), observedElapsedRealtimeNanos)
		publish(active.policyAt(active.latestNowNanos))
	}

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
		if (active.sessionBound && (candidate.logicalTrackingId?.value != active.logicalTrackingId ||
			candidate.serviceRunId?.value != active.serviceRunId)
		) return@synchronized
		val payload = candidate.payload
		val stillHint = payload is ActivityRecognitionPayload && payload.activityType == ACTIVITY_STILL ||
			payload is ActivityTransitionPayload && payload.activityType == ACTIVITY_STILL ||
			payload is LocationFixPayload && payload.speedMetersPerSecond?.let { it <= STATIONARY_SPEED_METERS_PER_SECOND } == true
		if (!active.acceptTime(candidate.clockDomainId, candidate.observedElapsedRealtimeNanos,
			candidate.receivedElapsedRealtimeNanos, if (stillHint) STILL_HINT_MAX_AGE_NANOS else MOTION_HINT_MAX_AGE_NANOS)
		) return@synchronized
		val previousAt = active.latestSourceObservation[candidate.source]
		if (previousAt != null && candidate.observedElapsedRealtimeNanos <= previousAt) return@synchronized
		active.latestSourceObservation[candidate.source] = candidate.observedElapsedRealtimeNanos

		val at = candidate.observedElapsedRealtimeNanos
		when (payload) {
			is ActivityRecognitionPayload -> active.onActivityRecognition(payload, at)
			is ActivityTransitionPayload -> active.onActivityTransition(payload, at)
			is StepCounterWindowPayload -> if (payload.deltaCount > 0L) {
				active.markPedestrian(at, MotionPolicyReason.STEP_COUNTER)
			}
			is LocationFixPayload -> active.onLocation(payload, at, candidate.locationSpeedAccuracyMetersPerSecond)
			// Radio and Pressure can describe source value/health, but cannot renew primary motion.
			else -> Unit
		}
		publish(active.policyAt(active.latestNowNanos))
	}

	/** Advances hysteresis when no sensor callback arrives; absence of GPS never marks a stop. */
	fun tick(nowElapsedRealtimeNanos: Long) = synchronized(lock) {
		require(nowElapsedRealtimeNanos >= 0L)
		session?.let {
			it.latestNowNanos = maxOf(it.latestNowNanos, nowElapsedRealtimeNanos)
			publish(it.policyAt(it.latestNowNanos))
		}
	}

	private fun publish(next: CollectionMotionPolicy) {
		if (_policy.value != next) _policy.value = next
	}

	private data class ActiveMotionSession(
		val logicalTrackingId: String,
		val serviceRunId: String,
		val startedElapsedRealtimeNanos: Long,
		val sessionBound: Boolean = true,
		var clockDomainId: String? = null,
		var latestNowNanos: Long = startedElapsedRealtimeNanos,
		val latestSourceObservation: MutableMap<SourceKind, Long> = mutableMapOf(),
		var lastVehicleNanos: Long? = null,
		var vehicleExitNanos: Long? = null,
		var lastPedestrianNanos: Long? = null,
		var lastMovingNanos: Long? = null,
		var lastMotionReason: MotionPolicyReason = MotionPolicyReason.AWAITING_EVIDENCE,
		var stationaryCandidateNanos: Long? = null,
		var stationaryConfirmations: Int = 0,
		var lastStationaryConfirmationNanos: Long? = null,
		var previousLocation: TimedLocation? = null,
	) {
		fun acceptTime(clock: String, observed: Long, received: Long, maximumAge: Long): Boolean {
			if (clockDomainId != null && clockDomainId != clock) return false
			if (observed < startedElapsedRealtimeNanos || received < observed) return false
			val now = maxOf(latestNowNanos, received)
			if (now - observed > maximumAge) return false
			clockDomainId = clock
			latestNowNanos = now
			return true
		}
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

		fun onLocation(payload: LocationFixPayload, at: Long, speedUncertainty: Float?) {
			val accurate = payload.horizontalAccuracyMeters.isFinite() &&
				payload.horizontalAccuracyMeters in 0f..MAX_MOTION_LOCATION_ACCURACY_METERS
			val speed = payload.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f }
			val uncertainty = speedUncertainty?.takeIf { it.isFinite() && it >= 0f }
			val minimumSpeed = if (speed != null && uncertainty != null) maxOf(0f, speed - uncertainty) else null
			val maximumSpeed = if (speed != null && uncertainty != null) speed + uncertainty else null
			when {
				accurate && minimumSpeed != null && minimumSpeed >= VEHICLE_SPEED_METERS_PER_SECOND ->
					markVehicle(at, MotionPolicyReason.LOCATION_MOTION)
				accurate && minimumSpeed != null && minimumSpeed >= MOVING_SPEED_METERS_PER_SECOND ->
					markMoving(at, MotionPolicyReason.LOCATION_MOTION)
				accurate && previousLocation?.isMeaningfulDisplacement(payload, at) == true ->
					markMoving(at, MotionPolicyReason.LOCATION_MOTION)
				accurate && maximumSpeed != null && maximumSpeed <= STATIONARY_SPEED_METERS_PER_SECOND ->
					markStationaryCandidate(at)
				accurate && maximumSpeed != null && maximumSpeed > STATIONARY_SPEED_METERS_PER_SECOND -> {
					stationaryCandidateNanos = null
					stationaryConfirmations = 0
					lastStationaryConfirmationNanos = null
				}
			}
			if (accurate && (previousLocation == null || at > previousLocation!!.atNanos)) previousLocation = TimedLocation(payload, at)
		}

		fun markVehicle(at: Long, reason: MotionPolicyReason) {
			if (lastMovingNanos?.let { at < it } == true) return
			lastVehicleNanos = max(lastVehicleNanos ?: Long.MIN_VALUE, at)
			vehicleExitNanos = null
			markMoving(at, reason)
		}

		fun markPedestrian(at: Long, reason: MotionPolicyReason) {
			if (lastMovingNanos?.let { at < it } == true) return
			lastPedestrianNanos = max(lastPedestrianNanos ?: Long.MIN_VALUE, at)
			markMoving(at, reason)
		}

		fun markMoving(at: Long, reason: MotionPolicyReason) {
			if (lastMovingNanos?.let { at < it } == true) return
			lastMovingNanos = max(lastMovingNanos ?: Long.MIN_VALUE, at)
			lastMotionReason = reason
			stationaryCandidateNanos = null
			stationaryConfirmations = 0
			lastStationaryConfirmationNanos = null
		}

		fun markStationaryCandidate(at: Long) {
			if (lastMovingNanos?.let { at < it } == true) return
			if (lastStationaryConfirmationNanos?.let { at - it > STILL_HINT_MAX_AGE_NANOS } == true) {
				stationaryCandidateNanos = null
				stationaryConfirmations = 0
				lastStationaryConfirmationNanos = null
			}
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
						MotionPolicyReason.VEHICLE_CONTINUITY,
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
				lastStationaryConfirmationNanos?.let { now - it in 0..STILL_HINT_MAX_AGE_NANOS } == true &&
				now - stationarySince >= STATIONARY_DWELL_NANOS &&
				(lastMovingNanos == null || lastMovingNanos!! <= stationarySince)
			) {
				return CollectionMotionPolicy(
					motionState = CollectionMotionState.STATIONARY,
					confidencePercent = 90,
					reason = MotionPolicyReason.STATIONARY_CONFIRMED,
					locationStrategy = LocationCollectionStrategy.LOW_POWER_SENTINEL,
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
		const val NANOS_PER_SECOND = 1_000_000_000L
		const val STATIONARY_CONFIRMATION_SPACING_NANOS = 20 * NANOS_PER_SECOND
		const val STATIONARY_DWELL_NANOS = 90 * NANOS_PER_SECOND
		const val MOTION_HINT_MAX_AGE_NANOS = 30 * NANOS_PER_SECOND
		const val STILL_HINT_MAX_AGE_NANOS = 120 * NANOS_PER_SECOND
		const val GENERAL_MOTION_HOLD_NANOS = 90 * NANOS_PER_SECOND
		const val PEDESTRIAN_HOLD_NANOS = 120 * NANOS_PER_SECOND
		const val VEHICLE_EXIT_HOLD_NANOS = 90 * NANOS_PER_SECOND
		const val VEHICLE_CONTINUITY_NANOS = 10 * 60 * NANOS_PER_SECOND
		const val LOCATION_DISPLACEMENT_WINDOW_NANOS = 2 * 60 * NANOS_PER_SECOND
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
