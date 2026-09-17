package com.adsamcik.tracker.shared.model.tracking

enum class TrackingSource(val stableCode: Int) {
	LOCATION(1),
	ACTIVITY(2),
	STEPS(3),
	PRESSURE(4),
	WIFI(5),
	CELL(6);

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

	fun forPurpose(purpose: TrackingPurpose): TrackingSourcePurposeIdentity =
		TrackingSourcePurposeIdentity(this, purpose)

	companion object {
		fun fromStableCode(value: Int): TrackingSource = entries.singleOrNull {
			it.stableCode == value
		} ?: throw IllegalArgumentException("Unknown tracking source code $value")
	}
}

enum class TrackingPurpose(val stableName: String) {
	SESSION_CAPTURE("SESSION_CAPTURE"),
	CONTROL("CONTROL"),
	AMBIENT_PRODUCT("AMBIENT_PRODUCT");

	companion object {
		fun fromStableName(value: String): TrackingPurpose = entries.singleOrNull {
			it.stableName == value
		} ?: throw IllegalArgumentException("Unknown tracking purpose $value")
	}
}

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
