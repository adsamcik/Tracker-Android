package com.adsamcik.tracker.stats.api

/**
 * States of the trip inference state machine.
 *
 * ```
 * STATIONARY → DEPARTING → IN_TRIP → STOP_PENDING → ARRIVED → STATIONARY
 * ```
 *
 * - [DEPARTING] debounces false starts from GPS drift (120s confirmation)
 * - [STOP_PENDING] debounces brief stops (mode-dependent: walk=5min, drive=10min)
 */
enum class TripState {
	/** User is not moving. No active trip. */
	STATIONARY,

	/** Motion detected but not yet confirmed as a trip. */
	DEPARTING,

	/** Actively in a trip. GPS recording at full rate. */
	IN_TRIP,

	/** Possible stop detected but waiting for confirmation. */
	STOP_PENDING,

	/** Trip has ended. Finalizing boundary and stats. */
	ARRIVED;

	val isInTrip: Boolean
		get() = this == IN_TRIP || this == STOP_PENDING

	val isTransitional: Boolean
		get() = this == DEPARTING || this == STOP_PENDING || this == ARRIVED
}
