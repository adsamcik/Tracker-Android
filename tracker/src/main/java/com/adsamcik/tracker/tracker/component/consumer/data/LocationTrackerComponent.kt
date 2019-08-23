package com.adsamcik.tracker.tracker.component.consumer.data

import android.content.Context
import com.adsamcik.tracker.common.data.MutableCollectionData
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData

internal class LocationTrackerComponent : DataTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf(
			TrackerComponentRequirement.LOCATION)

	override suspend fun onDisable(context: Context) = Unit
	override suspend fun onEnable(context: Context) = Unit

	override suspend fun onDataUpdated(tempData: CollectionTempData, collectionData: MutableCollectionData) {
		val locationResult = tempData.getLocationData(this)
		collectionData.setLocation(locationResult.lastLocation)
	}
}

