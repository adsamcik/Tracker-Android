package com.adsamcik.tracker.tracker.component.consumer.pre

import android.content.Context
import com.adsamcik.tracker.shared.base.concurrency.DefaultDispatchersProvider
import com.adsamcik.tracker.shared.base.concurrency.DispatchersProvider
import com.adsamcik.tracker.shared.preferences.Preferences
import com.adsamcik.tracker.shared.preferences.PreferenceKeys
import com.adsamcik.tracker.shared.preferences.flow.PreferenceFlows
import com.adsamcik.tracker.shared.preferences.tracking.TrackingParamsRepository
import com.adsamcik.tracker.tracker.component.PreTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import com.adsamcik.tracker.tracker.policy.TrackingPolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow

/**
 * Policy-aware location pre-tracker component for adaptive tracking.
 *  
 * Allows tracking to proceed without precise location when policy permits (PASSIVE_LOW, MOVEMENT_SUSPECTED).
 * Only requires location when policy demands it (ACTIVE_MODERATE, ACTIVE_ELEVATED, USER_INITIATED).
 *
 * For USER_INITIATED mode, reads the user-configured accuracy threshold from preferences
 * and observes changes live (matching LocationPreTrackerComponent behavior).
 */
internal class PolicyAwareLocationPreTrackerComponent(
	private val policyFlow: StateFlow<com.adsamcik.tracker.tracker.policy.TrackingPolicy>,
	private val dispatchers: DispatchersProvider = DefaultDispatchersProvider,
	private val trackingParamsRepository: TrackingParamsRepository? = null,
) : PreTrackerComponent {

	override val requiredData: Collection<TrackerComponentRequirement> = emptyList()

	@Volatile
	private var userAccuracyThreshold: Int = DEFAULT_ACCURACY
	private var scope: CoroutineScope? = null
	private var accuracyJob: Job? = null

	override suspend fun onEnable(context: Context) {
		scope = CoroutineScope(SupervisorJob() + dispatchers.main)
		val repository = trackingParamsRepository
		if (repository != null) {
			userAccuracyThreshold = repository.data.first().requiredAccuracyMeters
			accuracyJob = repository.data
				.onEach { userAccuracyThreshold = it.requiredAccuracyMeters }
				.launchIn(requireNotNull(scope))
		} else {
			userAccuracyThreshold = Preferences(context).fetchInt(
				PreferenceKeys.TRACKING_REQUIRED_ACCURACY,
				PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT
			)
			accuracyJob = PreferenceFlows.int(
				context,
				PreferenceKeys.TRACKING_REQUIRED_ACCURACY,
				PreferenceKeys.TRACKING_REQUIRED_ACCURACY_DEFAULT
			).onEach { userAccuracyThreshold = it }
				.launchIn(requireNotNull(scope))
		}
	}

	override suspend fun onDisable(context: Context) {
		accuracyJob?.cancel()
		accuracyJob = null
		scope?.cancel()
		scope = null
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
	override suspend fun onNewData(cycle: TrackingCycle): Boolean {
		val currentPolicy = policyFlow.value

		val requiresLocation = when (currentPolicy) {
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.PASSIVE_LOW -> false
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.MOVEMENT_SUSPECTED -> false
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.ACTIVE_MODERATE -> true
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.ACTIVE_ELEVATED -> true
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.USER_INITIATED -> true
		}

		if (!requiresLocation) {
			return true
		}

		// Location required: validate quality (H7 fix — restore checks from LocationPreTrackerComponent)
		val location = cycle.location?.lastLocation ?: return false

		// Mock location check
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
			if (location.isMock) return false
		} else {
			@Suppress("deprecation")
			if (location.isFromMockProvider) return false
		}

		if (!location.hasAccuracy()) return false

		// Accept accuracy based on policy level (use user preference for USER_INITIATED)
		val maxAccuracy = when (currentPolicy) {
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.ACTIVE_MODERATE -> 100
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.ACTIVE_ELEVATED -> 50
			com.adsamcik.tracker.tracker.policy.TrackingPolicy.USER_INITIATED -> userAccuracyThreshold
			else -> 200
		}
		return location.accuracy <= maxAccuracy
	}

	companion object {
		private const val DEFAULT_ACCURACY = 100
	}
}
