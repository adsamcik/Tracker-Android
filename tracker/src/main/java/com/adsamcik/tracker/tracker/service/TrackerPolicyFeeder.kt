package com.adsamcik.tracker.tracker.service

import com.adsamcik.tracker.shared.base.data.CollectionData
import com.adsamcik.tracker.shared.base.data.Location
import com.adsamcik.tracker.shared.base.data.LengthUnit
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

		feedActivityTransition(policyManager, collectionData, currentTimeMs, scope)
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
		currentTimeMs: Long,
		scope: CoroutineScope,
	) {
		collectionData.activity?.let { activity ->
			val currentActivityType = activity.activityType
			if (lastActivityType >= 0 && lastActivityType != currentActivityType) {
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
			policyManager.escalationEngine?.updateSpeed(location.speed)

			lastLocation?.let { prevLocation ->
				val distance = prevLocation.distance(location, LengthUnit.Meter).toFloat()
				if (distance > 0f) {
					scope.launch {
						policyManager.onLocationChange(
							displacementMeters = distance,
							timeMs = currentTimeMs,
						)
					}
				}
			}
			lastLocation = location
		}
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
