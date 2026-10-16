package com.adsamcik.tracker.shared.model.tracking

/** Stable codes for the six sources shared by saved data and tracking contracts; capability depends on the purpose. */
enum class TrackingSource(val stableCode: Int) {
	LOCATION(1),
	ACTIVITY(2),
	STEPS(3),
	PRESSURE(4),
	WIFI(5),
	CELL(6);

	/**
	 * Reports whether this source supports the requested purpose, independently of current permission or
	 * provider availability.
	 */
	fun supports(purpose: TrackingPurpose): Boolean = when (purpose) {
		TrackingPurpose.SESSION_CAPTURE -> true
		TrackingPurpose.CONTROL -> this == ACTIVITY
		TrackingPurpose.AMBIENT_PRODUCT -> when (this) {
			LOCATION,
			STEPS,
			WIFI,
			CELL,
			-> true
			ACTIVITY,
			PRESSURE,
			-> false
		}
	}

	/** Creates a validated source/purpose pair and rejects unsupported combinations. */
	fun forPurpose(purpose: TrackingPurpose): TrackingSourcePurposeIdentity =
		TrackingSourcePurposeIdentity(this, purpose)

	/** Decodes the stable numeric source codes used by saved tracking data. */
	companion object {
		/** Decodes a released source code; unknown values are rejected rather than mapped to another source. */
		fun fromStableCode(value: Int): TrackingSource = entries.singleOrNull {
			it.stableCode == value
		} ?: throw IllegalArgumentException("Unknown tracking source code $value")
	}
}

/** Stable persistence/control purposes; choosing a source for one purpose does not authorize another. */
enum class TrackingPurpose(val stableName: String) {
	SESSION_CAPTURE("SESSION_CAPTURE"),
	CONTROL("CONTROL"),
	AMBIENT_PRODUCT("AMBIENT_PRODUCT");

	/** Decodes the stable purpose names used by saved authority and observations. */
	companion object {
		/** Decodes a persisted purpose name; unknown values are rejected rather than granting another purpose. */
		fun fromStableName(value: String): TrackingPurpose = entries.singleOrNull {
			it.stableName == value
		} ?: throw IllegalArgumentException("Unknown tracking purpose $value")
	}
}

/** Validated source/purpose pair; construction rejects combinations unsupported by that source. */
data class TrackingSourcePurposeIdentity(
	val source: TrackingSource,
	val purpose: TrackingPurpose,
) {
	init {
		require(source.supports(purpose)) {
			"$source does not support ${purpose.stableName}"
		}
	}
}
