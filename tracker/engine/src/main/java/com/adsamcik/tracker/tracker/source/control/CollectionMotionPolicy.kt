package com.adsamcik.tracker.tracker.source.control

/** Fused motion state used to reduce collection cost without changing the user's source choices. */
enum class CollectionMotionState {
	UNKNOWN,
	STATIONARY,
	PEDESTRIAN,
	VEHICLE,
	MOVING,
}

enum class MotionPolicyReason {
	NO_ACTIVE_SESSION,
	AWAITING_EVIDENCE,
	ACTIVITY_RECOGNITION,
	ACTIVITY_TRANSITION,
	STEP_COUNTER,
	LOCATION_MOTION,
	CELL_CHANGE,
	PRESSURE_CHANGE,
	STATIONARY_CONFIRMED,
	VEHICLE_CONTINUITY,
}

enum class LocationCollectionStrategy {
	/** Preserve the frequency and quality selected by the user and tightened by active consumers. */
	FULL_FIDELITY,

	/** Keep an inexpensive sentinel request until motion can be classified. */
	LOW_POWER_SENTINEL,

	/** Consume provider-sharing fixes only; activity transitions remain the motion wake source. */
	PASSIVE_WHILE_STATIONARY,
}

data class CollectionMotionPolicy(
	val motionState: CollectionMotionState,
	val confidencePercent: Int,
	val reason: MotionPolicyReason,
	val locationStrategy: LocationCollectionStrategy,
	val expensiveNetworkScansAllowed: Boolean,
	val continuousPressureAllowed: Boolean,
	val lowLatencyStepReporting: Boolean,
) {
	init {
		require(confidencePercent in 0..100)
	}

	companion object {
		/** Default for non-service callers and compatibility tests. */
		fun unrestricted() = CollectionMotionPolicy(
			motionState = CollectionMotionState.MOVING,
			confidencePercent = 100,
			reason = MotionPolicyReason.NO_ACTIVE_SESSION,
			locationStrategy = LocationCollectionStrategy.FULL_FIDELITY,
			expensiveNetworkScansAllowed = true,
			continuousPressureAllowed = true,
			lowLatencyStepReporting = true,
		)

		fun awaitingEvidence() = CollectionMotionPolicy(
			motionState = CollectionMotionState.UNKNOWN,
			confidencePercent = 0,
			reason = MotionPolicyReason.AWAITING_EVIDENCE,
			locationStrategy = LocationCollectionStrategy.LOW_POWER_SENTINEL,
			expensiveNetworkScansAllowed = false,
			continuousPressureAllowed = false,
			lowLatencyStepReporting = false,
		)
	}
}

/** Fields that can actually alter an acquisition plan; provenance-only changes must not restart providers. */
data class CollectionAcquisitionProfile(
	val stationary: Boolean,
	val locationStrategy: LocationCollectionStrategy,
	val expensiveNetworkScansAllowed: Boolean,
	val continuousPressureAllowed: Boolean,
	val lowLatencyStepReporting: Boolean,
)

val CollectionMotionPolicy.acquisitionProfile: CollectionAcquisitionProfile
	get() = CollectionAcquisitionProfile(
		stationary = motionState == CollectionMotionState.STATIONARY,
		locationStrategy = locationStrategy,
		expensiveNetworkScansAllowed = expensiveNetworkScansAllowed,
		continuousPressureAllowed = continuousPressureAllowed,
		lowLatencyStepReporting = lowLatencyStepReporting,
	)
