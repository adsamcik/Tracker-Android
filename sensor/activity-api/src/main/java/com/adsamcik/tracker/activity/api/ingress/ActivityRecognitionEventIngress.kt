package com.adsamcik.tracker.activity.api.ingress

import com.adsamcik.tracker.activity.ActivityTransitionData
import com.adsamcik.tracker.activity.ActivityTransitionType
import com.adsamcik.tracker.activity.api.registration.ActivityRegistrationIdentity
import com.adsamcik.tracker.stats.api.DetectedActivityType

/**
 * Process-independent handoff for activity evidence delivered by a platform callback.
 *
 * Implementations must durably admit every event before returning [DURABLE]. Callers may
 * publish transient in-process effects only after that result.
 */
interface ActivityRecognitionEventIngress {
	suspend fun admit(batch: ActivityRecognitionEvidenceBatch): ActivityIngressResult
}

data class ActivityRecognitionEvidenceBatch(
	val receivedElapsedRealtimeNanos: Long,
	val receivedWallTimeMs: Long,
	val registrationIdentity: ActivityRegistrationIdentity? = null,
	/**
	 * Android start context carried by this delivery attempt.
	 *
	 * A durable retry preserves the observation but must never inherit the short-lived foreground
	 * service exemption of the original platform callback.
	 */
	val startContext: ActivityIngressStartContext = ActivityIngressStartContext.LIVE_PROVIDER_CALLBACK,
	/** Purpose metadata captured with the immutable provider registration callback. */
	val automaticRecognitionEligible: Boolean = false,
	val automaticTransitions: Set<ActivityTransitionData> = emptySet(),
	val recognitions: List<ActivityRecognitionEvidence> = emptyList(),
	val transitions: List<ActivityTransitionEvidence> = emptyList(),
) {
	init {
		require(receivedElapsedRealtimeNanos >= 0L)
		require(receivedWallTimeMs >= 0L)
	}

	val eventCount: Int
		get() = recognitions.size + transitions.size
}

enum class ActivityIngressStartContext {
	LIVE_PROVIDER_CALLBACK,
	DURABLE_REPLAY,
}

data class ActivityRecognitionEvidence(
	val activityType: DetectedActivityType,
	val confidencePercent: Int,
	val providerElapsedRealtimeNanos: Long,
) {
	init {
		require(confidencePercent in 0..100)
		require(providerElapsedRealtimeNanos >= 0L)
	}
}

data class ActivityTransitionEvidence(
	val activityType: DetectedActivityType,
	val transitionType: ActivityTransitionType,
	val providerElapsedRealtimeNanos: Long,
) {
	init {
		require(providerElapsedRealtimeNanos >= 0L)
	}
}

data class ActivityIngressResult(
	val status: ActivityIngressStatus,
	val admittedCount: Int,
	val duplicateCount: Int,
	val durableSelection: ActivityDurableSelection = ActivityDurableSelection.EMPTY,
	val failureCode: String? = null,
	/**
	 * Callback members permanently omitted by the provider-time or observed-time eligibility
	 * envelope before WAL admission.
	 *
	 * These members require no retry. Keeping them separate from [admittedCount] and
	 * [duplicateCount] preserves truthful durability accounting when a mixed callback has already
	 * committed every qualifying sibling but downstream recovery still needs another attempt.
	 */
	val discardedCount: Int = 0,
) {
	init {
		require(admittedCount >= 0)
		require(duplicateCount >= 0)
		require(discardedCount >= 0)
		require(status == ActivityIngressStatus.DURABLE || failureCode != null)
		require(status != ActivityIngressStatus.DURABLE || failureCode == null)
		require(status == ActivityIngressStatus.DURABLE || durableSelection.isEmpty) {
			"A failed Activity ingress cannot select transient events for publication"
		}
		require(
			status != ActivityIngressStatus.DURABLE ||
				durableSelection.selectedCount == admittedCount,
		) {
			"A durable Activity ingress must select every newly admitted callback member"
		}
	}

	val isDurable: Boolean
		get() = status == ActivityIngressStatus.DURABLE

	val settledCount: Int
		get() = admittedCount + duplicateCount + discardedCount

	companion object {
		fun durable(
			admittedCount: Int,
			duplicateCount: Int,
			durableSelection: ActivityDurableSelection = ActivityDurableSelection.EMPTY,
			discardedCount: Int = 0,
		): ActivityIngressResult = ActivityIngressResult(
			status = ActivityIngressStatus.DURABLE,
			admittedCount = admittedCount,
			duplicateCount = duplicateCount,
			durableSelection = durableSelection,
			discardedCount = discardedCount,
		)

		fun retryable(
			admittedCount: Int,
			duplicateCount: Int,
			failureCode: String,
			discardedCount: Int = 0,
		): ActivityIngressResult =
			ActivityIngressResult(
				status = ActivityIngressStatus.RETRYABLE,
				admittedCount = admittedCount,
				duplicateCount = duplicateCount,
				failureCode = failureCode,
				discardedCount = discardedCount,
			)

		fun rejected(
			admittedCount: Int,
			duplicateCount: Int,
			failureCode: String,
			discardedCount: Int = 0,
		): ActivityIngressResult =
			ActivityIngressResult(
				status = ActivityIngressStatus.REJECTED,
				admittedCount = admittedCount,
				duplicateCount = duplicateCount,
				failureCode = failureCode,
				discardedCount = discardedCount,
			)
	}
}

/** Original callback members whose exact WAL rows were verified after atomic admission. */
data class ActivityDurableSelection(
	val recognitionIndexes: Set<Int> = emptySet(),
	val transitionIndexes: Set<Int> = emptySet(),
) {
	init {
		require(recognitionIndexes.all { it >= 0 })
		require(transitionIndexes.all { it >= 0 })
	}

	val isEmpty: Boolean
		get() = recognitionIndexes.isEmpty() && transitionIndexes.isEmpty()

	val selectedCount: Int
		get() = recognitionIndexes.size + transitionIndexes.size

	companion object {
		val EMPTY = ActivityDurableSelection()
	}
}

enum class ActivityIngressStatus {
	DURABLE,
	RETRYABLE,
	REJECTED,
}
