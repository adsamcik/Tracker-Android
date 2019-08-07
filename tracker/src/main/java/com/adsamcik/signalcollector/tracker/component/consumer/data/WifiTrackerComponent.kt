package com.adsamcik.signalcollector.tracker.component.consumer.data

import android.content.Context
import android.location.Location
import com.adsamcik.signalcollector.common.data.MutableCollectionData
import com.adsamcik.signalcollector.common.extension.LocationExtensions
import com.adsamcik.signalcollector.tracker.component.DataTrackerComponent
import com.adsamcik.signalcollector.tracker.component.TrackerComponentRequirement
import com.adsamcik.signalcollector.tracker.data.collection.CollectionTempData
import com.adsamcik.signalcollector.tracker.data.collection.WifiScanData
import kotlin.math.abs

internal class WifiTrackerComponent : DataTrackerComponent {

	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf(TrackerComponentRequirement.WIFI)


	override suspend fun onDataUpdated(tempData: CollectionTempData, collectionData: MutableCollectionData) {
		val scanData = tempData.getWifiData(this)
		val locationResult = tempData.tryGetLocationResult()
		if (locationResult != null) {
			val location = locationResult.lastLocation
			val locations = locationResult.locations
			if (locations.size >= 2) {
				val nearestLocation = locations.sortedBy { abs(scanData.relativeTimeNanos - it.elapsedRealtimeNanos) }.take(2)
				val firstIndex = if (nearestLocation[0].time < nearestLocation[1].time) 0 else 1

				val first = nearestLocation[firstIndex]
				val second = nearestLocation[(firstIndex + 1).rem(2)]
				setWifi(scanData, collectionData, first, second, first.distanceTo(second))
			} else {
				val previousLocation = tempData.tryGetPreviousLocation()
				val distance = tempData.tryGetDistance()
				if (previousLocation != null && distance != null) {
					setWifi(scanData, collectionData, previousLocation, location, distance)
				}
			}
		} else {
			setWifi(scanData, collectionData)
		}
	}

	private fun setWifi(scanData: WifiScanData, collectionData: MutableCollectionData) {
		collectionData.setWifi(null, scanData.timeMillis, scanData.data)
	}

	private fun setWifi(scanData: WifiScanData,
	                    collectionData: MutableCollectionData,
	                    firstLocation: Location,
	                    secondLocation: Location,
	                    distanceBetweenFirstAndSecond: Float) {
		val timeDelta = (scanData.relativeTimeNanos - firstLocation.elapsedRealtimeNanos).toDouble() / (secondLocation.elapsedRealtimeNanos - firstLocation.elapsedRealtimeNanos).toDouble()
		val wifiDistance = distanceBetweenFirstAndSecond * timeDelta
		if (wifiDistance <= MAX_DISTANCE_TO_WIFI) {
			val interpolatedLocation = LocationExtensions.interpolateLocation(firstLocation, secondLocation, timeDelta)
			collectionData.setWifi(interpolatedLocation, scanData.timeMillis, scanData.data)
		}
	}

	override suspend fun onEnable(context: Context) {}

	override suspend fun onDisable(context: Context) {}


	companion object {
		private const val MAX_DISTANCE_TO_WIFI = 100
	}
}