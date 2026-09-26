package com.adsamcik.tracker.activity.api.registration

import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.stats.api.DetectedActivityType
import kotlinx.coroutines.flow.Flow

/** A process-local Location host lease; it grants neither Activity persistence nor automatic start. */
data class ActivityMotionAssistanceAuthority(
	val policyRevision: Long,
	val locationPurpose: LocationMotionPurpose,
	val locationConsentEpoch: Long,
	val collectedDataEpoch: Long,
	val clockDomainId: String,
	val effectiveElapsedRealtimeNanos: Long,
	val hostLeaseId: String,
) {
	init {
		require(policyRevision > 0L && locationConsentEpoch > 0L && collectedDataEpoch >= 0L)
		require(clockDomainId.isNotBlank() && hostLeaseId.isNotBlank())
		require(effectiveElapsedRealtimeNanos >= 0L)
	}
}

enum class LocationMotionPurpose { CAPTURE, BACKGROUND }

data class ActivityMotionHint(
	val authority: ActivityMotionAssistanceAuthority,
	val registrationIdentity: ActivityRegistrationIdentity,
	val activityType: DetectedActivityType,
	val transitionType: ActivityTransitionType,
	val observedElapsedRealtimeNanos: Long,
	val receivedElapsedRealtimeNanos: Long,
)

/** No replay, history, durable retry, or legacy Activity flow is associated with these hints. */
interface ActivityMotionAssistance {
	val hints: Flow<ActivityMotionHint>
	/** Non-null only for a currently acknowledged physical registration and process-local host lease. */
	val currentAuthority: ActivityMotionAssistanceAuthority?
}
