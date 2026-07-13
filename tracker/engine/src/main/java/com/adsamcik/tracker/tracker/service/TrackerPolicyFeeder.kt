package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.mapper.toModel
import com.adsamcik.tracker.shared.model.Location
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Feeds sensor data (activity transitions, location displacement, step counts)
 * into the [TrackingPolicyManager] for adaptive tracking decisions.
 *
 * Extracted from [TrackerService.updateData] to isolate policy-feeding concerns.
 */
internal class TrackerPolicyFeeder {

	private var lastActivityType: Int = -1
	private var lastLocation: Location? = null
	private var accumulatedStepCount: Int = 0

	/**
	 * Feed the latest collection cycle's data into the policy manager.
	 *
	 * @param policyManager   the active tracking policy manager.
	 * @param collectionData  data collected in this cycle.
	 * @param cycle           tracking cycle containing step counts.
	 * @param scope           coroutine scope for launching async policy updates.
	 */
	fun feed(
		policyManager: TrackingPolicyManager,
		collectionData: CollectionData,
		cycle: TrackingCycle,
		scope: CoroutineScope,
	) {
		val currentTimeMs = cycle.timestampMs

		feedActivityTransition(policyManager, collectionData, cycle.activityFresh, currentTimeMs, scope)
		feedLocationChange(policyManager, collectionData, currentTimeMs, scope)
		feedStepUpdate(policyManager, cycle, currentTimeMs, scope)
	}

	/**
	 * Reset accumulated state (e.g., when service is re-initialized).
	 */
	fun reset() {
		lastActivityType = -1
		lastLocation = null
		accumulatedStepCount = 0
	}

	private fun feedActivityTransition(
		policyManager: TrackingPolicyManager,
		collectionData: CollectionData,
		activityFresh: Boolean,
		currentTimeMs: Long,
		scope: CoroutineScope,
	) {
		collectionData.activity?.let { activity ->
			val currentActivityType = activity.activityType
			if (activityFresh) {
				scope.launch {
					policyManager.onActivityTransition(
						activityType = currentActivityType,
						confidence = activity.confidence,
						timeMs = currentTimeMs,
					)
				}
			}
			lastActivityType = currentActivityType
		}
	}

	private fun feedLocationChange(
		policyManager: TrackingPolicyManager,
		collectionData: CollectionData,
		currentTimeMs: Long,
		scope: CoroutineScope,
	) {
		collectionData.location?.let { location ->
			val modelLocation = location.toModel()
			policyManager.escalationEngine?.updateSpeed(modelLocation.speed, currentTimeMs)

			lastLocation?.let { prevLocation ->
				val distance = distanceMeters(prevLocation, modelLocation).toFloat()
				if (distance > 0f) {
					scope.launch {
						policyManager.onLocationChange(
							displacementMeters = distance,
							timeMs = currentTimeMs,
						)
					}
				}
			}
			lastLocation = modelLocation
		}
	}

	private fun distanceMeters(first: Location, second: Location): Double {
		val earthRadiusMeters = 6_371_000.0
		val firstLat = Math.toRadians(first.latitude)
		val secondLat = Math.toRadians(second.latitude)
		val deltaLat = Math.toRadians(second.latitude - first.latitude)
		val deltaLon = Math.toRadians(second.longitude - first.longitude)
		val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
			cos(firstLat) * cos(secondLat) * sin(deltaLon / 2) * sin(deltaLon / 2)
		return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
	}

	private fun feedStepUpdate(
		policyManager: TrackingPolicyManager,
		cycle: TrackingCycle,
		currentTimeMs: Long,
		scope: CoroutineScope,
	) {
		cycle.stepDelta?.let { newSteps ->
			if (newSteps > 0) {
				accumulatedStepCount += newSteps
				scope.launch {
					policyManager.onStepUpdate(
						stepCount = accumulatedStepCount,
						timeMs = currentTimeMs,
					)
				}
			}
		}
	}
}
