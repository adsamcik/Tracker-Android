package com.adsamcik.tracker.tracker.source.runtime

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationResult
import javax.inject.Inject
import javax.inject.Singleton

/** Application-scoped automatic-start demand; it is independent of tracking-session lifetime. */
@Singleton
class AutomaticStartTransitionMonitor @Inject constructor(
	private val arbiter: ActivityRegistrationArbiter,
) {
	suspend fun reconcile(
		enabled: Boolean,
		useTransitionApi: Boolean,
		continuousIntervalSeconds: Int,
		transitions: Set<ActivityTransitionData>,
	): ActivityRegistrationResult = if (!enabled) {
		arbiter.clearDemand(ActivityRegistrationOwner.AUTOMATIC_START_MONITOR)
	} else {
		arbiter.setDemand(
			ActivityRegistrationOwner.AUTOMATIC_START_MONITOR,
			ActivityRegistrationDemand(
				continuousRecognitionIntervalSeconds = continuousIntervalSeconds.takeUnless { useTransitionApi },
				transitions = transitions.takeIf { useTransitionApi }.orEmpty(),
			),
		)
	}
}

