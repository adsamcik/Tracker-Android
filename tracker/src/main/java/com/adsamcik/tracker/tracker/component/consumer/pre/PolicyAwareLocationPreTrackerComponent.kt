package com.adsamcik.tracker.tracker.component.consumer.pre

import android.content.Context
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.MutableCollectionTempData
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Policy-aware location pre-tracker component for adaptive tracking.
 *  
 * Allows tracking to proceed without precise location when policy permits (PASSIVE_LOW, MOVEMENT_SUSPECTED).
 * Only requires location when policy demands it (ACTIVE_MODERATE, ACTIVE_ELEVATED, USER_INITIATED).
 *
 * Contract:
 * - Input: MutableCollectionTempData with optional location
 * - Output: Boolean (true = proceed with tracking, false = skip this cycle)
 * - Behavior: Consults TrackingPolicyManager.shouldRequestLocation()
 *
 * Phase 3: Enables location-optional tracking for passive collection modes.
 */
internal class PolicyAwareLocationPreTrackerComponent(
	private val policyFlow: StateFlow<com.adsamcik.tracker.tracker.policy.TrackingPolicy>
) : PreTrackerComponent {

	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	override suspend fun onEnable(context: Context) {
		// No initialization needed
	}

	override suspend fun onDisable(context: Context) {
		// No cleanup needed
	}

	/**
	 * Check if tracking should proceed based on current policy and available data.
	 *
	 * Decision logic:
	 * - If policy requires location (ACTIVE policies, USER_INITIATED) → check location quality
	 * - If policy allows location-optional (PASSIVE, MOVEMENT_SUSPECTED) → always proceed
	 *
	 * @return true if tracking should proceed, false to skip this cycle
	 */
	override suspend fun onNewData(data: MutableCollectionTempData): Boolean {
		val currentPolicy = policyFlow.value

		// Check if current policy requires location
		val requiresLocation = when (currentPolicy) {
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.PASSIVE_LOW -> false
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.MOVEMENT_SUSPECTED -> false
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.ACTIVE_MODERATE -> true
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.ACTIVE_ELEVATED -> true
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.USER_INITIATED -> true
		}

		if (!requiresLocation) {
			// Location-optional mode: proceed even without location
			// Cell/Wi-Fi/activity/steps can be collected without GPS
			return true
		}

		// Location required: validate quality
		val location = data.tryGetLocation() ?: return false

		// Basic quality checks
		if (!location.hasAccuracy()) return false

		// Accept any accuracy for now; more strict checks can be added via preferences
		// (e.g., skip if accuracy > threshold specific to policy level)
		return true
	}
}
