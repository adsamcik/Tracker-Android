package com.adsamcik.tracker.tracker.component.consumer.data

import android.content.Context
import android.location.Location
import android.net.wifi.WifiManager
import com.adsamcik.tracker.shared.base.data.MutableCollectionData
import com.adsamcik.tracker.shared.base.extension.LocationExtensions
import com.adsamcik.tracker.shared.base.extension.wifiManager
import com.adsamcik.tracker.tracker.component.DataTrackerComponent
import com.adsamcik.tracker.tracker.component.TrackerComponentRequirement
import com.adsamcik.tracker.tracker.data.collection.TrackingCycle
import kotlin.math.min
import com.adsamcik.tracker.tracker.data.collection.WifiScanData
import kotlin.math.abs

internal class WifiTrackerComponent : DataTrackerComponent {

	private var wifiManager: WifiManager? = null

	override val requiredData: Collection<TrackerComponentRequirement> = mutableListOf(
			TrackerComponentRequirement.WIFI
	)


	override suspend fun onDataUpdated(
			cycle: TrackingCycle,
			collectionData: MutableCollectionData
	) {
		val scanData = requireNotNull(cycle.wifiScan)
		val locationData = cycle.location
		var geotagged = false
		if (locationData != null) {
			val location = locationData.lastLocation
			val locations = locationData.locations
			if (locations.size >= 2) {
				val nearestLocations = locations.sortedBy {
					abs(scanData.relativeTimeNanos - it.elapsedRealtimeNanos)
				}.take(2).sortedBy { it.elapsedRealtimeNanos }
				val first = nearestLocations[0]
				val second = nearestLocations[1]
				geotagged = setWifi(
					scanData,
					collectionData,
					first,
					second,
					first.distanceTo(second),
				)
			} else {
				val previousLocation = locationData.previousLocation
				val distance = locationData.distance
				if (previousLocation != null && distance != null) {
					geotagged = setWifi(scanData, collectionData, previousLocation, location, distance)
				}
			}
		}
		if (!geotagged) setWifi(scanData, collectionData)
	}

	private fun setWifi(scanData: WifiScanData, collectionData: MutableCollectionData) {
		collectionData.setWifi(
				null,
				scanData.timeMillis,
				scanData.data,
				requireNotNull(wifiManager)
		)
	}

	private fun setWifi(
			scanData: WifiScanData,
			collectionData: MutableCollectionData,
			firstLocation: Location,
			secondLocation: Location,
			distanceBetweenFirstAndSecond: Float
	): Boolean {
		val elapsedDelta = secondLocation.elapsedRealtimeNanos - firstLocation.elapsedRealtimeNanos
		if (elapsedDelta <= 0L) return false

		val timeDelta = (scanData.relativeTimeNanos - firstLocation.elapsedRealtimeNanos).toDouble() /
			elapsedDelta.toDouble()
		if (timeDelta !in 0.0..1.0) return false

		val distanceToClosestFix = distanceBetweenFirstAndSecond * min(timeDelta, 1.0 - timeDelta)
		if (distanceToClosestFix > MAX_DISTANCE_TO_WIFI) return false

		val interpolatedLocation = LocationExtensions.interpolateLocation(
			firstLocation,
			secondLocation,
			timeDelta,
		)
		collectionData.setWifi(
			interpolatedLocation,
			scanData.timeMillis,
			scanData.data,
			requireNotNull(wifiManager),
		)
		return true
	}

	override suspend fun onEnable(context: Context) {
		wifiManager = context.wifiManager
	}

	override suspend fun onDisable(context: Context) {
		wifiManager = null
	}


	companion object {
		private const val MAX_DISTANCE_TO_WIFI = 100
	}
}

