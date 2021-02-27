package com.adsamcik.tracker.tracker.component

import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

/**
 * Interface for tracker data consumers.
 */
internal interface TrackerDataConsumerComponent {
	/**
	 * List of required data
	 */
	val requiredData: Collection<TrackerComponentRequirement>

	/**
	 * Returns true if all requirements are met
	 */
	fun requirementsMet(data: CollectionTempData): Boolean {
		return requiredData.all { it.isRequirementFulfilled(data) }
	}
}
