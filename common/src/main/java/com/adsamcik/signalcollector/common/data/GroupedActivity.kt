package com.adsamcik.signalcollector.common.data

/**
 * Singleton that contains [GroupedActivity]. It is made this way so even the constants are contained within an object.
 */
enum class GroupedActivity {
	STILL,
	ON_FOOT,
	IN_VEHICLE,
	UNKNOWN;

	val isIdle: Boolean get() = this == UNKNOWN || this == STILL
}

