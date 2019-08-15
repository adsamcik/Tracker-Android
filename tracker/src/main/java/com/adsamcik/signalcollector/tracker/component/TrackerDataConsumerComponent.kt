package com.adsamcik.signalcollector.tracker.component

import com.adsamcik.signalcollector.tracker.data.collection.CollectionTempData

internal interface TrackerDataConsumerComponent {
	val requiredData: Collection<TrackerComponentRequirement>

	fun requirementsMet(data: CollectionTempData): Boolean {
		return requiredData.all { it.isRequirementFulfilled(data) }
	}
}
