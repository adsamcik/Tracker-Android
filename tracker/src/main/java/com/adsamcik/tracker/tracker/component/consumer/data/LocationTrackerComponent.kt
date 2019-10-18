package com.adsamcik.tracker.tracker.component.consumer.data

import android.content.Context
import android.location.Location
import com.adsamcik.tracker.common.Time
import com.adsamcik.tracker.common.data.MutableCollectionData
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.CollectionTempData
import kotlin.math.abs

internal class LocationTrackerComponent : DataTrackerComponent {
	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf(
			TrackerComponentRequirement.LOCATION
	)

	override suspend fun onDisable(context: Context) = Unit
	override suspend fun onEnable(context: Context) = Unit


	private fun calculateSpeed(prevLocation: Location, location: Location): Float {
		val recordedSpeed = location.speed
		val distance = location.distanceTo(prevLocation)
		val deltaS = (location.elapsedRealtimeNanos - prevLocation.elapsedRealtimeNanos) / Time.SECOND_IN_NANOSECONDS
		val calculatedSpeed = distance / deltaS

		if (recordedSpeed <= 0f) return calculatedSpeed

		val changePercentage = abs(calculatedSpeed - recordedSpeed) / recordedSpeed

		return if (changePercentage >= 0.2f) {
			calculatedSpeed
		} else {
			recordedSpeed
		}
	}

	override suspend fun onDataUpdated(
			tempData: CollectionTempData,
			collectionData: MutableCollectionData
	) {
		val locationResult = tempData.getLocationData(this)

		val location = locationResult.lastLocation
		val previousLocation = locationResult.previousLocation

		if (previousLocation != null) {
			val speed = calculateSpeed(previousLocation, location)
			location.speed = speed
		}

		collectionData.setLocation(location)
	}
}

