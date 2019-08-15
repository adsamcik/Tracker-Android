package com.adsamcik.tracker.tracker.component

import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal interface TrackerDataConsumerComponent {
	val requiredData: Collection<TrackerComponentRequirement>

	fun requirementsMet(data: CollectionTempData): Boolean {
		return requiredData.all { it.isRequirementFulfilled(data) }
	}
}
