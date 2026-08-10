package com.adsamcik.tracker.activity.api.backend

import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationArbiter
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationDemand
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationOwner
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationStatus
import javax.inject.Inject
import javax.inject.Singleton

/** Compatibility backend: legacy request aggregation is one client of the physical arbiter. */
@Singleton
class ArbitratedActivityRecognitionBackend @Inject constructor(
	private val arbiter: ActivityRegistrationArbiter,
	private val gmsBackend: GmsActivityRecognitionBackend,
) : ActivityRecognitionBackend {
	override val name: String get() = gmsBackend.name
	override val isAvailable: Boolean get() = gmsBackend.isAvailable
	override val activityUpdates get() = gmsBackend.activityUpdates
	override val transitionUpdates get() = gmsBackend.transitionUpdates
	override val lastActivity get() = gmsBackend.lastActivity
	override val lastActivityElapsedTimeMillis get() = gmsBackend.lastActivityElapsedTimeMillis

	override suspend fun startUpdates(config: RecognitionConfig): Boolean {
		val result = arbiter.setDemand(
			ActivityRegistrationOwner.LEGACY_REQUEST_MANAGER,
			ActivityRegistrationDemand(
				continuousRecognitionIntervalSeconds = config.intervalSeconds.takeIf { it > 0 },
				transitions = config.requestedTransitions.toSet(),
			),
		)
		return result.status == ActivityRegistrationStatus.APPLIED ||
			result.status == ActivityRegistrationStatus.DEGRADED
	}

	override suspend fun stopUpdates() {
		val result = arbiter.clearDemand(ActivityRegistrationOwner.LEGACY_REQUEST_MANAGER)
		if (result.status == ActivityRegistrationStatus.FAILED && !result.retryable) {
			error("Unable to remove legacy activity-recognition demand: ${result.failureCode}")
		}
	}
}

