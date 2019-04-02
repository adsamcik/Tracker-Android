package com.adsamcik.signalcollector.activity

/**
 * Singleton that contains [GroupedActivity]. It is made this way so even the constants are contained within an object.
 */
enum class GroupedActivity {
	STILL,
	ON_FOOT,
	IN_VEHICLE,
	UNKNOWN
}
