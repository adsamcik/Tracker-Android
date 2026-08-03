package com.adsamcik.tracker.tracker.component

import com.adsamcik.tracker.tracker.data.collection.TrackingCycle

/**
 * Requirements that show which data is essential for a given component.
 * Optional data can still be accessed.
 */
internal enum class TrackerComponentRequirement {
	WIFI,
	CELL,
	LOCATION,
	PRESSURE,
	STEP,
	ACTIVITY;

	fun isRequirementFulfilled(cycle: TrackingCycle): Boolean = when (this) {
		WIFI -> cycle.wifiScan != null
		CELL -> cycle.cellScan != null
		LOCATION -> cycle.location != null
		PRESSURE -> cycle.pressure != null
		STEP -> cycle.stepDelta != null
		ACTIVITY -> cycle.activity != null
	}
}
