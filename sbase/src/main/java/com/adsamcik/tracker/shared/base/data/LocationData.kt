package com.adsamcik.tracker.shared.base.data

import android.location.Location

data class LocationData(
		val locations: List<Location>,
		val previousLocation: Location?,
		val distance: Float?
) {
	val lastLocation: Location get() = locations.last()

	class Builder {
		private var locationList: List<Location>? = null

		private var previousLocation: Location? = null

		private var distance: Float? = null

		fun setLocation(location: Location) {
			setLocations(listOf(location))
		}

		fun setLocations(locations: List<Location>) {
			require(locationList == null)
			locationList = locations
		}

		fun setPreviousLocation(previousLocation: Location, distance: Float) {
			require(this.previousLocation == null)
			require(this.distance == null)

			this.previousLocation = previousLocation
			this.distance = distance
		}

		fun build(): LocationData {
			val locationList = requireNotNull(locationList)
			return LocationData(locationList, previousLocation, distance)
		}
	}
}
