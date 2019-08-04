package com.adsamcik.signalcollector.tracker.component

import com.adsamcik.signalcollector.tracker.data.collection.CollectionTempData

/**
 * Requirements that show which data is essential for a given component.
 * Optional data can still be accessed.
 */
internal enum class TrackerComponentRequirement {
	WIFI,
	CELL,
	LOCATION,
	STEP,
	ACTIVITY;

	fun isRequirementFulfilled(component: CollectionTempData): Boolean {
		return component.containsKey(name)
	}
}