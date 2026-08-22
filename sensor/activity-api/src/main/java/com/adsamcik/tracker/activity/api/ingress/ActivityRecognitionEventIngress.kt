package com.adsamcik.tracker.activity.api.ingress

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
) {
	init {
		require(admittedCount >= 0)
		require(duplicateCount >= 0)
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

	companion object {
		fun durable(
			admittedCount: Int,
			duplicateCount: Int,
			durableSelection: ActivityDurableSelection = ActivityDurableSelection.EMPTY,
		): ActivityIngressResult = ActivityIngressResult(
			ActivityIngressStatus.DURABLE,
			admittedCount,
			duplicateCount,
			durableSelection,
		)

		fun retryable(admittedCount: Int, duplicateCount: Int, failureCode: String): ActivityIngressResult =
			ActivityIngressResult(
				ActivityIngressStatus.RETRYABLE,
				admittedCount,
				duplicateCount,
				failureCode = failureCode,
			)

		fun rejected(admittedCount: Int, duplicateCount: Int, failureCode: String): ActivityIngressResult =
			ActivityIngressResult(
				ActivityIngressStatus.REJECTED,
				admittedCount,
				duplicateCount,
				failureCode = failureCode,
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
